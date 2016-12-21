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
  "Creates an Immutant listener (map) for a WebSocket connection.
  Note that on-text and on-binary will override each other in this implementation,
  and you should use on-message if you need a single handler to fulfill both binary
  and text messages."
  [ws-map]
  (let [{:keys [on-connect
                on-close
                on-error
                on-text
                on-binary
                on-message]} ws-map
        full-set {:on-connect {:on-open
                               (fn [channel]
                                 (on-connect channel))}

                  :on-close {:on-close
                             (fn [channel {:keys [code reason]}]
                               (on-close code reason))}

                  :on-error {:on-error
                             (fn [channel throwable]
                               (on-error throwable))}

                  :on-text {:on-message
                            (fn [channel m]
                              (async/send! channel (on-text m)))}

                  :on-binary {:on-message
                              (fn [channel m]
                                (let [payload (if (instance? String m) (.getBytes m) m)
                                      offset 0
                                      length (count payload)]
                                  (async/send! channel (on-binary payload offset length))))}

                  :on-message {:on-message (fn [channel m]
                                             (async/send! channel (on-message m)))}}
        listener (reduce merge {} (map val (select-keys full-set (keys ws-map))))]
    listener))

(defn add-ws-endpoints
  [request ws-paths]
  (loop [ws-maps (seq ws-paths)
         request request]
    (if (empty? ws-maps) request
        (let [[path ws-map] (first ws-maps)
              listener (make-ws-listener ws-map)
              handler (fn [request]
                        (async/as-channel (assoc request :websocket? true) listener))]
          (recur (rest ws-maps) (-> request
                                    (assoc :path path)
                                    (->> (web/run handler))))))))

