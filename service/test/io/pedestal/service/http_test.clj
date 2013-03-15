; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http-test
  (:require [clojure.test :refer :all]
            [io.pedestal.service.test :refer :all]
            [io.pedestal.service.http :as service]
            [io.pedestal.service.interceptor :as interceptor :refer [defon-response]]
            [io.pedestal.service.http.impl.servlet-interceptor]
            [io.pedestal.service.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp])
  (:import (java.io ByteArrayOutputStream)))

(defn about-page
  [request]
  (ring-resp/response (format "Yeah, this is a self-link to %s"
                              (io.pedestal.service.http.route/url-for :about))))

(defn hello-page
  [request] (ring-resp/response "HELLO"))


(defn get-edn
  [request] (ring-resp/response {:a 1}))

(defon-response clobberware
  [response]
  (assoc response :body
         (format "You must go to %s!"
                 (io.pedestal.service.http.route/url-for :about))))

(defroutes app-routes
  [[["/about" {:get [:about about-page]}]
    ["/hello" {:get [^:interceptors [clobberware] hello-page]}]
    ["/edn" {:get get-edn}]]])

(def app
  (-> {::service/routes app-routes}
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(deftest enter-linker-generates-correct-link
  (is (= "Yeah, this is a self-link to /about"
         (->> "/about"
              (response-for app :get)
              :body))))

(deftest leave-linker-generates-correct-link
  (is (= "You must go to /about!"
         (->> "/hello"
              (response-for app :get)
              :body))))

(def with-bindings*-atom
  (atom 0))

(let [original-with-bindings* with-bindings*]
  (defn with-bindings*-tracing
    [binding-map f & args]
    (swap! with-bindings*-atom inc)
    (apply original-with-bindings* binding-map f args)))

(deftest dynamic-binding-minimalism
  (with-redefs [with-bindings* with-bindings*-tracing]
    (is (= 3 (do (response-for app :get "/about")
                 @with-bindings*-atom))
        "with-bindings* is only called three times, once initially on enter, once when routing creates the linker, and once initially on leave")))

;; data response fn tests

(deftest edn-response-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (pr obj))
           (do (io.pedestal.service.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    service/edn-response
                    :body)
                output-stream)
               (.flush output-stream)
               (.close output-stream)
               (.toString output-stream "UTF-8"))))))

(deftest default-edn-output-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (pr obj))
           (do (io.pedestal.service.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    ring-resp/response
                    :body)
                output-stream)
               (.flush output-stream)
               (.close output-stream)
               (.toString output-stream "UTF-8"))))))

(deftest default-edn-response-test
  (let [edn-resp (response-for app :get "/edn")]
    (is (= "{:a 1}" (:body edn-resp))
        #_(= "application/edn"
           (headers "Content-Type")))))

(deftest json-response-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (clojure.data.json/pprint obj))
           (do (io.pedestal.service.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    service/json-response
                    :body)
                output-stream)
               (.flush output-stream)
               (.close output-stream)
               (.toString output-stream "UTF-8"))))))
