; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.http.http-kit.impl
  "Implementation details, subject to change at any time."
  {:added "0.8.0"}
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

      (send! [this data]
        (hk/send! this data false))

      (send! [this data close?]
        (when @*response
          (throw (ex-info "Mock Channel can only capture single response map"
                          {:response @*response
                           :data     data})))

        (when (realized? *response-promise)
          (throw (ex-info "Mock Channel: send! after close"
                          {:response @*response
                           :data     data})))

        (reset! *response data)

        (when close? (hk/close this)))

      (on-receive [_ _] (nyi "on-receive"))

      (on-ping [_ _] (nyi "on-ping"))

      (on-close [_ _] (nyi "on-close")))))
