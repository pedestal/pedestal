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


(definterceptorfn allow-origin
  [allowed-origins]
  (let [allowed? (if (fn? allowed-origins)
                   allowed-origins
                   (fn [origin] (some #(= % origin) (seq allowed-origins))))] 
    (around ::allow-origin
            (fn [context]
              (if-let [origin (get-in context [:request :headers "origin"])]
                (let [allowed (allowed? origin)]
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
                context)))))

(defbefore dev-allow-origin
  [context]
  (let [origin (get-in context [:request :headers "origin"])]
    (log/debug :msg "cors dev processing"
               :origin origin
               :context context)
    (if-not origin
      (assoc-in context [:request :headers "origin"] "")
      context)))


