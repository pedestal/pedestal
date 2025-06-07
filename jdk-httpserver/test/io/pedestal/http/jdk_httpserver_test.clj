(ns io.pedestal.http.jdk-httpserver-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http :as http]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jdk-httpserver :as jdk-httpserver]
            [io.pedestal.interceptor :as interceptor])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)))

(set! *warn-on-reflection* true)

#_io.pedestal.http.jetty-test
(deftest greet-handler
  (with-open [conn (-> (conn/default-connector-map 8080)
                     (conn/with-default-interceptors)
                     (conn/with-routes
                       #{["/greet" :get (fn [req]
                                          {:status 200
                                           :body   "Hello, world!"})
                          :route-name :greet-handler]})
                     jdk-httpserver/create-connector
                     conn/start!)
              http-client (HttpClient/newHttpClient)]
    (is (= "Hello, world!"
          (-> http-client
            (.send (-> "http://localhost:8080/greet"
                     URI/create
                     HttpRequest/newBuilder
                     (.timeout (Duration/ofSeconds 1))
                     .build)
              (HttpResponse$BodyHandlers/ofString))
            .body)))))


;; TODO: Moveto connecotr
(defn hello-world
  [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

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

(defn http-get
  [uri & {:keys [body]}]
  (-> uri
    URI/create
    HttpRequest/newBuilder
    (cond-> body (.method "GET" (HttpRequest$BodyPublishers/ofString body)))
    .build
    (as-> % (.send (HttpClient/newHttpClient) % (HttpResponse$BodyHandlers/ofString)))
    (as-> % {:status  (.statusCode %)
             :body    (.body %)
             :headers (into {}
                        (map (fn [[k vs]]
                               [k (if (next vs)
                                    (vec vs)
                                    (first vs))]))
                        (.map (.headers %)))})))

(defmacro with-server
  [handler opts & bodies]
  `(let [service-map# (-> {::http/join?        false
                           ::http/interceptors [(interceptor/interceptor ~handler)]
                           ::http/type         :jdk-httpserver
                           ::http/port         (:port ~opts)}
                        http/default-interceptors
                        http/create-server
                        http/start)]
     (try
       ~@bodies
       (finally
         (http/stop service-map#)))))

(deftest http-roundrip
  (with-server hello-world {:port 4347}
    (let [response (http-get "http://localhost:4347")]
      (is (= (:status response) 200))
      (is (.startsWith ^String (get-in response [:headers "content-type"])
            "text/plain"))
      (is (= (:body response) "Hello World")))))

(deftest default-character-encoding
  (with-server (content-type-handler "text/plain") {:port 4347}
    (let [response (http-get "http://localhost:4347")]
      (is (.contains
            ^String (get-in response [:headers "content-type"])
            "text/plain")))))

(deftest custom-content-type
  (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:port 4347}
    (let [response (http-get "http://localhost:4347")]
      (is (= (get-in response [:headers "content-type"])
            "text/plain;charset=UTF-16;version=1")))))

(deftest request-translation
  (with-server echo-handler {:port 4347}
    (let [response (http-get "http://localhost:4347/foo/bar/baz?surname=jones&age=123" {:body "hello"})]
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
    (let [response (http-get "http://localhost:4347")]
      (is (= (:status response) 200))
      (is (= (:body response) "Hello World")))))


(deftest supports-nio-async-via-readable-byte-channel
  (with-server hello-bytechannel {:port 4347}
    (let [response (http-get "http://localhost:4347")]
      (is (= (:status response) 200))
      (is (= (:body response) "Hello World")))))