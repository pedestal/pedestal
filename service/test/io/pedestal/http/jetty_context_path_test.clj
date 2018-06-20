;; This test file is a port of the official Ring jetty-adapter test
;; that works with Pedestal interceptors. The original version is
;; here:
;;
;; https://github.com/ring-clojure/ring/blob/master/ring-jetty-adapter/test/ring/adapter/test/jetty.clj

(ns io.pedestal.http.jetty-context-path-test
  (:use clojure.test)
  (:require [clj-http.client :as http]
            [io.pedestal.http :as service]
            [io.pedestal.http.route :as route]))

(defn hello-page [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(def routes
  (route/expand-routes
   #{["/hello" :get `hello-page :route-name :hello]}))

(def service-map
  {:io.pedestal.http/type :jetty
   :io.pedestal.http/routes routes})

(defmacro with-server [app options & body]
  `(let [server# (service/create-server (merge ~app {:io.pedestal.http/join? false} ~options))]
     (try
       (service/start server#)
       ~@body
       (finally (service/stop server#)))))

(deftest test-run-jetty-default-context
  (testing "default context-path"
    (with-server service-map {:io.pedestal.http/port 4347}
      (let [response (http/get "http://localhost:4347/hello")
            url-for (route/url-for-routes routes)]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World"))
        (is (= (url-for :hello) "/hello"))))))

(deftest test-run-jetty-custom-context
  (testing "custom context-path"
    (with-server service-map {:io.pedestal.http/port 4347 :io.pedestal.http/container-options {:context-path "/context"}}
      (let [response (http/get "http://localhost:4347/context/hello")
            url-for (route/url-for-routes routes :context "/context")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World"))
        (is (= (url-for :hello) "/context/hello"))))))

#_(def add-context-path-to-request
  {:name ::add-context-path-to-request
   :enter (fn [context] (assoc-in context [:request :context-path] (some-> (:servlet-request context) (.getContextPath))))})

(defn hello-page2 [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (route/url-for :hello)})

(def routes2
  (route/expand-routes
   #{["/hello" :get `hello-page2 :route-name :hello]}))

(def service-map2
  {:io.pedestal.http/type :jetty
   :io.pedestal.http/routes routes2})

(deftest test-run-jetty-custom-context-with-servletcontext
  (testing "custom context-path via servlet context"
    (with-server service-map2 {:io.pedestal.http/port 4347 :io.pedestal.http/container-options {:context-path "/context"}}
      (let [response (http/get "http://localhost:4347/context/hello")]
        (is (= (:status response) 200))
        (is (.startsWith ^String (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "/context/hello"))))))
