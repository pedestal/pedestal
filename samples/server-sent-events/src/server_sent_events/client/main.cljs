(ns server-sent-events.client.main
  (:require [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn data-identity [e]
  (.-data e))

(defn event-source!
  ([url-or-src]
   (event-source! url-or-src (async/chan 10) data-identity "message"))
  ([url-or-src ch]
   (event-source! url-or-src ch data-identity "message"))
  ([url-or-src ch f]
   (event-source! url-or-src ch f "message"))
  ([url-or-src ch f msg-type]
   (let [src (if (instance? js/EventSource url-or-src)
               url-or-src
               (js/EventSource. url-or-src))]
     (.addEventListener src
                        msg-type
                        (fn [e]
                          (async/put! ch (f e)))
                        false)
     {:source src
      :channel ch})))

;; Let's see the messages come through
;; ------------------------------------
(def sse-chan (async/chan 10))
;; If you named your event on the server side, you should be listening to that event here. ie: 'count'
(def sse-events (event-source! "/" sse-chan data-identity "count"))
;; If the server didn't specify a :name for the event, they'll be of type "message"
;(def sse-events (event-source! "/" sse-chan))

(.log js/console "Starting to fetch events...")
(go-loop [event (async/<! sse-chan)]
  (if event
    (do (.log js/console "New SSE event:" event)
        (recur (async/<! sse-chan)))
    (.log js/console "There are no more events; Event channel is closed")))

