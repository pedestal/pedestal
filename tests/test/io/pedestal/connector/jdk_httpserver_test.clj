(ns io.pedestal.connector.jdk-httpserver-test
  (:require [charred.api :as json]
            [io.pedestal.connector.jdk-httpserver :as jdk-httpserver]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.http.response :as response]
            [matcher-combinators.matchers :as m]
            [ring.util.response :refer [response]]
            [org.httpkit.client :as client]
            [clojure.core.async :refer [go]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.test-common :as tc]
            [io.pedestal.connector :as connector])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn hello-page
  [_request]
  (response "HELLO"))

(def async-hello
  (interceptor
    {:name  ::async-hello
     :enter (fn [context]
              (go
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
