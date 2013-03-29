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
            [io.pedestal.service.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.service.log :as log]
            [ring.util.response :as ring-response]))

(defn allowed?
  [allowed-origins origin]
  (some #(re-find % origin) allowed-origins))

(definterceptorfn allow-origin
  [allowed-origins]
  (around ::allow-origin
          (fn [context]
            (if-let [origin (get-in context [:request :headers "origin"])]
              (let [allowed (allowed? allowed-origins origin)]
                (log/debug :msg "cors processing"
                           :origin origin
                           :allowed allowed)
                (if allowed
                  (assoc context :cors-headers {"Access-Control-Allow-Origin" origin})
                  (assoc context :response {:status 403 :body "Forbidden" :headers {}})))
              context))
          (fn [context]
            (if-not (servlet-interceptor/response-sent? context)
              (update-in context [:response :headers] merge (:cors-headers context))
              context))))

(defbefore dev-allow-origin
  [context]
  (let [origin (get-in context [:request :headers "origin"])]
    (log/debug :msg "cors dev processing"
               :origin origin
               :context context)
    (if-not origin
      (assoc-in context [:request :headers "origin"] "")
      context)))

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

#_(defbefore cors-sse-clever-hack-interceptor
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
