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

    public static final String USER_ATTRIBUTE_KEY = "io.pedestal.http.jetty.websockets.FnEndpoint";

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
