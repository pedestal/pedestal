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

(ns ^{:doc "Integration tests of request handling."}
  io.pedestal.http.request-handling-test
  (:require [ring.util.response :as ring-response]
            [clojure.test :refer [deftest is are]]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.http :as service])
  (:import (java.io File)))

(defn terminator
  "An interceptor that creates a valid ring response and places it in
  the context, terminating the interceptor chain."
  [_request]
  {:status  200
   :body    "Terminated."
   :headers {}})

(defn leaf-handler
  "An interceptor that creates a valid ring response and places it in
  the context."
  [_request]
  {:status  200
   :body    "Leaf handled!"
   :headers {}})

(def request-handling-routes
  `[[:request-handling "request-handling.pedestal"
     ["/terminated" ^:interceptors [terminator]
      ["/leaf" {:get [:leaf1 leaf-handler]}]]
     ["/unterminated"
      ["/leaf" {:get [:leaf2 leaf-handler]}]]]])

(defn make-app
  [options]
  (-> options
      service/default-interceptors
      service/service-fn
      ::service/service-fn))


(defn make-app-for-dir
  [dir]
  (make-app {::service/routes        request-handling-routes
             ::service/resource-path "public"
             ::service/file-path     dir}))

(deftest termination-test
  (let [text-file (File/createTempFile "request-handling-test" ".txt")
        text-url  (->> text-file
                       .getName
                       (str "http://request-handling.pedestal/"))
        app       (make-app-for-dir (.getParent text-file))
        content   "I endeavor, at all times, to be accurate."]
    (spit text-file content)
    (are [url body] (= body (->> url
                                 (response-for app :get)
                                 :body))
      "http://request-handling.pedestal/terminated/leaf" "Terminated."
      "http://request-handling.pedestal/unterminated/leaf" "Leaf handled!"
      "http://request-handling.pedestal/unrouted" "Not Found"
      "http://request-handling.pedestal/test.txt" "Text data on the classpath\n"
      text-url content)))

(def custom-not-found
  {:leave (fn [context]
            (assoc context :response (ring-response/not-found "Custom Not Found")))})

(deftest custom-not-found-test
  (let [app (make-app {::service/routes                request-handling-routes
                       ::service/not-found-interceptor custom-not-found})]
    (is (= "Custom Not Found"
           (:body (response-for app :get "http://request-handling.pedestal/unrouted"))))))

(deftest content-type-test
  (let [css-file (File/createTempFile "request-handling-test_" ".css")
        css-url  (->> css-file
                      .getName
                      (str "http://request-handling.pedestal/"))
        app      (make-app-for-dir (.getParent css-file))]
    (spit css-file "body { bold }")
    (are [url content-type] (= content-type (get (->> url
                                                      (response-for app :get)
                                                      :headers)
                                                 "Content-Type"))
      "http://request-handling.pedestal/test.html" "text/html"
      "http://request-handling.pedestal/test.js" "text/javascript"
      css-url "text/css")))
