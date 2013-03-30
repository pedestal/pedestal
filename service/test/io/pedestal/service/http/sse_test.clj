; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http.sse-test
  (:require [io.pedestal.service.impl.interceptor :as interceptor]
            [io.pedestal.service.log :as log]
            [io.pedestal.service.http.sse :refer :all]
            [io.pedestal.service.http.cors :as cors])
  (:use [clojure.test]
        [io.pedestal.service.test]))

(deftest sse-setup-test
  (let [test-servlet-response (test-servlet-response)
        {byte-array-output-stream :output-stream
         status :status
         headers-map :headers-map} (meta test-servlet-response)
        fake-context {:request {:servlet-response test-servlet-response}
                      :servlet-response test-servlet-response}
        semaphore (promise)
        sse-rig (fn [sse-context]
                  (log/info :msg "in sse rig")
                  (send-event sse-context "test" "passes")
                  (end-event-stream sse-context)
                  (deliver semaphore (.isCommitted (:servlet-response sse-context))))
        interceptor-context (interceptor/enqueue fake-context (start-event-stream sse-rig))]
    (log/info :context interceptor-context
              :queue (seq (:io.pedestal.service.impl.interceptor/queue interceptor-context)))
    (log/info :execution-call (interceptor/execute interceptor-context))
    (is (= @semaphore true))
    (is (= 200 @status)
        "A successful status code is sent to the client.")
    (is (= "text/event-stream; charset=UTF-8" (:content-type @headers-map))
        "The mime type and character encoding are set with the servlet setContentType method")
    (is (= "close" ((:set-header @headers-map) "Connection"))
        "The client is instructed to close the connection.")
    (is (= "no-cache" ((:set-header @headers-map) "Cache-control"))
        "The client is instructed not to cache the event stream")))

(deftest sse-cors-test
  (let [test-servlet-response (test-servlet-response)
        {byte-array-output-stream :output-stream
         status :status
         headers-map :headers-map} (meta test-servlet-response)
        fake-context {:request {:servlet-response test-servlet-response
                                :headers {"origin" "http://foo.com:8080"}}
                      :servlet-response test-servlet-response}
        semaphore (promise)
        sse-rig (fn [sse-context]
                  (log/info :msg "in sse rig")
                  (send-event sse-context "test" "passes")
                  (end-event-stream sse-context)
                  (deliver semaphore (.isCommitted (:servlet-response sse-context))))
        interceptor-context (interceptor/enqueue fake-context
                                                 (cors/allow-origin [#"foo.com"])
                                                 (start-event-stream sse-rig))]
    (log/info :context interceptor-context
              :queue (seq (:io.pedestal.service.impl.interceptor/queue interceptor-context)))
    (log/info :execution-call (interceptor/execute interceptor-context))
    (is (= @semaphore true))
    (is (= 200 @status)
        "A successful status code is sent to the client.")
    (is (= "text/event-stream; charset=UTF-8" (:content-type @headers-map))
        "The mime type and character encoding are set with the servlet setContentType method")
    (is (= "http://foo.com:8080" ((:set-header @headers-map) "Access-Control-Allow-Origin"))
        "The origin is allowed")
    (is (= "close" ((:set-header @headers-map) "Connection"))
        "The client is instructed to close the connection.")
    (is (= "no-cache" ((:set-header @headers-map) "Cache-control"))
        "The client is instructed not to cache the event stream")))
