; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Integration tests of request handling."}
  io.pedestal.http.request-handling-test
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [ring.util.response :as ring-response]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.interceptor :as interceptor :refer [defhandler]]
            [io.pedestal.http :as service])
  (:use [clojure.test]
        [clojure.pprint]
        [io.pedestal.test]))

(defhandler terminator
  "An interceptor that creates a valid ring response and places it in
  the context, terminating the interceptor chain."
  [request]
  {:status 200
   :body "Terminated."
   :headers {}})

(defhandler leaf-handler
  "An interceptor that creates a valid ring response and places it in
  the context."
  [request]
  {:status 200
   :body "Leaf handled!"
   :headers {}})

(defroutes request-handling-routes
  [[:request-handling "request-handling.pedestal"
    ["/terminated" ^:interceptors [terminator]
     ["/leaf" {:get [:leaf1 leaf-handler]}]]
    ["/unterminated"
     ["/leaf" {:get [:leaf2 leaf-handler]}]]]])

(let [file (java.io.File/createTempFile "request-handling-test" ".txt")]
  (def tempfile-url
    (->> file
     .getName
     (str "http://request-handling.pedestal/")))
  (def tempdir
    (.getParent file))
  (spit file "some test data"))

(defn make-app [options]
  (-> options
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(def app (make-app {::service/routes request-handling-routes
                    ::service/file-path tempdir}))


(deftest termination-test
  (are [url body] (= body (->> url
                               (response-for app :get)
                               :body))
       "http://request-handling.pedestal/terminated/leaf" "Terminated."
       "http://request-handling.pedestal/unterminated/leaf" "Leaf handled!"
       "http://request-handling.pedestal/unrouted" "Not Found"
       "http://request-handling.pedestal/test.txt" "Text data on the classpath\n"
       tempfile-url "some test data"))

(interceptor/defafter custom-not-found
  [context]
  (if-not (servlet-interceptor/response-sent? context)
    (assoc context :response (ring-response/not-found "Custom Not Found"))
    context))

(deftest custom-not-found-test
  (let [app (make-app {::service/routes request-handling-routes
                       ::service/not-found-interceptor custom-not-found})]
    (is (= "Custom Not Found"
           (:body (response-for app :get "http://request-handling.pedestal/unrouted"))))))

(let [file (java.io.File/createTempFile "request-handling-test" ".css")]
  (def tempfile-url
    (->> file
     .getName
     (str "http://request-handling.pedestal/")))
  (def tempdir
    (.getParent file))
  (spit file "some test data"))

(deftest content-type-test
  (are [url content-type] (= content-type (get (->> url
                                                    (response-for app :get)
                                                    :headers) "Content-Type"))
       "http://request-handling.pedestal/test.html" "text/html"
       "http://request-handling.pedestal/test.js" "text/javascript"
       tempfile-url "text/css"))
