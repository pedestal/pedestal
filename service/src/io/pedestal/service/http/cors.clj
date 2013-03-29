; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http.cors
  (:require [io.pedestal.service.interceptor :refer :all]
            [io.pedestal.service.log :as log]
            [ring.util.response :as ring-response]))

(defn origin
  "Returns the Origin request header."
  [request] (get (:headers request) "origin"))

(defn allow-request?
  "Returns true if the request's origin matches the access control
  origin, otherwise false."
  [request access-control]
  (let [origin (origin request)
        allowed (:access-control-allow-origin access-control)]
    (if (and origin allowed (some #(re-matches % origin) (if (sequential? allowed) allowed [allowed])))
      true false)))

(defn header-name
  "Returns the capitalized header name as a string."
  [header] (if header (join "-" (map capitalize (split (name header) #"-")))))

(defn normalize-headers
  "Normalize the headers by converting them to capitalized strings."
  [headers] (reduce #(assoc %1 (header-name (first %2)) (last %2)) {} headers))

(defn add-access-control
  "Add the access control headers using the request's origin to the response."
  [request response access-control]
  (if-let [origin (origin request)]
    (let [access-headers (normalize-headers (assoc access-control :access-control-allow-origin origin))]
      (assoc response :headers (merge (:headers response) access-headers)))
    response))

;; in dev mode - if no Origin, let it go; if Origin, say yes to everything and reply with correct header
;; in prod mode - if no Origin, fail; if Origin not in set, fail

(definterceptorfn allow-origin
  [origin-whitelist]
  (around ::origin
          (fn [context]
            (let [origin (get-in context [:request :headers "Origin"] "")
                  access-control {:access-control-allow-origin #"localhost:8080"}]
              (if (cors/allow-request? request access-control)
                (do (log/debug :msg "allowing request"
                               :request request
                               :access-control access-control)
                    (update-in context [:response] #(cors/add-access-control request % access-control)))
                (do (log/debug :msg "not allowing request") context))))
          (fn [context]
            )))


#_(defbefore cors-options-interceptor
  "Interceptor that adds CORS headers when the origin matches the authorized origin."
  [context]
  ;; special case options request
  (let [request (:request context)
        _ (log/debug :msg "options request headers" :headers (:headers request))
        preflight-origin (get-in request [:headers "origin"])
        preflight-headers (get-in request [:headers "access-control-request-headers"])]
    (log/debug :msg "access-control-request-headers" :preflight-headers preflight-headers)
    (assoc context :response
           (when (= (:request-method request) :options)
             (-> (ring-response/response "")
                 (ring-response/header "Access-Control-Allow-Origin" preflight-origin)
                 (ring-response/header "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS, HEAD")
                 (ring-response/header "Access-Control-Allow-Headers" preflight-headers))))))

(defbefore cors-sse-clever-hack-interceptor
  [context]
  (let [request (:request context)
        servlet-response (:servlet-response request)
        access-control {:access-control-allow-origin #"localhost:8080"}
        response (-> (ring-response/response "")
                     (ring-response/content-type "text/event-stream")
                     (ring-response/charset "UTF-8")
                     (ring-response/header "Connection" "close")
                     (ring-response/header "Cache-control" "no-cache")
                     (#(cors/add-access-control request % access-control)))]
    (servlet-interceptor/set-response servlet-response response)
    (.flushBuffer servlet-response)
    context))

