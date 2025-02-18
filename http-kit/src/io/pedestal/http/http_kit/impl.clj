(ns ^:no-doc io.pedestal.http.http-kit.impl
  "Implementation details, subject to change at any time."
  (:require [org.httpkit.server :as hk]))

(defn- nyi [s]
  (throw (IllegalStateException. (str "Not yet implemented: Channel." s))))

(defn mock-channel
  "Mock channel that captures a single send! for the response map. Not appropriate for simulating
  a websocket connection."
  [*response-promise]
  (let [*response (atom nil)]
    (reify hk/Channel

      (open? [_] (nyi "open?"))

      (websocket? [_] (nyi "websocket?"))

      (close [_]
        (if (realized? *response-promise)
          false
          (deliver *response-promise @*response)))

      (send! [_ data]
        (when @*response
          (throw (ex-info "Mock Channel can only capture single response map"
                          {:response @*response
                           :data     data})))

        (when (realized? *response-promise)
          (throw (ex-info "Mock Channel: send! after close"
                          {:response @*response
                           :data     data})))

        (reset! *response data))

      (send! [this data close?]
        (hk/send! this data)
        (when close? (hk/close this)))

      (on-receive [_ _] (nyi "on-receive"))

      (on-ping [_ _] (nyi "on-ping"))

      (on-close [_ _] (nyi "on-close")))))
