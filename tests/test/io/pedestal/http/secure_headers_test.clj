(ns io.pedestal.http.secure-headers-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.connector :as conn]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.service.interceptors :as interceptors]
            [ring.util.response :as ring-resp]))



(defn hello-page
  [_request]
  (ring-resp/response "HELLO"))

(def app-routes
  [[["/hello" {:get #'hello-page}]]])

(defn- response-for
  [connector verb url & {:as options}]
  (test/response-for connector verb url options))

(defn make-app
  "Was default-interceptors with the ::service/routes and ::service/secure-headers options.

  :secure-headers is passed to with-default-interceptors. A nil :secure-headers turned the
  secure-headers interceptor off; with-default-interceptors always adds it (nil means the default
  settings), so without secure headers the default interceptors are listed instead."
  [{:keys [routes secure-headers]
    :or   {routes app-routes}
    :as   options}]
  (-> (conn/default-connector-map -1)
      (cond->
        (some? secure-headers) (conn/with-default-interceptors :secure-headers secure-headers)
        (and (contains? options :secure-headers)
             (nil? secure-headers)) (conn/with-interceptors [interceptors/log-request
                                                             interceptors/not-found
                                                             (ring-middlewares/content-type)
                                                             route/query-params]))
      (conn/with-routes routes)
      (jetty/create-connector nil)))

(deftest options-are-repected
  (let [app      (make-app {:secure-headers
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
  (let [app      (make-app {:secure-headers nil})
        response (response-for app :get "/hello")]
    (is (= {"Content-Type" "text/plain"}
           (:headers response)))))

(def custom-sec-headers
  {:leave (fn [context]
            (update-in context [:response :headers]
                       merge
                       {(sec-headers/header-names :xss-protection) (sec-headers/xss-protection-header "1")}))})

(def new-app-routes
  [[["/hello" {:get [^:interceptors [custom-sec-headers] #'hello-page]}]]])

(deftest custom-secure-headers
  (let [app      (make-app {:routes         new-app-routes
                            :secure-headers nil})
        response (response-for app :get "/hello")]
    (is (= {"Content-Type"     "text/plain"
            "X-XSS-Protection" "1"}
           (:headers response)))))

