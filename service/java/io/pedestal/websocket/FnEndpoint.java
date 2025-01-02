// Copyright 2023 Cognitect, Inc.
//
// The use and distribution terms for this software are covered by the
// Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
// which can be found in the file epl-v10.html at the root of this distribution.
//
// By using this software in any fashion, you are agreeing to be bound by
// the terms of this license.
//
// You must not remove this notice, or any other, from this software.

package io.pedestal.websocket;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.Symbol;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;

/**
 * Endpoint that delegates to a provided function.
 *
 * Because of the intricacies of the websocket APIs, this call must have a no-args constructor and
 * we make use of the available user properties to capture the callback function during the onOpen() call.
 *
 * The callback function is passed a keyword, the session, and a value.
 * The keyword is :on-open, :on-close, or :on-error, and
 * the value is specific to the callback type.
 */
public class FnEndpoint extends Endpoint {

    public static final String USER_ATTRIBUTE_KEY = "io.pedestal.websocket.FnEndpoint";

    private static Keyword kw(String s) {
        return Keyword.intern(Symbol.intern(null, s));
    }

    private static final Keyword onOpenKw = kw("on-open");
    private static final Keyword onCloseKw = kw("on-close");
    private static final Keyword onErrorKw = kw("on-error");

    /**
     * Set in the onOpen() callback.
     */
    private IFn callback;

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        callback = (IFn) config.getUserProperties().get(USER_ATTRIBUTE_KEY);

        callback.invoke(onOpenKw, session, config);
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        if (callback != null) {
            callback.invoke(onCloseKw, session, closeReason);
        }
    }

    @Override
    public void onError(Session session, Throwable t) {
        if (callback != null) {
            callback.invoke(onErrorKw, session, t);
        }
    }
}
