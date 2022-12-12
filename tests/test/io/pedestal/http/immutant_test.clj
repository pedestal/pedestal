(ns io.pedestal.http.immutant-test
  (:use clojure.test
        io.pedestal.http.immutant)
  (:require [clj-http.client :as http]
            [clojure.edn]
            [io.pedestal.interceptor.helpers :refer [defhandler handler]]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor])
  (:import (java.nio ByteBuffer)
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

(defn immutant-server
  [app options]
  (server {:io.pedestal.http/servlet (servlet/servlet :service (servlet-interceptor/http-interceptor-service-fn [app]))}
          (assoc options :join? false)))

(defmacro with-server [app options & body]
  `(let [server# (immutant-server ~app ~options)]
     (try
       ((:start-fn server#))
       ~@body
       (finally ((:stop-fn server#))))))

(deftest test-run-immutant
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

  (testing "default character encoding"
    (with-server (content-type-handler "text/plain") {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (.contains
             ^String (get-in response [:headers "content-type"])
             "text/plain")))))

  (testing "custom content-type"
    (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:port 4347}
      (let [response (http/get "http://localhost:4347")]
        (is (= (into #{} (.split (get-in response [:headers "content-type"]) ";"))
              #{"charset=UTF-16" "version=1" "text/plain"})))))

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

