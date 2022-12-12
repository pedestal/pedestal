;; This test file is a port of the official Ring jetty-adapter test
;; that works with Pedestal interceptors. The original version is
;; here:
;;
;; https://github.com/ring-clojure/ring/blob/master/ring-jetty-adapter/test/ring/adapter/test/jetty.clj

(ns io.pedestal.http.jetty-test
  (:use clojure.test
        io.pedestal.http.jetty)
  (:require [clj-http.client :as http]
            [clojure.edn]
            [io.pedestal.http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.helpers :refer [defhandler handler]]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.jetty :as jetty])
  (:import (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)))

(defhandler hello-world [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(defhandler hello-bytebuffer [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8"))})

(defhandler hello-bytechannel [request]
  (let [p (Pipe/open)
        b (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8"))
        sink (.sink p)]
    (.write sink b)
    (.close sink)
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (.source p)}))

(defn content-type-handler [content-type]
  (handler
    (fn [_]
      {:status  200
       :headers {"Content-Type" content-type}
       :body    ""})))

(defhandler echo-handler [request]
  {:status 200
   :headers {"request-map" (str (dissoc request
                                        :body
                                        :servlet
                                        :servlet-request
                                        :servlet-response
                                        :servlet-context
                                        :pedestal.http.impl.servlet-interceptor/protocol
                                        :pedestal.http.impl.servlet-interceptor/async-supported?))}
   :body (:body request)})

(defn jetty-server
  [app options]
  (jetty/server {:io.pedestal.http/servlet (servlet/servlet :service (servlet-interceptor/http-interceptor-service-fn [app]))}
          (assoc options :join? false)))

(defmacro with-server [app options & body]
  `(let [server# (jetty-server ~app ~options)]
     (try
       ((:start-fn server#))
       ~@body
       (finally ((:stop-fn server#))))))

;; -----------------
;; !!! CAUTION !!!
;;
;; Using this macro creates a full service which introduces many interceptors
;; into the interceptor chain.
;; This doesn't isolate the service as well as `with-server`.
;;
;; Prefer `with-server` unless you're testing something that
;; requires routes/service-map/etc.
;; -----------------------------
(defmacro with-service-server [service-map & body]
  `(let [server# (io.pedestal.http/create-server (merge {:io.pedestal.http/type :jetty
                                                         :io.pedestal.http/join? false}
                                                        ~service-map))]
     (try
       (io.pedestal.http/start server#)
       ~@body
       (finally (io.pedestal.http/stop server#)))))

(deftest test-run-jetty
  (testing "HTTP server"
    (with-server hello-world {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World")))))

  (testing "HTTPS server"
    (with-server hello-world {:port 4347
                              :container-options {:ssl-port 4348
                                                  :keystore "test/io/pedestal/http/keystore.jks"
                                                  :key-password "password"}}
      (let [response (http/get "https://localhost:4348" {:insecure? true})]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello World")))))

  (testing "HTTPS server with different options"
    (with-server hello-world {:port 4347
                              :container-options {:ssl? true
                                                  :ssl-port 4348
                                                  :keystore "test/io/pedestal/http/keystore.jks"
                                                  :key-password "password"}}
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

  (testing "configurator set to run last"
    (let [max-threads 20
          new-handler  (proxy [AbstractHandler] []
                         (handle [_ ^Request base-request request response]))
          configurator (fn [^Server server]
                         (.setAttribute server "ANewAttribute" 42)
                         (.setHandler server new-handler)
                         server)
          ^Server server (:server (jetty-server hello-world
                                                {:join? false :port 4347 :container-options {:max-threads max-threads
                                                                                             :configurator configurator}}))]
      (is (= (.getMaxThreads ^QueuedThreadPool (.getThreadPool server)) max-threads))
      (is (= (.getAttribute server "ANewAttribute") 42))
      (is (identical? new-handler (.getHandler server)))
      (is (= 1 (count (.getHandlers server))))))

  (testing "setting daemon threads"
    (testing "default (daemon off)"
      (let [server (:server (jetty-server hello-world {:port 4347 :join? false}))]
        (is (not (.. server getThreadPool isDaemon)))))
    (testing "daemon on"
      (let [server (:server (jetty-server hello-world {:port 4347 :join? false :container-options {:daemon? true}}))]
        (is (.. server getThreadPool isDaemon))))
    (testing "daemon off"
      (let [server (:server (jetty-server hello-world {:port 4347 :join? false :container-options {:daemon? false}}))]
        (is (not (.. server getThreadPool isDaemon))))))

  (testing "default character encoding"
    (with-server (content-type-handler "text/plain") {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (.contains
              ^String (get-in response [:headers "content-type"])
              "text/plain")))))

  (testing "custom content-type"
    (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (= (get-in response [:headers "content-type"])
               "text/plain;charset=UTF-16;version=1")))))

  (testing "request translation"
    (with-server echo-handler {:port 4347}
      (let [response (http/get "http://localhost:4347/foo/bar/baz?surname=jones&age=123" {:body "hello"})]
        (is (= (:status response) 200))
        (is (= (:body response) "hello"))
        (let [request-map (clojure.edn/read-string
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

  (testing "supports NIO Async via ByteBuffers"
    (with-server hello-bytebuffer {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello World")))))
  (testing "supports NIO Async via ReadableByteChannel"
    (with-server hello-bytechannel {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello World"))))))

;; Servlet Context Path tests
;; --------------------------

(def routes
  (route/expand-routes
   #{["/hello" :get `hello-world :route-name :hello]}))

(def service-map
  {:io.pedestal.http/type :jetty
   :io.pedestal.http/routes routes
   :io.pedestal.http/port 4347})

(deftest test-run-jetty-context-path
  (testing "default context-path"
    (with-service-server service-map
      (let [response (http/get "http://localhost:4347/hello")
            url-for (route/url-for-routes routes)]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World"))
        (is (= (url-for :hello) "/hello")))))
  (testing "custom context-path"
    (with-service-server (merge service-map
                                {:io.pedestal.http/container-options {:context-path "/context"}})
      (let [response (http/get "http://localhost:4347/context/hello")
            url-for (route/url-for-routes routes :context "/context")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World"))
        (is (= (url-for :hello) "/context/hello"))))))

(defn hello-page2 [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (route/url-for :hello)})

(def routes2
  (route/expand-routes
   #{["/hello" :get `hello-page2 :route-name :hello]}))

(def service-map2
  {:io.pedestal.http/type :jetty
   :io.pedestal.http/routes routes2
   :io.pedestal.http/port 4347})

(deftest test-run-jetty-custom-context-with-servletcontext
  (testing "custom context-path via servlet context"
    (with-service-server (merge service-map2
                                {:io.pedestal.http/container-options {:context-path "/context2"}})
      (let [response (http/get "http://localhost:4347/context2/hello")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "/context2/hello"))))))

