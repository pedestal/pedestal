; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

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
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [ring.util.response :as ring-resp]
            [ring.middleware.session.cookie :as cookie]))

(defn html-response
  [html]
  {:status 200 :body html :headers {"Content-Type" "text/html"}})

;; Gather some data from the user to retain in their session.
(defn intro-form
  "Prompt a user for their name, then remember it."
  [req]
  (html-response
   (slurp (io/resource "hello-form.html"))))

(defn introduction
  "Place the name provided by the user into their session, then send
   them to hello."
  [req]
  (let [name (get-in req [:params :name])
        name (if (empty? name) nil name)]
    (-> (ring-resp/redirect "/hello")
        (assoc :session {:name name}))))

;; Behavior dictated by data in the user's session. Using ring
;; middleware means that the request is what gets modified by
;; interceptors.

;; We default in 'Stranger' so users visiting the service can see the
;; behavior of the service when no session data is present.
(defn hello
  "Look up the name for this http session, if present greet the user
   by their name. If not, greet the user as stranger."
  [req]
  (let [name (or (get-in req [:session :name]) "Stranger")]
    (html-response (str "<html><body><h1>Hello, " name "!</h1></body></html>\n"))))


;; Two notes:

;; 1. Storing session data requires specifying the session store
;; in the map. (e.g. `{:store (cookie/cookie-store)}` If :store
;; is not specified, the session data will not be stored.

;; 2: In this example code we do not specify the secret with which the
;; session data is encrypted prior to being sent back to the
;; browser. As a result, the same interceptor instance must be used
;; through the service for the data to be readable and writable. Also,
;; the session data will become unrecoverable when the server process
;; ends. While the browser retains the cookie, the interceptor will
;; treat the unrecoverable ciphertext as non-existant.

;; Set up routes to get all the above handlers accessible.
(def routes
  (let [session-interceptor (middlewares/session {:store (cookie/cookie-store)})]
    (route/expand-routes
     [[["/" {:get `intro-form}]
       ["/introduce" ^:interceptors [(body-params/body-params)
                                     middlewares/params
                                     middlewares/keyword-params
                                     session-interceptor]
        {:post `introduction}]
       ["/hello" ^:interceptors [session-interceptor]
        {:get `hello}]]])))

(def service {:env                 :prod
              ::http/routes        routes
              ::http/resource-path "/public"
              ::http/type          :jetty
              ::http/port          8080})
