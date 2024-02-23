(ns io.pedestal.http.secure-headers-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.http :as service]
            [io.pedestal.http.secure-headers :as sec-headers]
            [ring.util.response :as ring-resp]))



(defn hello-page
  [_request]
  (ring-resp/response "HELLO"))

(def app-routes
  `[[["/hello" {:get hello-page}]]])

(defn make-app [options]
  (-> (merge {::service/routes app-routes} options)
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(deftest options-are-repected
  (let [app      (make-app {::service/secure-headers
                            {:hsts-settings                    (sec-headers/hsts-header 500)
                             :frame-options-settings           (sec-headers/frame-options-header "SAMEORIGIN")
                             :xss-protection-settings          (sec-headers/xss-protection-header 1)
                             :download-options-settings        (sec-headers/download-options-header nil)
                             :cross-domain-policies-settings   (sec-headers/cross-domain-policies-header "master-only")
                             :content-security-policy-settings (sec-headers/content-security-policy-header {:default-src "'self'"})}})
        response (response-for app :get "/hello")]
    (is (= {"Content-Type"                      "text/plain"
            "Strict-Transport-Security"         "max-age=500"
            "X-Frame-Options"                   "SAMEORIGIN"
            "X-Content-Type-Options"            "nosniff"
            "X-XSS-Protection"                  "1"
            "X-Permitted-Cross-Domain-Policies" "master-only"
            "Content-Security-Policy"           "default-src 'self'"}
           (:headers response)))))

(deftest secure-headers-can-be-turned-off
  (let [app      (make-app {::service/secure-headers nil})
        response (response-for app :get "/hello")]
    (is (= {"Content-Type" "text/plain"}
           (:headers response)))))

(def custom-sec-headers
  {:leave (fn [context]
            (update-in context [:response :headers]
                       merge
                       {(sec-headers/header-names :xss-protection) (sec-headers/xss-protection-header "1")}))})

(def new-app-routes
  `[[["/hello" {:get [^:interceptors [custom-sec-headers] hello-page]}]]])

(deftest custom-secure-headers
  (let [app      (make-app {::service/routes         new-app-routes
                            ::service/secure-headers nil})
        response (response-for app :get "/hello")]
    (is (= {"Content-Type"     "text/plain"
            "X-XSS-Protection" "1"}
           (:headers response)))))

