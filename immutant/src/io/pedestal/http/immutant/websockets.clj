(ns io.pedestal.http.immutant.websockets
  (:require [immutant.web :as web]
            [immutant.web.async :as async]))

(defn start-ws-connection
  ([on-connect-fn]
   (start-ws-connection on-connect-fn 10))
  ([on-connect-fn send-buffer-or-n]
   (fn [send-ch]
     (on-connect-fn "immutant-ws-session" send-ch))))

(defn make-ws-listener
  [ws-map]
  (let [listener {}
        listener (when-let [f (:on-connect ws-map)]
                   (assoc listener
                          :on-open
                          (fn [channel] (f channel))))
        listener (when-let [f (:on-close ws-map)]
                   (assoc listener
                          :on-close
                          (fn [channel {:keys [code reason ]}] (f code reason))))
        listener (when-let [f (:on-error ws-map)]
                   (assoc listener
                          :on-error
                          (fn [channel throwable] (f throwable))))
        listener (when-let [f (:on-text ws-map)]
                   (assoc listener
                          :on-message
                          (fn [channel m] (f m))))
        listener (when-let [f (:on-binary ws-map)]
                   (assoc listener
                          :on-message
                          (fn [channel m]
                            ;; TODO m may be bytes
                            (let [payload (.getBytes m) 
                                  offset 0
                                  length (size payload)]
                              (f payload offset length)))))]
    listener))

(defn add-ws-endpoints
  [server ws-paths]
  (doseq [[path ws-map] ws-paths]
    (let [listener (make-ws-listener ws-map)
          handler (fn [request]
                    (async/as-channel (assoc request :websocket? true) listener))]
      (-> server (assoc server :path path) (->> (web/run handler))))))
