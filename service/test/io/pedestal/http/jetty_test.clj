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
            [io.pedestal.interceptor :as interceptor :refer [defhandler definterceptorfn handler]]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor])
  (:import (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)))

(defhandler hello-world [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(definterceptorfn content-type-handler [content-type]
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
  (server (servlet/servlet :service (servlet-interceptor/http-interceptor-service-fn [app]))
          (assoc options :join? false)))

(defmacro with-server [app options & body]
  `(let [server# (jetty-server ~app ~options)]
     (try
       ((:start-fn server#))
       ~@body
       (finally ((:stop-fn server#))))))

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
                              :jetty-options {:ssl-port 4348
                                              :keystore "test/io/pedestal/http/keystore.jks"
                                              :key-password "password"}}
      (let [response (http/get "https://localhost:4348" {:insecure? true})]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello World")))))

  (testing "HTTPS server with different options"
    (with-server hello-world {:port 4347
                              :jetty-options {:ssl? true
                                              :ssl-port 4348
                                              :keystore "test/io/pedestal/http/keystore.jks"
                                              :key-password "password"}}
      (let [response (http/get "https://localhost:4348" {:insecure? true})]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello World")))))

  (testing "configurator set to run last"
    (let [max-threads 20
          new-handler  (proxy [AbstractHandler] []
                         (handle [_ ^Request base-request request response]))
          configurator (fn [^Server server]
                         (.setAttribute server "ANewAttribute" 42)
                         (.setHandler server new-handler)
                         server)
          ^Server server (:server (jetty-server hello-world
                                                {:join? false :port 4347 :jetty-options {:max-threads max-threads
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
      (let [server (:server (jetty-server hello-world {:port 4347 :join? false :jetty-options {:daemon? true}}))]
        (is (.. server getThreadPool isDaemon))))
    (testing "daemon off"
      (let [server (:server (jetty-server hello-world {:port 4347 :join? false :jetty-options {:daemon? false}}))]
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
          (is (= (:content-length request-map) 5))
          (is (= (:character-encoding request-map) "UTF-8"))
          (is (= (:request-method request-map) :get))
          (is (= (:content-type request-map) "text/plain; charset=UTF-8"))
          (is (= (:remote-addr request-map) "127.0.0.1"))
          (is (= (:scheme request-map) :http))
          (is (= (:server-name request-map) "localhost"))
          (is (= (:server-port request-map) 4347))
          (is (= (:ssl-client-cert request-map) nil)))))))

