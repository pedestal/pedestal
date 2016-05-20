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
  (let [full-set {:on-connect
                  {:on-open
                   (fn [channel]
                     ((:on-connect ws-map) channel))}
                  :on-close
                  {:on-close
                   (fn [channel {:keys [code reason]}]
                     ((:on-close ws-map) code reason))}
                  :on-error
                  {:on-error
                   (fn [channel throwable]
                     ((:on-error ws-map) throwable))}
                  :on-text
                  {:on-message
                   (fn [channel m]
                     (async/send! channel ((:on-text ws-map) m)))}
                  :on-binary
                  {:on-message
                   (fn [channel m]
                     (let [payload (if (instance? String m) (.getBytes m) m)
                           offset 0
                           length (count payload)]
                       (async/send! channel ((:on-binary ws-map) payload offset length))))}}
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
          (recur (rest ws-maps) (-> request (assoc :path path) (->> (web/run handler))))))))
