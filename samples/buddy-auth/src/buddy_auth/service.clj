(ns buddy-auth.service
  "A simple service demonstrating integration with the buddy-auth
  authentication/authorization library."
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.interceptor.error :refer [error-dispatch]]
            [io.pedestal.interceptor.helpers :refer [on-request]]
            [ring.util.response :as ring-resp]
            [buddy.auth :as auth]
            [buddy.auth.backends :as auth.backends]
            [buddy.auth.middleware :as auth.middleware]))

(def users
  "A sample user store."
  {:aaron {:display-name "Aaron Aardvark"
           :password     "secret"
           :role         :user}
   :gmw   {:display-name "Gerald M. Weinberg"
           :password     "rutabaga"
           :role         :admin}})

(def basic-auth-backend
  "A buddy-auth Basic Authentication backend.  See
  https://funcool.github.io/buddy-auth/latest/#http-basic"
  (auth.backends/basic {:realm  "MyApi"
                        :authfn (fn [_ authdata]
                                  (let [{:keys [username password]} authdata
                                        known-user                  (get users (keyword username))]
                                    (when (= (:password known-user) password)
                                      (keyword username))))}))

(defn home
  "Given a request, returns a user-specific response if authenticated,
  otherwise the response is anonymous."
  [request]
  (ring-resp/response (if (auth/authenticated? request)
                        (str "Hello " (:display-name (get users (:identity request))))
                        "Hello anonymous")))

(defn admin
  "Returns a 200 response for authorized users, otherwise throws a buddy-auth
  'unauthorized' exception."
  [request]
  (let [known-user (get users (:identity request))]
    (if (= :admin (:role known-user))
      (ring-resp/response  "Only admins can see this!")
      (buddy.auth/throw-unauthorized))))

(def authentication-interceptor
  "Port of buddy-auth's wrap-authentication middleware."
  (on-request ::authenticate auth.middleware/authentication-request basic-auth-backend))

(defn authorization-interceptor
  "Port of buddy-auth's wrap-authorization middleware."
  [backend]
  (error-dispatch [ctx ex]
                  [{:exception-type :clojure.lang.ExceptionInfo :stage :enter}]
                  (try
                    (assoc ctx
                           :response
                           (auth.middleware/authorization-error (:request ctx)
                                                                ex
                                                                backend))
                    (catch Exception e
                      (assoc ctx ::interceptor.chain/error e)))

                  :else (assoc ctx ::interceptor.chain/error ex)))

(def common-interceptors [(body-params/body-params)
                          http/html-body
                          authentication-interceptor
                          (authorization-interceptor basic-auth-backend)])

(def routes #{["/" :get (conj common-interceptors `home)]
              ["/admin" :get (conj common-interceptors `admin)]})

(def service {:env                     :prod
              ::http/routes            routes
              ::http/resource-path     "/public"
              ::http/type              :jetty
              ::http/port              8080
              ::http/container-options {:h2c? true
                                        :h2?  false
                                        :ssl? false}})
