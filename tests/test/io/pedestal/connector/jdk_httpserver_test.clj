(ns io.pedestal.connector.jdk-httpserver-test
  (:require [charred.api :as json]
            [clj-http.client :as http]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.connector :as connector]
            [io.pedestal.connector.jdk-httpserver :as jdk-httpserver]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.response :as response]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.test-common :as tc]
            [matcher-combinators.matchers :as m]
            [org.httpkit.client :as client]
            [ring.util.response :refer [response]])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.security.cert X509Certificate)))

(defn hello-page
  [_request]
  (response "HELLO"))

(def async-hello
  (interceptor
    {:name  ::async-hello
     :enter (fn [context]
              (async/go
                (response/respond-with context 200 "ASYNC HELLO")))}))

(defn echo-name
  [request]
  (let [{:keys [json-params]} request]
    {:status 200
     :body   (str "Hello, " (:name json-params) "!")}))

(defn echo-header
  [request]
  {:status 200
   :body   (get-in request [:headers "x-test"] "(missing)")})

(def routes
  (table/table-routes
    {}
    [["/hello" :get hello-page :route-name ::hello]
     ["/hello" :post echo-name]
     ["/async/hello" :get async-hello]
     ["/echo-header" :get echo-header :route-name ::echo-header]]))

(def port 38348)

(def base-url (str "http://localhost:" port))

(defn get!
  ([path]
   (get! path nil))
  ([path opts]
   @(client/get (str base-url path)
      (merge {:as :stream} opts))))

(defn new-connector
  []
  (-> (connector/default-connector-map port)
    (connector/with-default-interceptors)
    (connector/with-routes routes)
    (jdk-httpserver/create-connector nil)))

(use-fixtures :once
  tc/instrument-specs-fixture
  (fn [f]
    (let [conn (new-connector)]
      (try
        (connector/start! conn)
        (f)
        (finally
          (connector/stop! conn))))))

(deftest basic-access
  (is (match? {:status  200
               :headers {:content-type "text/plain"}
               :body    (m/via slurp "HELLO")}
        (get! "/hello"))))


(deftest async-request-handling
  (is (match? {:status  200
               :headers {:content-type "text/plain"}
               :body    (m/via slurp "ASYNC HELLO")}
        (get! "/async/hello"))))

(deftest with-body
  (is (match?
        {:status 200
         :body   (m/via slurp "Hello, Mr. Client!")}
        @(client/post (str base-url "/hello")
           {:as      :stream
            :headers {"content-type" "application/json"}
            :body    (json/write-json-str {:name "Mr. Client"})}))))

(deftest duplicate-request-headers-joined-with-comma
  (let [java-client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                  (.uri (URI/create (str base-url "/echo-header")))
                  (.header "X-Test" "value-a")
                  (.header "X-Test" "value-b")
                  (.GET)
                  (.build))
        response (.send java-client request (HttpResponse$BodyHandlers/ofString))]
    (is (= 200 (.statusCode response)))
    (is (= "value-a,value-b" (.body response)))))

(deftest includes-essential-security-headers
  (is (match? {:status  200
               :headers {:strict-transport-security         "max-age=31536000; includeSubdomains"
                         :x-frame-options                   "DENY"
                         :x-content-type-options            "nosniff"
                         :x-xss-protection                  "1; mode=block"
                         :x-download-options                "noopen"
                         :x-permitted-cross-domain-policies "none"
                         :content-security-policy           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"}}
        (get! "/hello"))))

(deftest context-path
  (let [*captures (atom [])
        conn (-> 1337
               connector/default-connector-map
               (connector/with-interceptor {:name  ::capture
                                            :enter (fn [{:keys [request]
                                                         :as   ctx}]
                                                     (swap! *captures conj (select-keys request [:uri :path-info :context]))
                                                     ctx)})
               (connector/with-default-interceptors)
               (connector/with-routes #{["/hello/:name" :get (fn [{:keys [path-params]}]
                                                               {:body   (str "Hello " (:name path-params) "!")
                                                                :status 200})
                                         :route-name :my-route]})
               (jdk-httpserver/create-connector {:context-path "/my-custom-path"}))]
    (is (= "Hello response-for!"
          (-> conn
            (test/response-for :get "/my-custom-path/hello/response-for")
            :body)))
    (try
      (connector/start! conn)
      (is (= "Hello real-server!"
            (with-open [http-client (HttpClient/newHttpClient)]
              (-> "http://0:1337/my-custom-path/hello/real-server"
                URI/create
                HttpRequest/newBuilder
                .build
                (as-> % (.send http-client % (HttpResponse$BodyHandlers/ofString)))
                .body))))

      (finally
        (connector/stop! conn)))
    (is (= [{:context   "/my-custom-path"
             :path-info "/hello/response-for"
             :uri       "/my-custom-path/hello/response-for"}
            {:context   "/my-custom-path"
             :path-info "/hello/real-server"
             :uri       "/my-custom-path/hello/real-server"}]
          @*captures))))

(deftest https-round-trip-with-ssl
  (let [*requests (atom [])
        conn (-> 1337
               connector/default-connector-map
               (connector/with-interceptor {:name  ::respond
                                            :enter (fn [{:keys [request]
                                                         :as   ctx}]
                                                     (swap! *requests conj (-> request
                                                                             (select-keys [:scheme :ssl-client-cert])
                                                                             (update :ssl-client-cert #(instance? X509Certificate %))))
                                                     (assoc ctx :response {:body   "Hello World"
                                                                           :status 200}))})
               (jdk-httpserver/create-connector {:keystore     "test/io/pedestal/http/keystore.jks"
                                                 :key-password "password"}))]
    (try
      (connector/start! conn)
      (let [response (http/get "https://localhost:1337" {:insecure? true})]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello World")))
      (finally
        (connector/stop! conn)))))
