; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.csrf
  "CSRF protection interceptor support, compatible with ring-anti-forgery"
  (:require [crypto.random :as random]
            [crypto.equality :as crypto]
            [io.pedestal.interceptor.helpers :as interceptor :refer [around]]))

;; This is a port of the ring-anti-forgery CSRF protection, with the following
;; differences:
;;  * Optionally include (and handle) a double-submit cookie
;;  * A function to get the current token (to embed in a head/meta tag, html form)
;;    given the context
;;  * CSRF token is in the `request` not in a dynamic var
;;  * Structured for Pedestal interceptor
;;  * No third-party dependency on an HTML templating library


;; this is both the marker and the function (to use with the context)
(def anti-forgery-token ::anti-forgery-token)

(defn existing-token [request]
  (get-in request [:session "__anti-forgery-token"]))

(defn- session-token [request]
  (or (existing-token request)
      (random/base64 60)))

(defn- assoc-session-token [response request token]
  (let [old-token (existing-token request)]
    (if (= old-token token)
      response
      (-> response
          (assoc :session (:session request))
          (assoc-in [:session "__anti-forgery-token"] token)))))

;; This must be run after the session token setting
(defn- assoc-double-submit-cookie [request response]
  ;; The token should also be in a cookie for JS (proper double submit)
  (assoc-in response
            [:cookies "__anti-forgery-token"]
            (existing-token request)))

(defn- form-params [request]
  (merge (:form-params request)
         (:multipart-params request)))

(defn- default-request-token [request]
  (or (-> request form-params :__anti-forgery-token)
      (-> request :headers (get "x-csrf-token"))
      (-> request :headers (get "x-xsrf-token"))))

(defn- valid-request? [request read-token]
  (let [user-token   (read-token request)
        stored-token (session-token request)]
    (and user-token
         stored-token
         (crypto/eq? user-token stored-token))))

(defn- get-request? [{method :request-method :as request}]
  (or (= :head method)
      (= :get method)))

(defn access-denied-response [body]
  {:status 403
   :headers {"Content-Type" "text/html"}
   :body body})

(def denied-msg "<h1>Invalid anti-forgery token</h1>")

(def default-error-response (access-denied-response denied-msg))

(defn anti-forgery
  "Interceptor that prevents CSRF attacks. Any POST/PUT/PATCH/DELETE request to
  the handler returned by this function must contain a valid anti-forgery
  token, or else an access-denied response is returned.

  The anti-forgery token can be placed into a HTML page via the
  ::anti-forgery-token within the request, which is bound to a random key
  unique to the current session. By default, the token is expected to be in a
  form field named '__anti-forgery-token', or in the 'X-CSRF-Token' or
  'X-XSRF-Token' headers.

  This behavior can be customized by supplying a map of options:
    :read-token
      a function that takes a request and returns an anti-forgery token, or nil
      if the token does not exist.
    :cookie-token
      a truthy value, if you want a CSRF double-submit cookie set
    :error-response
      the response to return if the anti-forgery token is incorrect or missing.
    :error-handler
      a handler function (passed the context) to call if the anti-forgery
      token is incorrect or missing (intended to return a valid response).

  Only one of :error-response, :error-handler may be specified."
  ([] (anti-forgery {}))
  ([options]
   {:pre [(not (and (:error-response options)
                    (:error-handler options)))]}
   (let [token-reader (:read-token options default-request-token)
         cookie-token? (:cookie-token options)
         error-response (:error-response options default-error-response)
         error-handler (:error-handler options (fn [context]
                                                 (assoc-in context [:response] error-response)))]
     (around ::anti-forgery
       (fn [{request :request :as context}]
         (let [token (session-token request)]
           (if (and (not (get-request? request))
                    (not (valid-request? request token-reader)))
             (error-handler context)
             (assoc-in context [:request anti-forgery-token] token))))
       (fn [{response :response req :request :as context}]
         (let [token (anti-forgery-token req)]
           (assoc context
                  :response (cond-> (assoc-session-token response req token)
                              cookie-token? (#(assoc-double-submit-cookie req %))))))))))
