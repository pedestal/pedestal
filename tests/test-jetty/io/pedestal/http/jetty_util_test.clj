; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty-util-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [io.pedestal.http.jetty.util :as jetty-util]
            [io.pedestal.http.jetty-test :as test-util])
  (:import (org.eclipse.jetty.ee10.servlet ServletContextHandler)
           (org.eclipse.jetty.ee10.servlets DoSFilter)
           (org.eclipse.jetty.server.handler.gzip GzipHandler)))

;; NOTE:
;;   In later versions of Jetty, GZip became a `Handler` to allow it to optimize
;;   async requests.  The Gzip ServletFilter example has been updated.
;;
;;   Servlet Filters are now tested using DoSFilter

(deftest simple-gzip-handler
  (testing "A Simple GZip Handler"
    (test-util/with-server test-util/hello-world {:port 4347
                                                  :container-options {:context-configurator (fn [ ^ServletContextHandler c]
                                                                                              (let [gzip-handler (GzipHandler.)]
                                                                                                (.insertHandler c gzip-handler)
                                                                                                c))}}
                           (let [response (http/get "http://localhost:4347")]
                             (is (= (:status response) 200))
                             (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (.startsWith ^String (:orig-content-encoding response) "gzip"))
        (is (= (:body response) "Hello World"))))))

(def custom-dos (jetty-util/filter-holder (DoSFilter.) {"maxRequestsPerSec" "1" ;; Default is 25
                                                        "insertHeaders" "true"}))
(deftest simple-dos-filter
  (testing "A Simple DoS filter"
    (test-util/with-server test-util/hello-world {:port 4347
                                                  :container-options {:context-configurator #(jetty-util/add-servlet-filter % {:filter DoSFilter})}}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= "close" (get-in response [:headers "connection"])))
        (is (= (:body response) "Hello World")))))
  (testing "A FilterHolder (also DoS)"
    (test-util/with-server test-util/hello-world {:port 4347
                                                  :container-options {:context-configurator #(jetty-util/add-servlet-filter % {:filter custom-dos})}}
      (let [response (http/get "http://localhost:4347")]
        (http/get "http://localhost:4347")
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= "close" (get-in response [:headers "connection"])))
        (is (= (:body response) "Hello World"))))))
