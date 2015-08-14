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

(ns io.pedestal.http-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as service]
            [io.pedestal.interceptor.helpers :as interceptor :refer [defon-response defbefore defafter]]
            [io.pedestal.impl.interceptor :as interceptor-impl]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp])
  (:import (java.io ByteArrayOutputStream FileInputStream File)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)))

(defn about-page
  [request]
  (ring-resp/response (format "Yeah, this is a self-link to %s"
                              (io.pedestal.http.route/url-for :about))))

(defn hello-page
  [request] (ring-resp/response "HELLO"))

(defn hello-token-page
  [request] (ring-resp/response (str "HELLO" (:io.pedestal.http.csrf/anti-forgery-token request))))

(defn hello-plaintext-page [request]
  (-> request hello-page (ring-resp/content-type "text/plain")))

(defn hello-byte-buffer-page [request]
  (assoc-in (ring-resp/response (ByteBuffer/wrap (.getBytes "HELLO" "UTF-8")))
            [:headers "Content-Type"]
            "text/plain"))

(defn hello-byte-channel-page [request]
  (let [p (Pipe/open)
        b (ByteBuffer/wrap (.getBytes "HELLO" "UTF-8"))
        sink (.sink p)]
    (.write sink b)
    (.close sink)
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (.source p)}))

(defn hello-plaintext-no-content-type-page [request]
  (hello-page request))

(def ^:dynamic *req* {})

(defn with-binding-page [request]
  (ring-resp/response (str ":req was bound to " *req*)))

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
                 (io.pedestal.http.route/url-for :about))))

