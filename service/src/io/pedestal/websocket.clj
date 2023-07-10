(ns io.pedestal.websocket
  (:import (io.pedestal.websocket FnEndpoint)
           (jakarta.websocket EndpointConfig Session MessageHandler$Whole)
           (jakarta.websocket.server ServerContainer ServerEndpointConfig ServerEndpointConfig$Builder)
           (java.nio ByteBuffer)))


(defn- message-handler
  ^MessageHandler$Whole [f]
  (reify MessageHandler$Whole
    (onMessage [_ message]
      (f message))))

(defn- make-endpoint-callback
  "Given a map defining the WebService behavior, returns a function that will ultimately be used
  by the FnEndpoint instance."
  [ws-map]
  (let [{:keys [on-open
                on-close
                on-error
                on-text                                     ;; TODO: rename to `on-string`?
                on-binary]} ws-map
        maybe-invoke-callback (fn [f & args]
                                (when f
                                  (apply f args)))
        full-on-open (fn [^Session session ^EndpointConfig config]
                       ;; TODO: support adding a pong handler?
                       (when on-text
                         (.addMessageHandler session String (message-handler on-text)))

                       (when on-binary
                         (.addMessageHandler session ByteBuffer (message-handler on-binary)))

                       (maybe-invoke-callback on-open session config))]
    (fn [event-type ^Session session event-value]
             (case event-type
               :on-open (full-on-open session event-value)
               :on-error (maybe-invoke-callback on-error session event-value)
               :on-close (maybe-invoke-callback on-close session event-value)))))

(defn add-endpoint
  [^ServerContainer container ^String path ws-map]
  (let [callback (make-endpoint-callback ws-map)
        config ^ServerEndpointConfig (-> (ServerEndpointConfig$Builder/create FnEndpoint path)
                     .build)
        user-properties ^java.util.Map (.getUserProperties config)]
    (.put user-properties FnEndpoint/USER_ATTRIBUTE_KEY callback)
    (.addEndpoint container config)))
