(ns io.pedestal.http.jdk-httpserver-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jdk-httpserver]
            [io.pedestal.interceptor :as interceptor])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(comment
  (System/setProperty "io.pedestal.http.jdk-httpserver-test.create-connector"
    (str `io.pedestal.http.jdk-httpserver/create-connector))

  (System/setProperty "io.pedestal.http.jdk-httpserver-test.create-connector"
    (str `io.pedestal.http.jetty/create-connector))

  (System/setProperty "io.pedestal.http.jdk-httpserver-test.create-connector"
    (str `io.pedestal.http.http-kit/create-connector)))

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
                                  :async-channel
                                  :io.pedestal.http.request/response-commited-ch
                                  :pedestal.http.impl.servlet-interceptor/protocol
                                  :pedestal.http.impl.servlet-interceptor/async-supported?))}
   :body    (:body request)})

(defn hello-bytebuffer [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8"))})

(defn hello-bytechannel [_request]
  (let [p (Pipe/open)
        b (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8"))
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
    (.timeout (Duration/ofMillis 100))
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
  (let [create-connector-sym (symbol (System/getProperty "io.pedestal.http.jdk-httpserver-test.create-connector" "io.pedestal.http.jdk-httpserver/create-connector"))]
    (when-not (requiring-resolve create-connector-sym)
      (throw (ex-info "Can't find create-connector"
               {:sym create-connector-sym})))
    `(let [conn#
           (-> (conn/default-connector-map (:port ~opts))
             (conn/with-interceptor (interceptor/interceptor ~handler))
             (~create-connector-sym (:connector-opts ~opts))
             conn/start!)]
       (try
         ~@bodies
         (finally
           (conn/stop! conn#))))))

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
        (is (= (:request-method request-map) :get))
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

(defn echo-paths
  [request]
  {:status  200
   :headers {"Content-Type" "application/edn"}
   :body    (prn-str (select-keys request [:uri :path-info :context]))})

(deftest with-context-path
  (with-server echo-paths {:port           4347
                           :connector-opts {:context-path "/hello"}}
    (let [response (http-get "http://localhost:4347/hello/world")]
      (is (= {:uri       "/hello/world"
              :path-info "/world"
              :context   "/hello"}
            (-> response
              :body
              edn/read-string
              #_(doto clojure.pprint/pprint)))))))