(defbefore add-binding
  [context]
  (update-in context [:bindings] #(assoc % #'*req* {:a 1})))

(defroutes app-routes
  [[["/about" {:get [:about about-page]}]
    ["/hello" {:get [^:interceptors [clobberware] hello-page]}]
    ["/token" {:get hello-token-page}]
    ["/bytebuffer" {:get hello-byte-buffer-page}]
    ["/bytechannel" {:get hello-byte-channel-page}]
    ["/edn" {:get get-edn}]
    ["/just-status" {:get just-status-page}]
    ["/with-binding" {:get [^:interceptors [add-binding] with-binding-page]}]
    ["/text-as-html" {:get [::text-as-html hello-page]}
     ^:interceptors [service/html-body]]
    ["/plaintext-body-with-html-interceptor" {:get hello-plaintext-page}
     ^:interceptors [service/html-body]]
    ["/plaintext-body-no-interceptors" {:get hello-plaintext-no-content-type-page}]
    ["/data-as-json" {:get [::data-as-json get-edn]}
     ^:interceptors [service/json-body]]
    ["/plaintext-body-with-json-interceptor" {:get get-plaintext-edn}
     ^:interceptors [service/json-body]]
    ["/data-as-transit" {:get [::data-as-transit get-edn]}
     ^:interceptors [service/transit-body]]
    ["/plaintext-body-with-transit-interceptor" {:get [::plaintext-from-transit get-plaintext-edn]}
     ^:interceptors [service/transit-body]]]])

(def app-interceptors
  (service/default-interceptors {::service/routes app-routes}))

(defn make-app [interceptors]
  (-> interceptors
      service/service-fn
      ::service/service-fn))

(def app (make-app app-interceptors))

(deftest html-body-test
  (let [response (response-for app :get "/text-as-html")]
    (is (= "text/html;charset=UTF-8" (get-in response [:headers "Content-Type"])))
    ;; Ensure secure headers by default
    (is (= {"Content-Type" "text/html;charset=UTF-8"
            "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
            "X-Frame-Options" "DENY"
            "X-Content-Type-Options" "nosniff"
            "X-XSS-Protection" "1; mode=block"} (:headers response)))))

(deftest plaintext-body-with-html-interceptor-test
  "Explicit request for plain-text content-type is honored by html-body interceptor."
  (let [response (response-for app :get "/plaintext-body-with-html-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-body-with-no-interceptors-test
  "Requests without a content type are served as text/plain"
  (let [response (response-for app :get "/plaintext-body-no-interceptors")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-from-byte-buffer
  "Response bodies that are ByteBuffers toggle async behavior in supported containers"
  (let [response (response-for app :get "/bytebuffer")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))
    (is (= "HELLO" (:body response)))))

(deftest plaintext-from-byte-channel
  "Response bodies that are ReadableByteChannels toggle async behavior in supported containers"
  (let [response (response-for app :get "/bytechannel")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))
    (is (= "HELLO" (:body response)))))

(deftest json-body-test
  (let [response (response-for app :get "/data-as-json")]
    (is (= "application/json;charset=UTF-8" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-body-with-json-interceptor-test
  "Explicit request for plain-text content-type is honored by json-body interceptor."
  (let [response (response-for app :get "/plaintext-body-with-json-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest transit-body-test
  (let [response (response-for app :get "/data-as-transit")]
    (is (= "application/transit+json;charset=UTF-8" (get-in response [:headers "Content-Type"])))
    (is (= "[\"^ \",\"~:a\",1]" (:body response)))))

(deftest plaintext-body-with-transit-interceptor-test
  "Explicit request for plain-text content-type is honored by transit-body interceptor."
  (let [response (response-for app :get "/plaintext-body-with-transit-interceptor")]
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

(deftest adding-a-binding-to-context-appears-in-user-request
  (is (= ":req was bound to {:a 1}"
         (->> "/with-binding"
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

(defn- slurp-output-stream [output-stream]
  (.flush output-stream)
  (.close output-stream)
  (.toString output-stream "UTF-8"))

(deftest edn-response-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (pr obj))
           (do (io.pedestal.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    service/edn-response
                    :body)
                output-stream)
               (slurp-output-stream output-stream))))))

(deftest transit-response-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (pr obj))
           (do (io.pedestal.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    service/edn-response
                    :body)
                output-stream)
               (slurp-output-stream output-stream))))))

(deftest default-edn-output-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (pr obj))
           (do (io.pedestal.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    ring-resp/response
                    :body)
                output-stream)
               (slurp-output-stream output-stream))))))

(deftest default-edn-response-test
  (let [edn-resp (response-for app :get "/edn")]
    (is (= "{:a 1}" (:body edn-resp))
        #_(= "application/edn"
           (headers "Content-Type")))))

(deftest json-response-test
  (let [obj {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (cheshire.core/generate-string obj)
           (do (io.pedestal.http.impl.servlet-interceptor/write-body-to-stream
                (-> obj
                    service/json-response
                    :body)
                output-stream)
               (slurp-output-stream output-stream))))))

(defn- create-temp-file [content]
  (let [f (File/createTempFile "pedestal-" nil)]
    (.deleteOnExit f)
    (spit f content)
    f))

(deftest input-stream-body-test
  (let [body-content "Hello Input Stream"
        body-stream (-> body-content
                        create-temp-file
                        FileInputStream.)
        output-stream (ByteArrayOutputStream.)]
    (is (= body-content
           (do (io.pedestal.http.impl.servlet-interceptor/write-body-to-stream body-stream output-stream)
               (slurp-output-stream output-stream))))
    (is (thrown? java.io.IOException
                 ;; This is JVM implementation specific;
                 ;;   If it breaks down, we'll needed a decorated body-stream
                 (.available body-stream)))))

(deftest file-body-test
  (let [body-content "Hello File"
        body-file (create-temp-file body-content)
        output-stream (ByteArrayOutputStream.)]
    (is (= body-content
           (do (io.pedestal.http.impl.servlet-interceptor/write-body-to-stream body-file output-stream)
               (slurp-output-stream output-stream))))))

(def anti-forgery-app-interceptors
  (service/default-interceptors {::service/routes app-routes
                                 ::service/enable-csrf {}}))

(def af-app (make-app anti-forgery-app-interceptors))

(deftest html-body-test-csrf-token
  (let [response (response-for af-app :get "/token")
        body (:body response)]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))
    (is (> (count body) 5))
    (is (= "HELLO" (subs body 0 5)))
    (is (not-empty (subs body 4)))))

;;; Async request handlers

(defbefore hello-async [context]
  (let [ch (async/chan 1)]
    (async/go
      (async/>! ch (assoc context
                    :response
                    {:status 200
                     :body "Hello async"})))
    ch))

(defroutes async-hello-routes
  [[["/hello-async" {:get hello-async}]]])

(deftest channel-returning-request-handler
  (let [app      (-> {::service/routes async-hello-routes}
                   service/default-interceptors
                   service/service-fn
                   ::service/service-fn)
        response (response-for app :get "/hello-async")]
    (is (= "Hello async" (:body response)))))
