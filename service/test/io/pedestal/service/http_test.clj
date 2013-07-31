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
            [io.pedestal.service.interceptor :as interceptor :refer [defon-response defbefore defafter]]
            [io.pedestal.service.impl.interceptor :as interceptor-impl]
            [io.pedestal.service.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.service.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp])
  (:import (java.io ByteArrayOutputStream)))

(defn about-page
  [request]
  (ring-resp/response (format "Yeah, this is a self-link to %s"
                              (io.pedestal.service.http.route/url-for :about))))

(defn hello-page
  [request] (ring-resp/response "HELLO"))

(defn hello-plaintext-page [request]
  (-> request hello-page (ring-resp/content-type "text/plain")))

(defn just-status-page
  [request] {:status 200})

(defn get-edn
  [request] (ring-resp/response {:a 1}))

(defn get-plaintext-edn
  [request]
  (-> request get-edn (ring-resp/content-type "text/plain")))

(defon-response clobberware
  [response]
  (assoc response :body
         (format "You must go to %s!"
                 (io.pedestal.service.http.route/url-for :about))))

(defbefore send-response-directly
  [ctx]
  (let [response (ring-resp/response "Responding directly")]
    (servlet-interceptor/write-response ctx (dissoc response :body))
    (servlet-interceptor/write-response-body ctx (:body response))
    (servlet-interceptor/flush-response ctx)
    ctx))

(defafter add-response-after
  [ctx]
  (assoc ctx :response (ring-resp/response "I'm responding")))

(defroutes app-routes
  [[["/about" {:get [:about about-page]}]
    ["/hello" {:get [^:interceptors [clobberware] hello-page]}]
    ["/edn" {:get get-edn}]
    ["/just-status" {:get just-status-page}]
    ["/direct-response-1" {:get [::direct-response-1 send-response-directly]}]
    ["/direct-response-2" {:get [::direct-response-2 send-response-directly]}
     ^:interceptors [add-response-after]]
    ["/text-as-html" {:get [::text-as-html hello-page]}
     ^:interceptors [service/html-body]]
    ["/plaintext-body-with-html-interceptor" {:get hello-plaintext-page}
     ^:interceptors [service/html-body]]
    ["/data-as-json" {:get [::data-as-json get-edn]}
     ^:interceptors [service/json-body]]
    ["/plaintext-body-with-json-interceptor" {:get get-plaintext-edn}
     ^:interceptors [service/json-body]]]])

(def app-interceptors
  (service/default-interceptors {::service/routes app-routes}))

(defn make-app [interceptors]
  (-> interceptors
      service/service-fn
      ::service/service-fn))

(def app (make-app app-interceptors))

(deftest html-body-test
  (let [response (response-for app :get "/text-as-html")]
    (is (= "text/html;charset=UTF-8" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-body-with-html-interceptor-test
  "Explicit request for plain-text content-type is honored by html-body interceptor."
  (let [response (response-for app :get "/plaintext-body-with-html-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest json-body-test
  (let [response (response-for app :get "/data-as-json")]
    (is (= "application/json;charset=UTF-8" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-body-with-json-interceptor-test
  "Explicit request for plain-text content-type is honored by json-body interceptor."
  (let [response (response-for app :get "/plaintext-body-with-json-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

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

(deftest response-with-only-status-works
  (is (= 200
         (->> "/just-status"
              (response-for app :get)
              :status))))

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
    (is (= (cheshire.core/generate-string obj)
           (do (io.pedestal.service.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    service/json-response
                    :body)
                output-stream)
               (.flush output-stream)
               (.close output-stream)
               (.toString output-stream "UTF-8"))))))

(deftest direct-response-test
  (is (= "Responding directly"
         (->> "/direct-response-1"
              (response-for app :get)
              :body))))

(deftest direct-response-with-later-response-test
  (let [error-signal (promise)
        patched-interceptors (concat [@#'servlet-interceptor/terminator-injector
                                      (assoc @#'servlet-interceptor/stylobate :error
                                             (fn [ctx exception]
                                               (deliver error-signal exception)
                                               ctx))
                                      @#'servlet-interceptor/ring-response]
                                     (::service/interceptors app-interceptors))
        patched-app (#'servlet-interceptor/interceptor-service-fn patched-interceptors {})]
    (is (= "Responding directly"
           (->> "/direct-response-2"
                (response-for patched-app :get)
                :body)))
    (is (= "Response already sent"
           (.getMessage @error-signal)))))
