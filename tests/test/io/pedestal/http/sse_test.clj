; Copyright 2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.sse-test
  (:require [io.pedestal.interceptor.chain :as interceptor]
            [io.pedestal.http.sse :refer [start-event-stream]]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http.cors :as cors])
  (:import (clojure.core.async.impl.protocols Channel)))

(deftest sse-start-stream
  (let [fake-context        {:request {:headers {"origin" "http://foo.com:8080"}}}
        interceptor-context (interceptor/enqueue* fake-context
                                                  (cors/allow-origin ["http://foo.com:8080"])
                                                  ;; The `stream-ready-fn` takes the channel and the context
                                                  (start-event-stream (fn [ch _context] ch)))
        {{body                                          :body
          {content-type  "Content-Type"
           connection    "Connection"
           cache-control "Cache-Control"
           allow-origin  "Access-Control-Allow-Origin"} :headers
          status                                        :status} :response
         } (interceptor/execute interceptor-context)]
    (is body "Response has a body")
    (is (instance? Channel body) "Response body is a channel")
    (is (= 200 status)
        "A successful status code is sent to the client.")
    (is (= "text/event-stream; charset=UTF-8" content-type)
        "The mime type and character encoding are set with the servlet setContentType method")
    (is (= "close" connection)
        "The client is instructed to close the connection.")
    (is (= "no-cache" cache-control)
        "The client is instructed not to cache the event stream")
    (is (= "http://foo.com:8080" allow-origin)
        "The origin is allowed")))

