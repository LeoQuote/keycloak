/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.services.managers;

import org.jboss.logging.Logger;
import org.keycloak.common.util.ServerCookie.SameSiteAttributeValue;
import org.keycloak.common.util.Time;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.freemarker.AuthenticationStateCookie;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.protocol.RestartLoginCookie;
import org.keycloak.services.util.CookieHelper;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.sessions.StickySessionEncoderProvider;

import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class AuthenticationSessionManager {

    public static final String AUTH_SESSION_ID = "AUTH_SESSION_ID";

    public static final int AUTH_SESSION_COOKIE_LIMIT = 3;

    private static final Logger log = Logger.getLogger(AuthenticationSessionManager.class);

    private final KeycloakSession session;

    public AuthenticationSessionManager(KeycloakSession session) {
        this.session = session;
    }


    /**
     * Creates a fresh authentication session for the given realm . Optionally sets the browser
     * authentication session cookie {@link #AUTH_SESSION_ID} with the ID of the new session.
     * @param realm
     * @param browserCookie Set the cookie in the browser for the
     * @return
     */
    public RootAuthenticationSessionModel createAuthenticationSession(RealmModel realm, boolean browserCookie) {
        RootAuthenticationSessionModel rootAuthSession = session.authenticationSessions().createRootAuthenticationSession(realm);

        if (browserCookie) {
            setAuthSessionCookie(rootAuthSession.getId(), realm);
        }

        return rootAuthSession;
    }


    public RootAuthenticationSessionModel getCurrentRootAuthenticationSession(RealmModel realm) {
        List<String> authSessionCookies = getAuthSessionCookies(realm);

        return authSessionCookies.stream().map(oldEncodedId -> {
            AuthSessionId authSessionId = decodeAuthSessionId(oldEncodedId);
            String sessionId = authSessionId.getDecodedId();

            RootAuthenticationSessionModel rootAuthSession = session.authenticationSessions().getRootAuthenticationSession(realm, sessionId);

            if (rootAuthSession != null) {
                reencodeAuthSessionCookie(oldEncodedId, authSessionId, realm);
                return rootAuthSession;
            }

            return null;
        }).filter(authSession -> Objects.nonNull(authSession)).findFirst().orElse(null);
    }


    public UserSessionModel getUserSessionFromAuthCookie(RealmModel realm) {
        List<String> authSessionCookies = getAuthSessionCookies(realm);

        return authSessionCookies.stream().map(oldEncodedId -> {
            AuthSessionId authSessionId = decodeAuthSessionId(oldEncodedId);
            String sessionId = authSessionId.getDecodedId();

            UserSessionModel userSession = session.sessions().getUserSession(realm, sessionId);

            if (userSession != null) {
                reencodeAuthSessionCookie(oldEncodedId, authSessionId, realm);
                return userSession;
            }

            return null;
        }).filter(authSession -> Objects.nonNull(authSession)).findFirst().orElse(null);
    }


    /**
     * Returns current authentication session if it exists, otherwise returns {@code null}.
     * @param realm
     * @return
     */
    public AuthenticationSessionModel getCurrentAuthenticationSession(RealmModel realm, ClientModel client, String tabId) {
        List<String> authSessionCookies = getAuthSessionCookies(realm);

        return authSessionCookies.stream().map(oldEncodedId -> {
            AuthSessionId authSessionId = decodeAuthSessionId(oldEncodedId);
            String sessionId = authSessionId.getDecodedId();

            AuthenticationSessionModel authSession = getAuthenticationSessionByIdAndClient(realm, sessionId, client, tabId);

            if (authSession != null) {
                reencodeAuthSessionCookie(oldEncodedId, authSessionId, realm);
                return authSession;
            }

            return null;
        }).filter(authSession -> Objects.nonNull(authSession)).findFirst().orElse(null);
    }


    /**
     * @param authSessionId decoded authSessionId (without route info attached)
     * @param realm
     */
    public void setAuthSessionCookie(String authSessionId, RealmModel realm) {
        UriInfo uriInfo = session.getContext().getUri();
        String cookiePath = AuthenticationManager.getRealmCookiePath(realm, uriInfo);

        boolean sslRequired = realm.getSslRequired().isRequired(session.getContext().getConnection());

        StickySessionEncoderProvider encoder = session.getProvider(StickySessionEncoderProvider.class);
        String encodedAuthSessionId = encoder.encodeSessionId(authSessionId);

        CookieHelper.addCookie(AUTH_SESSION_ID, encodedAuthSessionId, cookiePath, null, null, -1, sslRequired, true, SameSiteAttributeValue.NONE, session);

        log.debugf("Set AUTH_SESSION_ID cookie with value %s", encodedAuthSessionId);
    }


    /**
     *
     * @param encodedAuthSessionId encoded ID with attached route in cluster environment (EG. "5e161e00-d426-4ea6-98e9-52eb9844e2d7.node1" )
     * @return object with decoded and actually encoded authSessionId
     */
    AuthSessionId decodeAuthSessionId(String encodedAuthSessionId) {
        log.debugf("Found AUTH_SESSION_ID cookie with value %s", encodedAuthSessionId);
        StickySessionEncoderProvider encoder = session.getProvider(StickySessionEncoderProvider.class);
        String decodedAuthSessionId = encoder.decodeSessionId(encodedAuthSessionId);
        String reencoded = encoder.encodeSessionId(decodedAuthSessionId);

        return new AuthSessionId(decodedAuthSessionId, reencoded);
    }


    void reencodeAuthSessionCookie(String oldEncodedAuthSessionId, AuthSessionId newAuthSessionId, RealmModel realm) {
        if (!oldEncodedAuthSessionId.equals(newAuthSessionId.getEncodedId())) {
            log.debugf("Route changed. Will update authentication session cookie. Old: '%s', New: '%s'", oldEncodedAuthSessionId,
                    newAuthSessionId.getEncodedId());
            setAuthSessionCookie(newAuthSessionId.getDecodedId(), realm);
        }
    }


    /**
     * @param realm
     * @return list of the values of AUTH_SESSION_ID cookies. It is assumed that values could be encoded with route added (EG. "5e161e00-d426-4ea6-98e9-52eb9844e2d7.node1" )
     */
    List<String> getAuthSessionCookies(RealmModel realm) {
        Set<String> cookiesVal = CookieHelper.getCookieValues(session, AUTH_SESSION_ID);
        List<String> authSessionIds = cookiesVal.stream().limit(AUTH_SESSION_COOKIE_LIMIT).collect(Collectors.toList());

        if (authSessionIds.isEmpty()) {
            log.debugf("Not found AUTH_SESSION_ID cookie");
        }

        return authSessionIds.stream().filter(new Predicate<String>() {
            @Override
            public boolean test(String id) {
                StickySessionEncoderProvider encoder = session.getProvider(StickySessionEncoderProvider.class);
                // in case the id is encoded with a route when running in a cluster
                String decodedId = encoder.decodeSessionId(id);
                // we can't blindly trust the cookie and assume it is valid and referencing a valid root auth session
                // but make sure the root authentication session actually exists
                // without this check there is a risk of resolving user sessions from invalid root authentication sessions as they share the same id
                return session.authenticationSessions().getRootAuthenticationSession(realm, decodedId) != null;
            }
        }).collect(Collectors.toList());
    }


    public void removeAuthenticationSession(RealmModel realm, AuthenticationSessionModel authSession, boolean expireRestartCookie) {
        RootAuthenticationSessionModel rootAuthSession = authSession.getParentSession();

        log.debugf("Removing root authSession '%s'. Expire restart cookie: %b", rootAuthSession.getId(), expireRestartCookie);
        session.authenticationSessions().removeRootAuthenticationSession(realm, rootAuthSession);

        // expire restart cookie
        if (expireRestartCookie) {
            UriInfo uriInfo = session.getContext().getUri();
            RestartLoginCookie.expireRestartCookie(realm, uriInfo, session);
            AuthenticationStateCookie.expireCookie(realm, session);

            // With browser session, this makes sure that info/error pages will be rendered correctly when locale is changed on them
            session.getProvider(LoginFormsProvider.class).setDetachedAuthSession();
        }
    }

    /**
     * Remove authentication session from root session. Possibly remove whole root authentication session if there are no other browser tabs
     * @param realm
     * @param authSession
     * @return true if whole root authentication session was removed. False just if single tab was removed
     */
    public boolean removeTabIdInAuthenticationSession(RealmModel realm, AuthenticationSessionModel authSession) {
        RootAuthenticationSessionModel rootAuthSession = authSession.getParentSession();
        rootAuthSession.removeAuthenticationSessionByTabId(authSession.getTabId());
        if (rootAuthSession.getAuthenticationSessions().isEmpty()) {
            // no more tabs, remove the session completely
            removeAuthenticationSession(realm, authSession, true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * This happens when one browser tab successfully finished authentication (including required actions and consent screen if applicable)
     * Just authenticationSession of the current browser tab is removed from "root authentication session" and other tabs are kept, so
     * authentication can be automatically finished in other browser tabs (typically with authChecker.js javascript)
     *
     * @param realm
     * @param authSession
     */
    public void updateAuthenticationSessionAfterSuccessfulAuthentication(RealmModel realm, AuthenticationSessionModel authSession) {
        boolean removedRootAuthSession = removeTabIdInAuthenticationSession(realm, authSession);
        if (!removedRootAuthSession) {
            RootAuthenticationSessionModel rootAuthSession = authSession.getParentSession();

            // 1 minute by default. Same timeout, which is used for client to complete "authorization code" flow
            // Very short timeout should be OK as when this cookie is set, other existing browser tabs are supposed to be refreshed immediately by JS script authChecker.js
            // and login user automatically. No need to have authenticationSession and cookie living any longer
            int authSessionExpiresIn = realm.getAccessCodeLifespan();

            // Set timestamp to the past to make sure that authSession is scheduled for expiration in "authSessionExpiresIn" seconds
            int authSessionExpirationTime = Time.currentTime() - SessionExpiration.getAuthSessionLifespan(realm) + authSessionExpiresIn;
            rootAuthSession.setTimestamp(authSessionExpirationTime);

            log.tracef("Removed authentication session of root session '%s' with tabId '%s'. But there are remaining tabs in the root session. Root authentication session will expire in %d seconds", rootAuthSession.getId(), authSession.getTabId(), authSessionExpiresIn);

            AuthenticationStateCookie.generateAndSetCookie(session, realm, rootAuthSession, authSessionExpiresIn);
        }
    }

    // Check to see if we already have authenticationSession with same ID
    public UserSessionModel getUserSession(AuthenticationSessionModel authSession) {
        return session.sessions().getUserSession(authSession.getRealm(), authSession.getParentSession().getId());
    }


    // Don't look at cookie. Just lookup authentication session based on the ID and client. Return null if not found
    public AuthenticationSessionModel getAuthenticationSessionByIdAndClient(RealmModel realm, String authSessionId, ClientModel client, String tabId) {
        RootAuthenticationSessionModel rootAuthSession = session.authenticationSessions().getRootAuthenticationSession(realm, authSessionId);
        return rootAuthSession==null ? null : rootAuthSession.getAuthenticationSession(client, tabId);
    }
}
