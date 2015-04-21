(ns io.pedestal.interceptor.error-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as service]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp]
            [io.pedestal.interceptor.error :as error-int]))

(def service-error-handler
  (error-int/error-dispatch [ctx ex]
    [{:exception-type :java.lang.ArithmeticException
      :interceptor ::another-bad-one}] (assoc ctx :response {:status 400 :body "Another bad one"})
    [{:exception-type :java.lang.ArithmeticException}] (assoc ctx :response {:status 400 :body "A bad one"})
    ;; If we don't match, forward it on
    :else (assoc ctx :io.pedestal.impl.interceptor/error ex)))


(defn bad-page
  [request]
  (ring-resp/response (str "Bad division: " (/ 3 0))))

(defn drop-through
  [request]
  (throw (Exception. "Just testing the error-handler, this is not a real exception")))

(defroutes request-handling-routes
  [[:request-handling "error-dispatch.pedestal"
    ["/" ^:interceptors [service-error-handler]
     ["/div" {:any bad-page}]
     ["/div2" {:any [::another-bad-one bad-page]}]
     ["/drop" {:any drop-through}]]]])

(defn make-app [options]
  (-> options
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(def app (make-app {::service/routes request-handling-routes}))

(def url "http://error-dispatch.pedestal/div")
(def url-two (str url "2"))
(def drop-url "http://error-dispatch.pedestal/drop")

(deftest captures-generic-exception
  (is (= (:body (response-for app :get url))
         "A bad one")))

(deftest captures-specific-exception
  (is (= (:body (response-for app :get url-two))
         "Another bad one")))

(deftest allows-fallthrough-behavior
  (let [boom-resp (response-for app :get drop-url)]
    (is (= (:status boom-resp)
           500))
    (is (= (:body boom-resp)
           "Internal server error: exception"))))

