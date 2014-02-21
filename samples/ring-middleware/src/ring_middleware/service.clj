; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ring-middleware.service
    (:require [clojure.java.io :as io]
              [io.pedestal.service.http :as bootstrap]
              [io.pedestal.service.http.route :as route]
              [io.pedestal.service.http.body-params :as body-params]
              [io.pedestal.service.http.route.definition :refer [defroutes]]
              [io.pedestal.service.http.ring-middlewares :as middlewares]
              [io.pedestal.service.interceptor :refer [defhandler definterceptor]]
              [ring.middleware.session.cookie :as cookie]
              [ring.util.response :as ring-resp]))


(defn html-response
  [html]
  (ring-resp/content-type (ring-resp/response html) "text/html"))

;; Gather some data from the user to retain in their session.
(defhandler intro-form
  "Prompt a user for their name, then remember it."
  [req]
  (html-response
   (slurp (io/resource "hello-form.html"))))

(defhandler introduction
  "Place the name provided by the user into their session, then send
  them to hello."
  [req]
  (let [name (-> req
                 :params
                 :name)]
    (-> (ring-resp/redirect "/hello")
        (assoc :session {:name name}))))

;; Behavior dictated by data in the user's session. Using ring
;; middleware means that the request is what gets modified by
;; interceptors.

;; We default in 'Stranger' so users visiting the service can see the
;; behavior of the service when no session data is present.
(defhandler hello
  "Look up the name for this http session, if present greet the user
  by their name. If not, greet the user as stranger."
  [req]
  (let [name (or (-> req
                     :session
                     :name)
                 "Stranger")]
    (html-response (str "<html><body><h1>Hello, " name "!</h1></body></html>"))))

;; Two notes:

;; 1: You can create a session interceptor without specifying a store,
;; in which case the interceptor will store the session data nowhere
;; and it will be about as useful as not having it in the first
;; place. Storing session data requires specifying the session store.

;; 2: In this example code we do not specify the secret with which the
;; session data is encrypted prior to being sent back to the
;; browser. This has two consequences, the first being that we need to
;; use the same interceptor instance throughout the service so that the
;; session data is readable and writable to all paths. The second
;; consequence is that session data will become unrecoverable when the
;; server process is ended. Even though the browser retains the
;; cookie, it is not unrecoverable ciphertext and the session
;; interceptor will treat it as non-existant.
(definterceptor session-interceptor
  (middlewares/session {:store (cookie/cookie-store)}))

;; Set up routes to get all the above handlers accessible.
(defroutes routes
  [[["/" {:get intro-form}]
    ["/introduce" ^:interceptors [middlewares/params
                                  middlewares/keyword-params
                                  session-interceptor]
     {:post introduction}]
    ["/hello" ^:interceptors [session-interceptor]
     {:get hello}]]])

;; You can use this fn or a per-request fn via io.pedestal.service.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by ring-middleware.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes
              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;; Choose from [:jetty :tomcat].
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})
