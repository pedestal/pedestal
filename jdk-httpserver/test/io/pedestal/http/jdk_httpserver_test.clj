(ns io.pedestal.http.jdk-httpserver-test
  (:refer-clojure :exclude [send])
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jdk-httpserver]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.service.protocols :as p])
  (:import (com.sun.net.httpserver HttpsConfigurator)
           (java.lang AutoCloseable)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Version HttpHeaders HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe)
           (java.security KeyStore)
           (java.security.cert X509Certificate)
           (java.time Duration)
           (java.util Optional)
           (java.util.function BiPredicate Supplier)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManager X509ExtendedTrustManager)))

(set! *warn-on-reflection* true)

(comment
  (System/setProperty "io.pedestal.http.jdk-httpserver-test.create-connector"
    (str `io.pedestal.http.jdk-httpserver/create-connector))

  (System/setProperty "io.pedestal.http.jdk-httpserver-test.create-connector"
    (str `io.pedestal.http.jetty/create-connector))

  (System/setProperty "io.pedestal.http.jdk-httpserver-test.create-connector"
    (str `io.pedestal.http.http-kit/create-connector)))

(defn create-and-start!
  ^AutoCloseable [connector-map connector-opts]
  (let [create-connector (-> "io.pedestal.http.jdk-httpserver-test.create-connector"
                           (System/getProperty "io.pedestal.http.jdk-httpserver/create-connector")
                           symbol
                           requiring-resolve
                           (or (throw (ex-info "Can't find create-connector" {}))))
        conn (-> connector-map
               (create-connector connector-opts)
               conn/start!)]
    (vary-meta (reify
                 AutoCloseable
                 (close [_] (conn/stop! conn))
                 p/PedestalConnector
                 (start-connector! [_] (p/start-connector! conn))
                 (stop-connector! [_] (p/stop-connector! conn))
                 (test-request [_ ring-request] (p/test-request conn ring-request)))
      assoc :connector-map connector-map :connector-opts connector-opts)))

(defn send
  [conn & {:keys [uri body headers request-method query-string context path-info server-name scheme server-port
                  ssl-context]
           :or   {server-name    "localhost"
                  request-method :get}}]
  (let [{:keys [connector-map connector-opts]} (meta conn)
        ^HttpsConfigurator https-configurator (:https-configurator connector-opts)
        uri (URI. (name (or scheme
                          (if https-configurator
                            :https
                            :http)))
              nil server-name
              (or (:port connector-map) server-port -1)
              (or uri
                (str context path-info)
                "/")
              query-string nil)
        method (some-> request-method name string/upper-case)
        http-headers (HttpHeaders/of (into {}
                                       (map (fn [[k v]]
                                              [k (if (string? v)
                                                   [v]
                                                   (vec v))]))
                                       headers)
                       (reify BiPredicate
                         (test [_ _ _] true)))
        ^HttpResponse http-response (with-open [http-client (.build (cond-> (HttpClient/newBuilder)
                                                                      https-configurator (.sslContext (.getSSLContext https-configurator))))]
                                      (.send http-client
                                        (proxy [HttpRequest] []
                                          (headers [] http-headers)
                                          (timeout [] (Optional/of (Duration/ofSeconds 1)))
                                          (expectContinue [] false)
                                          (version [] (Optional/of HttpClient$Version/HTTP_1_1))
                                          (bodyPublisher [] (if body
                                                              (Optional/of body)
                                                              (Optional/empty)))
                                          (uri [] uri)
                                          (method [] method))
                                        (HttpResponse$BodyHandlers/ofString)))]
    {:status  (.statusCode http-response)
     :headers (into {}
                (keep (fn [[k vs]]
                        [k (if (next vs)
                             (vec vs)
                             (first vs))]))
                (.map (.headers http-response)))
     :body    (.body http-response)}))

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
   :headers {"request-map" (str (select-keys request [:query-string :remote-addr :request-method :scheme :server-name
                                                      :server-port :ssl-client-cert :uri]))}
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
    (.timeout (Duration/ofSeconds 1))
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
  `(with-open [conn# (-> (conn/default-connector-map (:port ~opts))
                       (conn/with-interceptor (interceptor/interceptor ~handler))
                       (create-and-start! (:connector-opts ~opts)))]
     ~@bodies))

(deftest http-roundtrip
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

;; no async support for now
#_#_
(def async-route
  {:name  ::async-route
   :enter (fn [context]
            (async/go (assoc context :response {:status 204})))})
(deftest with-async-route
  (with-server async-route {:port 4347}
    (let [response (http-get "http://localhost:4347/hello/world")]
      (is (= 204 (:status response))))))

(defn echo-name
  [{:keys [json-params]}]
  {:status 200
   :body   (str "Hello, " (:name json-params) "!")})

(deftest with-body
  (with-open [conn (-> (conn/default-connector-map 4347)
                     (conn/with-default-interceptors)
                     (conn/with-routes (table/table-routes
                                         {}
                                         [["/hello" :post echo-name]]))
                     (create-and-start! {}))]
    (is (= {:status 200
            :body   "Hello, Mr. Client!"}
          (-> conn
            (send
              :uri "/hello"
              :request-method :post
              :headers {"content-type" "application/json"}
              :body (HttpRequest$BodyPublishers/ofInputStream
                      (reify Supplier
                        (get [_]
                          (io/input-stream (.getBytes (json/generate-string {:name "Mr. Client"})))))))
            (select-keys [:body :status]))))))

(deftest with-body
  (with-open [conn (-> (conn/default-connector-map 4347)
                     (conn/with-interceptors [{:name  :first
                                               :enter (fn [ctx] (assoc ctx :response {:status 200 :body "first"}))}
                                              {:name  :second
                                               :enter (fn [ctx] (assoc ctx :response {:status 200 :body "second"}))}])
                     (create-and-start! {}))]
    (is (= "first"
          (-> conn
            (send :uri "/")
            :body)))))

(defn ->ssl-context
  [keystore key-password]
  (let [pw (chars (char-array key-password))
        ks (doto (KeyStore/getInstance "PKCS12")
             (as-> % (with-open [is (io/input-stream keystore)]
                       (.load % is pw))))
        kmf (doto (KeyManagerFactory/getInstance "SunX509")
              (.init ks pw))
        #_#_tmf (doto (TrustManagerFactory/getInstance "SunX509")
                  (.init ks))]
    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf)

        ;; TODO - why can't use trust manager? - fails on client
        #_(.getTrustManagers tmf)
        (into-array TrustManager [(proxy [X509ExtendedTrustManager] []
                                    (checkClientTrusted
                                      ([_ _])
                                      ([_ _ _]))
                                    (checkServerTrusted
                                      ([_ _])
                                      ([_ _ _]))
                                    (getAcceptedIssuers []))])
        nil))))

(deftest minimal-https
  (let [ssl-context (->ssl-context
                      (io/resource "io/pedestal/http/keystore.jks")
                      "password")]
    (with-open [conn (-> (conn/default-connector-map 4347)
                       (conn/with-interceptor (fn [{:keys [ssl-client-cert]}]
                                                {:status (if (instance? X509Certificate ssl-client-cert)
                                                           204
                                                           404)}))
                       (create-and-start! {:https-configurator (HttpsConfigurator. ssl-context)}))]
      (is (= 204
            (-> conn
              (send :uri "/")
              :status))))))
