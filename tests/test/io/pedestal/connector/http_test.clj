; Copyright 2024-2026 Nubank NA
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

(ns io.pedestal.connector.http-test
  "HTTP behavior of an application built from a connector map.

  Interceptor behavior is tested with io.pedestal.connector.test/response-for on a Jetty connector;
  servlet behavior (NIO bodies, async completion, body write failures) through the Servlet API,
  with io.pedestal.servlet-support (a ConnectorServlet and the mock servlet objects)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :as async]
            [io.pedestal.connector :as conn]
            [io.pedestal.connector.test :as test]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.servlet-support :as servlet-support]
            [io.pedestal.test-common :as tc]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.impl.servlet-interceptor :as si]
            [io.pedestal.http.response :as response]
            [io.pedestal.http.route :as route]
            [io.pedestal.service.interceptors :as interceptors]
            [charred.api :as json]
            [ring.util.response :as ring-resp])
  (:import (java.io ByteArrayOutputStream File FileInputStream InputStream IOException OutputStream)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)))

(use-fixtures :once tc/instrument-specs-fixture)

(defn about-page
  [_request]
  (ring-resp/response (format "Yeah, this is a self-link to %s"
                              (route/url-for :about))))

(defn hello-page
  [_request]
  (ring-resp/response "HELLO"))

(defn hello-token-page
  [request]
  (ring-resp/response (str "HELLO" (:io.pedestal.http.csrf/anti-forgery-token request))))

(defn hello-plaintext-page [request]
  (-> request hello-page (ring-resp/content-type "text/plain")))

(defn hello-byte-buffer-page [_request]
  (assoc-in (ring-resp/response (ByteBuffer/wrap (.getBytes "HELLO" "UTF-8")))
            [:headers "Content-Type"]
            "text/plain"))

(defn hello-byte-channel-page
  [_request]
  (let [p    (Pipe/open)
        b    (ByteBuffer/wrap (.getBytes "HELLO" "UTF-8"))
        sink (.sink p)]
    (.write sink b)
    (.close sink)
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (.source p)}))

(defn hello-plaintext-no-content-type-page
  [request]
  (hello-page request))

(defn hello-async-chunked-page
  "An asynchronous handler whose body is written in several chunks, flushing the
  stream between them (as streaming writers, such as transit, do)."
  [_request]
  (async/go
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (fn [^OutputStream output-stream]
                (doseq [chunk ["HE" "LL" "O"]]
                  (.write output-stream (.getBytes ^String chunk "UTF-8"))
                  (.flush output-stream)
                  ;; Give the test thread a chance to observe a partial body
                  (Thread/sleep 20)))}))

(defn failing-body-page
  "A synchronous handler whose body fails while being written to the response."
  [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (proxy [InputStream] []
              (read
                ([] (throw (IOException. "Stream closed")))
                ([_b] (throw (IOException. "Stream closed")))
                ([_b _off _len] (throw (IOException. "Stream closed")))))})

(defn transit-params
  [{:keys [transit-params] :as _request}]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (pr-str transit-params)})

(def ^:dynamic *req* {})

(defn with-binding-page
  [_request]
  (ring-resp/response (str ":req was bound to " *req*)))

(defn just-status-page
  [_request]
  {:status 200})

(defn get-edn
  [_request]
  (ring-resp/response {:a 1}))

(defn get-plaintext-edn
  [request]
  (-> request get-edn (ring-resp/content-type "text/plain")))

(def clobberware
  {:leave #(update % :response assoc :body
                   (format "You must go to %s!"
                           (route/url-for :about)))})

