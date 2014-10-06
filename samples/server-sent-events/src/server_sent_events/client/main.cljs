(ns server-sent-events.client.main
  (:require [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn event-source!
  ([url]
   (event-source! url (async/chan 10) identity "message"))
  ([url ch]
   (event-source! url ch identity "message"))
  ([url ch f]
   (event-source! url ch f "message"))
  ([url ch f msg-type]
   (let [src (js/EventSource. url)]
     (.addEventListener src
                        msg-type
                        (fn [e]
                          (.log js/console "Got a event: " e)
                          (async/put! ch (f (.-data e))))
                        false)
     {:source src
      :channel ch})))

;; Let's see the messages come through
;; ------------------------------------
(def sse-chan (async/chan 10))
(def sse-events (event-source! "/" sse-chan))

(.log js/console "Starting to fetch events...")
(go-loop [event (async/<! sse-chan)]
  (if event
    (do (.log js/console "New SSE event:" event)
        (recur (async/<! sse-chan)))
    (.log js/console "There are no more events; Event channel is closed")))

