; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

;; This test file is a port of the official Ring jetty-adapter test
;; that works with Pedestal interceptors. The original version is
;; here:
;;
;; https://github.com/ring-clojure/ring/blob/master/ring-jetty-adapter/test/ring/adapter/test/jetty.clj

(ns io.pedestal.http.jetty-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clj-http.client :as http]
            [hato.client :as hc]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.service.resources :as resources]
            [io.pedestal.test-common :as tc]
            [io.pedestal.http.jetty :as jetty]
            io.pedestal.http.jetty.specs)
  (:import (jakarta.servlet DispatcherType)
           (jakarta.servlet.http HttpServletResponse)
           (java.util EnumSet)
           (org.eclipse.jetty.ee10.servlet FilterHolder ServletContextHandler)
           (org.eclipse.jetty.servlets DoSFilter)
           (org.eclipse.jetty.server.handler.gzip GzipHandler)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)))

(use-fixtures :once tc/instrument-specs-fixture)

(defn hello-world
  [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(defn hello-bytebuffer [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8"))})

(defn hello-bytechannel [_request]
  (let [p    (Pipe/open)
        b    (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8"))
        sink (.sink p)]
    (.write sink b)
    (.close sink)
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (.source p)}))

(defn content-type-handler
  [content-type]
  (fn [_request]
    {:status  200
     :headers {"Content-Type" content-type}
     :body    ""}))

(defn echo-handler
  [request]
  {:status  200
   :headers {"request-map" (str (dissoc request
                                        :body
                                        :servlet
                                        :servlet-request
                                        :servlet-response
                                        :servlet-context
                                        :pedestal.http.impl.servlet-interceptor/protocol
                                        :pedestal.http.impl.servlet-interceptor/async-supported?))}
   :body    (:body request)})

;; Was jetty/server with an io.pedestal.http.servlet/servlet running only the app; now a connector
;; whose only interceptor is the app. Jetty options go in the :container-options key of
;; create-connector's second argument (the old options map was {:port ... :container-options ...}).

(defn jetty-connector
  [app {:keys [port container-options]}]
  (jetty/create-connector (-> (conn/default-connector-map port)
                              (conn/with-interceptor (interceptor app)))
                          {:container-options container-options}))

(defn ^:private jetty-server
  "Creates a connector (not started) and returns its Jetty Server. The connector doesn't expose the
  Server, so it is captured with the :configurator container option (which runs last)."
  ^Server [app options]
  (let [*server      (atom nil)
        configurator (get-in options [:container-options :configurator] identity)]
    (jetty-connector app (assoc-in options [:container-options :configurator]
                                   (fn [server]
                                     (reset! *server (configurator server)))))
    @*server))

(defmacro with-server [app options & body]
  `(let [connector# (jetty-connector ~app ~options)]
     (try
       (conn/start! connector#)
       ~@body
       (finally (conn/stop! connector#)))))

;; -----------------
;; !!! CAUTION !!!
;;
;; Using this macro creates a full connector which introduces many interceptors
;; into the interceptor chain.
;; This doesn't isolate the connector as well as `with-server`.
;;
;; Prefer `with-server` unless you're testing something that
;; requires routes/default interceptors/etc.
;; -----------------------------
(defmacro with-connector-server [connector-map container-options & body]
  `(let [connector# (jetty/create-connector ~connector-map {:container-options ~container-options})]
     (try
       (conn/start! connector#)
       ~@body
       (finally (conn/stop! connector#)))))

(deftest http-roundrip
  (with-server hello-world {:port 4347}
               (let [response (http/get "http://localhost:4347")]
                 (is (= (:status response) 200))
                 (is (.startsWith ^String (get-in response [:headers "content-type"])
                                  "text/plain"))
                 (is (= (:body response) "Hello World")))))

(deftest https-round-trip
  (with-server hello-world {:port              4347
                            :container-options {:ssl-port        4348
                                                ;; I
                                                :sni-host-check? false
                                                :keystore        "test/io/pedestal/http/keystore.jks"
                                                :key-password    "password"}}
               (let [response (http/get "https://localhost:4348" {:insecure? true})]
                 (is (= (:status response) 200))
                 (is (= (:body response) "Hello World")))))


(deftest https-round-trip-with-ssl
  (with-server hello-world {:port              4347
                            :container-options {:ssl?            true
                                                :sni-host-check? false
                                                :ssl-port        4348
                                                :keystore        "test/io/pedestal/http/keystore.jks"
                                                :key-password    "password"}}
               (let [response (http/get "https://localhost:4348" {:insecure? true})]
                 (is (= (:status response) 200))
                 (is (= (:body response) "Hello World")))))

;; clj-http (and by proxy, Apache HttpClient only speak HTTP 1.1
;(testing "HTTP2 via HTTPS/ALPN"
;  (with-server hello-world {:port 4347
;                            :container-options {:ssl? true
;                                                :ssl-port 4348
;                                                :keystore "test/io/pedestal/http/keystore.jks"
;                                                :key-password "password"
;                                                :alpn true}}
;    (let [response (http/get "https://localhost:4348" {:insecure? true})]
;      (is (= (:status response) 200))
;      (is (= (:body response) "Hello World"))
;      (is (= nil (:headers response))))))


(deftest ensure-configurator-runs-last
  (let [max-threads    20
        new-handler    (proxy [AbstractHandler] []
                         (handle [_ ^Request base-request request response]))
        configurator   (fn [^Server server]
                         (.setAttribute server "ANewAttribute" 42)
                         (.setHandler server new-handler)
                         server)
        ^Server server (jetty-server hello-world
                                     {:port 4347 :container-options {:max-threads  max-threads
                                                                     :configurator configurator}})]
    (is (= (.getMaxThreads ^QueuedThreadPool (.getThreadPool server)) max-threads))
    (is (= (.getAttribute server "ANewAttribute") 42))
    (is (identical? new-handler (.getHandler server)))
    (is (= 1 (count (.getHandlers server))))))


(deftest setting-daemon-threads
  (testing "default (daemon off)"
    (let [server (jetty-server hello-world {:port 4347})]
      (is (not (.isDaemon ^QueuedThreadPool (.getThreadPool server))))))
  (testing "daemon on"
    (let [server (jetty-server hello-world {:port 4347 :container-options {:daemon? true}})]
      (is (.isDaemon ^QueuedThreadPool (.getThreadPool server)))))
  (testing "daemon off"
    (let [server (jetty-server hello-world {:port 4347 :container-options {:daemon? false}})]
      (is (not (.isDaemon ^QueuedThreadPool (.getThreadPool server)))))))


(deftest default-character-encoding
  (with-server (content-type-handler "text/plain") {:port 4347}
               (let [response (http/get "http://localhost:4347")]
                 (is (.contains
                       ^String (get-in response [:headers "content-type"])
                       "text/plain")))))


(deftest custom-content-type
  (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:port 4347}
               (let [response (http/get "http://localhost:4347")]
                 (is (= (get-in response [:headers "content-type"])
                        "text/plain;charset=UTF-16;version=1")))))


(deftest request-translation
  (with-server echo-handler {:port 4347}
               (let [response (http/get "http://localhost:4347/foo/bar/baz?surname=jones&age=123" {:body "hello"})]
                 (is (= (:status response) 200))
                 (is (= (:body response) "hello"))
                 (let [request-map (edn/read-string
                                     (get-in response [:headers "request-map"]))]
                   (is (= (:query-string request-map) "surname=jones&age=123"))
                   (is (= (:uri request-map) "/foo/bar/baz"))
                   ;; This are no longer part of the Ring Spec, and are removed from the base request protocol
                   ;(is (= (:content-length request-map) 5))
                   ;(is (= (:character-encoding request-map) "UTF-8"))
                   (is (= (:request-method request-map) :get))
                   ;(is (= (:content-type request-map) "text/plain; charset=UTF-8"))
                   (is (= (:remote-addr request-map) "127.0.0.1"))
                   (is (= (:scheme request-map) :http))
                   (is (= (:server-name request-map) "localhost"))
                   (is (= (:server-port request-map) 4347))
                   (is (= (:ssl-client-cert request-map) nil))))))


(deftest supports-nio-async-via-byte-buffers
  (with-server hello-bytebuffer {:port 4347}
               (let [response (http/get "http://localhost:4347")]
                 (is (= (:status response) 200))
                 (is (= (:body response) "Hello World")))))


(deftest supports-nio-async-via-readable-byte-channel
  (with-server hello-bytechannel {:port 4347}
               (let [response (http/get "http://localhost:4347")]
                 (is (= (:status response) 200))
                 (is (= (:body response) "Hello World")))))

;; Servlet Context Path tests
;; --------------------------

;; Was a syntax-quoted symbol; the Var gives the same route.
(def route-spec
  #{["/hello" :get #'hello-world :route-name :hello]})

(def routes
  (route/expand-routes route-spec))

;; Was the service map {::bootstrap/type :jetty ::bootstrap/routes routes ::bootstrap/port 4347},
;; with the default interceptors.
(defn connector-map
  []
  (-> (conn/default-connector-map 4347)
      (conn/with-default-interceptors)
      (conn/with-routes route-spec)))

(deftest test-run-jetty-context-path
  (testing "default context-path"
    (with-connector-server (connector-map) nil
                           (let [response (http/get "http://localhost:4347/hello")
                                 url-for  (route/url-for-routes routes)]
                             (is (= (:status response) 200))
                             (is (.startsWith ^String (get-in response [:headers "content-type"])
                                              "text/plain"))
                             (is (= (:body response) "Hello World"))
                             (is (= (url-for :hello) "/hello")))))
  (testing "custom context-path"
    (with-connector-server (connector-map) {:context-path "/context"}
                           (let [response (http/get "http://localhost:4347/context/hello")
                                 url-for  (route/url-for-routes routes :context "/context")]
                             (is (= (:status response) 200))
                             (is (.startsWith ^String (get-in response [:headers "content-type"])
                                              "text/plain"))
                             (is (= (:body response) "Hello World"))
                             (is (= (url-for :hello) "/context/hello"))))))

(def small-buffer-size
  (interceptor
    {:name  ::small-buffer-size
     :enter (fn [context]
              (let [^HttpServletResponse response (get-in context [:request :servlet-response])]
                (.setBufferSize response 1000))
              context)}))

(deftest access-an-async-response
  ;; Was default-interceptors, then small-buffer-size appended after the routing interceptors.
  (with-connector-server (-> (conn/default-connector-map 4347)
                             (conn/with-default-interceptors)
                             (conn/with-routes (resources/file-routes {:file-root "file-root"}))
                             (conn/with-interceptor small-buffer-size))
                         nil
                         (is (match?
                               {:status 200
                                :body   (slurp "file-root/sub/image.jpg")}
                               (http/get "http://localhost:4347/sub/image.jpg")))))

(defn hello-page2 [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (route/url-for :hello)})

(def route-spec2
  #{["/hello" :get #'hello-page2 :route-name :hello]})

(deftest test-run-jetty-custom-context-with-servletcontext
  (testing "custom context-path via servlet context"
    (with-connector-server (-> (conn/default-connector-map 4347)
                               (conn/with-default-interceptors)
                               (conn/with-routes route-spec2))
                           {:context-path "/context2"}
                           (let [response (http/get "http://localhost:4347/context2/hello")]
                             (is (= (:status response) 200))
                             (is (.startsWith ^String (get-in response [:headers "content-type"])
                                              "text/plain"))
                             (is (= (:body response) "/context2/hello"))))))

(def ^:private create-server #'jetty/create-server)

(deftest validates-that-context-path-starts-with-slash
  (is (thrown-with-msg? Exception #"\QNo HTTP or SSL port configured\E"
                        (create-server nil {:context-path "invalid"}))))

(deftest requires-ssl-port-if-h2-enabled
  (is (thrown-with-msg? Exception #"\QSSL must be enabled to use HTTP/2\E"
                        (create-server nil {:container-options {:h2? true}}))))

(deftest requires-plain-or-ssl-port
  (is (thrown-with-msg? Exception #"\QNo HTTP or SSL port configured\E"
                        (create-server nil nil))))

(deftest requires-plan-port-for-h2c
  (is (thrown-with-msg? Exception #"\QHTTP2-Cleartext can not be enabled unless a non-nil HTTP port is provided\E"
                        (create-server nil {:container-options {:h2c?     true
                                                                :ssl-port 443}}))))

(deftest supports-h2
  (with-server hello-world {:port              4347
                            :container-options {:ssl-port        4348
                                                :h2? true
                                                ;; I
                                                :sni-host-check? false
                                                :keystore        "test/io/pedestal/http/keystore.jks"
                                                :key-password    "password"}}
    ;; clj-http does not support http2 so we're using hato
    (let [response (hc/get "https://localhost:4348/" {:http-client {:ssl-context {:insecure? true}
                                                                    :version     :http-2}})]
      (is (= (:status response) 200))
      (is (= (:body response) "Hello World")))))

;; Moved from io.pedestal.http.jetty-util-test (io.pedestal.http.jetty.util was removed in Pedestal 1.0).

;; NOTE:
;;   In later versions of Jetty, GZip became a `Handler` to allow it to optimize
;;   async requests.  The Gzip ServletFilter example has been updated.
;;
;;   Servlet Filters are now tested using DoSFilter

(deftest simple-gzip-handler
  (testing "A Simple GZip Handler"
    (with-server hello-world {:port              4347
                              :container-options {:context-configurator (fn [^ServletContextHandler c]
                                                                          (let [gzip-handler (GzipHandler.)]
                                                                            (.insertHandler c gzip-handler)
                                                                            c))}}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (.startsWith ^String (:orig-content-encoding response) "gzip"))
        (is (= (:body response) "Hello World"))))))

;; jetty-util/add-servlet-filter and filter-holder were removed; as the deprecation suggests,
;; the :context-configurator uses Jetty's API directly (what those functions did).

(defn ^:private add-servlet-filter
  [^ServletContextHandler context ^FilterHolder holder]
  (.addFilter context holder "/*" (EnumSet/of DispatcherType/REQUEST))
  context)

(defn ^:private custom-dos
  ^FilterHolder []
  (doto (FilterHolder. (DoSFilter.))
    (.setInitParameter "maxRequestsPerSec" "1")             ;; Default is 25
    (.setInitParameter "insertHeaders" "true")))

(deftest simple-dos-filter
  (testing "A Simple DoS filter"
    (with-server hello-world {:port              4347
                              :container-options {:context-configurator #(add-servlet-filter % (FilterHolder. DoSFilter))}}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= "close" (get-in response [:headers "connection"])))
        (is (= (:body response) "Hello World")))))
  (testing "A FilterHolder (also DoS)"
    (with-server hello-world {:port              4347
                              :container-options {:context-configurator #(add-servlet-filter % (custom-dos))}}
      (let [response (http/get "http://localhost:4347")]
        (http/get "http://localhost:4347")
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= "close" (get-in response [:headers "connection"])))
        (is (= (:body response) "Hello World"))))))