(def add-binding
  {:enter #(chain/bind % *req* {:a 1})})

;; The handlers are Vars: a route without an explicit route name takes it from its Var.
;; The /transit-params route needs no body-params interceptor: with-default-interceptors includes it.
(def app-routes
  [[["/about" {:get [:about #'about-page]}]
    ["/hello" {:get [^:interceptors [clobberware] #'hello-page]}]
    ["/token" {:get #'hello-token-page}]
    ["/bytebuffer" {:get #'hello-byte-buffer-page}]
    ["/bytechannel" {:get #'hello-byte-channel-page}]
    ["/async-chunked" {:get #'hello-async-chunked-page}]
    ["/failing-body" {:get #'failing-body-page}]
    ["/edn" {:get #'get-edn}]
    ["/just-status" {:get #'just-status-page}]
    ["/with-binding" {:get [^:interceptors [add-binding] #'with-binding-page]}]
    ["/text-as-html" {:get [::text-as-html #'hello-page]}
     ^:interceptors [interceptors/html-body]]
    ["/plaintext-body-with-html-interceptor" {:get #'hello-plaintext-page}
     ^:interceptors [interceptors/html-body]]
    ["/plaintext-body-no-interceptors" {:get #'hello-plaintext-no-content-type-page}]
    ["/data-as-json" {:get [::data-as-json #'get-edn]}
     ^:interceptors [interceptors/json-body]]
    ["/plaintext-body-with-json-interceptor" {:get #'get-plaintext-edn}
     ^:interceptors [interceptors/json-body]]
    ["/data-as-transit" {:get [::data-as-transit #'get-edn]}
     ^:interceptors [interceptors/transit-json-body]]
    ["/transit-params" {:post #'transit-params}]
    ["/plaintext-body-with-transit-interceptor" {:get [::plaintext-from-transit #'get-plaintext-edn]}
     ^:interceptors [interceptors/transit-json-body]]]])

;; Built when the namespace is loaded, before the specs are instrumented:
;; the "/hello" and "/with-binding" routes put ^:interceptors before the handler without a route name,
;; which the terse expander accepts but the :io.pedestal.http.route.specs/terse-routes spec rejects.
(def app-connector-map
  (-> (conn/default-connector-map -1)
      (conn/with-default-interceptors)
      (conn/with-routes app-routes)))

(defn app
  "A Jetty connector (not started), for io.pedestal.connector.test/response-for."
  []
  (jetty/create-connector app-connector-map nil))

(defn response-for
  [connector verb url & {:as options}]
  (test/response-for connector verb url options))

(defn servlet-response-for
  "Through the Servlet API: a ConnectorServlet with the same connector map."
  [verb url & {:as options}]
  (servlet-support/response-for (servlet-support/create-servlet app-connector-map) verb url options))

(deftest html-body-test
  (let [response (response-for (app) :get "/text-as-html")]
    (is (= "text/html;charset=UTF-8" (get-in response [:headers "Content-Type"])))
    ;; Ensure secure headers by default
    (is (= {"Content-Type"                      "text/html;charset=UTF-8"
            "Strict-Transport-Security"         "max-age=31536000; includeSubdomains"
            "X-Frame-Options"                   "DENY"
            "X-Content-Type-Options"            "nosniff"
            "X-XSS-Protection"                  "1; mode=block"
            "X-Download-Options"                "noopen"
            "X-Permitted-Cross-Domain-Policies" "none"
            "Content-Security-Policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"}
           (:headers response)))))

(deftest plaintext-body-with-html-interceptor-test
  ;; Explicit request for plain-text content-type is honored by html-body interceptor.
  (let [response (response-for (app) :get "/plaintext-body-with-html-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-body-with-no-interceptors-test
  ;; Requests without a content type are served as text/plain
  (let [response (response-for (app) :get "/plaintext-body-no-interceptors")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest plaintext-from-byte-buffer
  ;; Response bodies that are ByteBuffers toggle async behavior in supported containers
  (let [response (servlet-response-for :get "/bytebuffer")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))
    (is (= "HELLO" (:body response)))))

(deftest plaintext-from-byte-channel
  ;; Response bodies that are ReadableByteChannels toggle async behavior in supported containers
  (let [response (servlet-response-for :get "/bytechannel")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))
    (is (= "HELLO" (:body response)))))

(deftest async-response-is-complete-only-when-the-async-context-completes
  ;; The chain goes async at the handler, so the servlet returns before the body is written;
  ;; the response must only be returned once the AsyncContext is completed, not on the first
  ;; flush of the output stream.
  (let [response (servlet-response-for :get "/async-chunked")]
    (is (= 200 (:status response)))
    (is (= "HELLO" (:body response)))))

(deftest sync-response-is-complete-when-the-servlet-returns
  ;; The body write fails before anything is flushed; a synchronous response is complete
  ;; once the servlet returns, so this must not wait for a completion that never comes.
  ;; Since nothing was sent, the response is replaced by a 500.
  (let [response (servlet-response-for :get "/failing-body" :timeout 1000)]
    (is (= 500 (:status response)))
    (is (= "Internal server error: exception" (:body response)))))

(deftest json-body-test
  (let [response (response-for (app) :get "/data-as-json")]
    (is (match?
          {:headers {"Content-Type" "application/json;charset=UTF-8"}
           :body    "{\"a\":1}"}
          response))))

(deftest plaintext-body-with-json-interceptor-test
  ;; Explicit request for plain-text content-type is honored by json-body interceptor.
  (let [response (response-for (app) :get "/plaintext-body-with-json-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest transit-response-body-test
  (let [response (response-for (app) :get "/data-as-transit")]
    (is (= "application/transit+json;charset=UTF-8" (get-in response [:headers "Content-Type"])))
    (is (= "[\"^ \",\"~:a\",1]" (:body response)))))

(deftest transit-request-body-test
  ;; io.pedestal.connector.test/response-for does not lower-case request header names (a servlet
  ;; container does), and only "content-type" sets the request's :content-type.
  (let [response (response-for (app) :post "/transit-params"
                               :headers {"content-type" "application/transit+json"}
                               :body "[\"^ \",\"~:a\",1]")]
    (is (match? {:status 200
                 :body   "{:a 1}"}
                response))))

(deftest plaintext-body-with-transit-interceptor-test
  ;; Explicit request for plain-text content-type is honored by transit-body interceptor.
  (let [response (response-for (app) :get "/plaintext-body-with-transit-interceptor")]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

(deftest enter-linker-generates-correct-link
  (is (= "Yeah, this is a self-link to /about"
         (->> "/about"
              (response-for (app) :get)
              :body))))

(deftest leave-linker-generates-correct-link
  (is (= "You must go to /about!"
         (->> "/hello"
              (response-for (app) :get)
              :body))))

(deftest response-with-only-status-works
  (is (= 200
         (->> "/just-status"
              (response-for (app) :get)
              :status))))

(deftest response-with-bad-query
  (let [resp (response-for (app) :get "/just-status?q=%")]
    (is (= 400 (:status resp)))
    (is (= "Bad Request - URLDecoder: Incomplete trailing escape (%) pattern"
           (:body resp)))))

(deftest default-edn-response-test
  ;; A map body without a content type is written as EDN.
  (let [edn-resp (response-for (app) :get "/edn")]
    (is (= "{:a 1}" (:body edn-resp)))
    (is (= "application/edn" (get-in edn-resp [:headers "Content-Type"])))))

(deftest adding-a-binding-to-context-appears-in-user-request
  (is (= ":req was bound to {:a 1}"
         (->> "/with-binding"
              (response-for (app) :get)
              :body))))

;; data response fn tests

(defn- slurp-output-stream [^ByteArrayOutputStream output-stream]
  (.flush output-stream)
  (.close output-stream)
  (.toString output-stream "UTF-8"))

(deftest default-edn-output-test
  (let [obj           {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (is (= (with-out-str (pr obj))
           (do (si/write-body-to-stream
                 (-> obj
                     ring-resp/response
                     :body)
                 output-stream)
               (slurp-output-stream output-stream))))))

(deftest json-response-test
  (let [obj           {:a 1 :b 2 :c [1 2 3]}
        output-stream (ByteArrayOutputStream.)]
    (si/write-body-to-stream
      (-> obj
          response/json-response
          :body)
      output-stream)
    (is (= (json/write-json-str obj)
           (slurp-output-stream output-stream)))))

(defn- create-temp-file [content]
  (let [f (File/createTempFile "pedestal-" nil)]
    (.deleteOnExit f)
    (spit f content)
    f))

(deftest input-stream-body-test
  (let [body-content  "Hello Input Stream"
        body-stream   (-> body-content
                          ^File create-temp-file
                          FileInputStream.)
        output-stream (ByteArrayOutputStream.)]
    (is (= body-content
           (do (si/write-body-to-stream body-stream output-stream)
               (slurp-output-stream output-stream))))
    (is (thrown? IOException
                 ;; This is JVM implementation specific;
                 ;;   If it breaks down, we'll need a decorated body-stream
                 (.available body-stream)))))

(deftest file-body-test
  (let [body-content  "Hello File"
        body-file     (create-temp-file body-content)
        output-stream (ByteArrayOutputStream.)]
    (is (= body-content
           (do (si/write-body-to-stream body-file output-stream)
               (slurp-output-stream output-stream))))))

(def anti-forgery-app-connector-map
  ;; Sessions, body params, then anti-forgery: with-default-interceptors has the session and body-params
  ;; interceptors; anti-forgery is added after them.
  (-> (conn/default-connector-map -1)
      (conn/with-default-interceptors :session-options {})
      (conn/with-interceptor (csrf/anti-forgery {}))
      (conn/with-routes app-routes)))

(defn af-app
  []
  (jetty/create-connector anti-forgery-app-connector-map nil))

(deftest html-body-test-csrf-token
  (let [response (response-for (af-app) :get "/token")
        body     (:body response)]
    (is (= "text/plain" (get-in response [:headers "Content-Type"])))
    (is (> (count body) 5))
    (is (= "HELLO" (subs body 0 5)))
    (is (not-empty (subs body 4)))))

;;; Routes

(def hello-async
  {:enter (fn [context]
            (async/go
              (assoc context
                     :response
                     {:status 200
                      :body   "Hello async"})))})

(def async-hello-routes
  [[["/hello-async" {:get #'hello-async}]]])

(deftest can-provide-expanded-routes
  ;; with-routes doesn't accept a routing table (the result of expand-routes): it calls expand-routes
  ;; again, and a RoutingTable isn't ExpandableRoutes. So the routing table is passed to route/router,
  ;; as with-routes does.
  (let [connector (-> (conn/default-connector-map -1)
                      (conn/with-default-interceptors)
                      (conn/with-interceptors [(route/router (route/expand-routes async-hello-routes) :sawtooth)
                                               route/path-params-decoder])
                      (jetty/create-connector nil))
        response  (response-for connector :get "/hello-async")]
    (is (= "Hello async" (:body response)))))
