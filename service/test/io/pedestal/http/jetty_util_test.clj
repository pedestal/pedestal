(ns io.pedestal.http.jetty-util-test
  (:use clojure.test
        io.pedestal.http.jetty)
  (:require [clj-http.client :as http]
            [io.pedestal.http.jetty.util :as jetty-util]
            [io.pedestal.http.jetty-test :as test-util])
  (:import (org.eclipse.jetty.servlets DoSFilter)
           (org.eclipse.jetty.server.handler.gzip GzipHandler)))

;; NOTE:
;;   In later versions of Jetty, GZip became a `Handler` to allow it to optimize
;;   async requests.  The Gzip ServletFilter example has been updated.
;;
;;   Servlet Filters are now tested using DoSFilter

(deftest simple-gzip-handler
  (testing "A Simple GZip Handler"
    (test-util/with-server test-util/hello-world {:port 4347
                                                  :container-options {:context-configurator (fn [c]
                                                                                              (let [gzip-handler (GzipHandler.)]
                                                                                                (.setGzipHandler c gzip-handler)
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
      (let [response (http/get "http://localhost:4347")
            response2 (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= "close" (get-in response [:headers "connection"])))
        (is (= (:body response) "Hello World"))))))
