(ns io.pedestal.http.secure-headers-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as service]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp]))



(defn hello-page
  [request] (ring-resp/response "HELLO"))

(defroutes app-routes
  [[["/hello" {:get hello-page}]]])

(defn make-app [options]
  (-> (merge {::service/routes app-routes} options)
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(deftest options-are-repected
  (let [app (make-app {::service/secure-headers
                       {:hsts-settings (sec-headers/hsts-header 500)
                        :frame-options-settings (sec-headers/frame-options-header "SAMEORIGIN")
                        :xss-protection-settings (sec-headers/xss-protection-header 1)}})
        response (response-for app :get "/hello")]
    (is (= {"Content-Type" "text/plain"
            "Strict-Transport-Security" "max-age=500"
            "X-Frame-Options" "SAMEORIGIN"
            "X-Content-Type-Options" "nosniff"
            "X-XSS-Protection" "1"}
           (:headers response)))))

