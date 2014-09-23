(ns io.pedestal.http.jetty-util-test
  (:use clojure.test
        io.pedestal.http.jetty)
  (:require [clj-http.client :as http]
            [io.pedestal.http.jetty.util :as jetty-util]
            [io.pedestal.http.jetty-test :as test-util])
  (:import (org.eclipse.jetty.servlets GzipFilter
                                       IncludableGzipFilter)))

(def custom-gzip (jetty-util/filter-holder (GzipFilter.) {"mimeTypes" "text/javascript,text/plain"
                                                          "minGzipSize" "0"}))

(deftest simple-filter
  (testing "A Simple GZip filter"
    (test-util/with-server test-util/hello-world {:port 4347
                                                  :container-options {:context-configurator #(jetty-util/add-servlet-filter % {:filter GzipFilter})}}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (.startsWith ^String (:orig-content-encoding response) "gzip"))
        (is (= (:body response) "Hello World")))))
  (testing "A FilterHolder (also GZip)"
    (test-util/with-server test-util/hello-world {:port 4347
                                                  :container-options {:context-configurator #(jetty-util/add-servlet-filter % {:filter custom-gzip})}}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (.startsWith ^String (:orig-content-encoding response) "gzip"))
        (is (= (:body response) "Hello World"))))))

