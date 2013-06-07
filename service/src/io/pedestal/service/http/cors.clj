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
            [clojure.string :as str]
            [ring.util.response :as ring-response]))

(defn- convert-header-name
  [header-name]
  (str/join "-" (map str/capitalize (str/split (name header-name) #"-"))))

(defn- convert-header-names
  [header-names]
  (str/join ", " (map convert-header-name header-names)))

(defn- preflight
  [context origin]
  (let [cors-headers {"Access-Control-Allow-Origin" origin
                      "Access-Control-Allow-Credentials" (str true)
                      "Access-Control-Allow-Headers"
                      (convert-header-names (keys (get-in context [:request :headers])))
                      "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, HEAD"
                      "Access-Control-Max-Age" (str 10)}]
    (log/info :msg "cors preflight"
              :cors-headers cors-headers)
    (assoc context :response {:status 200
                              :headers cors-headers})))

(definterceptorfn allow-origin
  [allowed-origins]
  (let [allowed? (if (fn? allowed-origins)
                   allowed-origins
                   (fn [origin] (some #(= % origin) (seq allowed-origins))))] 
    (around ::allow-origin
            (fn [context]
              (let [origin (get-in context [:request :headers "origin"])
                    allowed (allowed? origin)
                    preflight-request (= :options (get-in context [:request :request-method]))]
                (log/info :msg "cors request processing"
                          :origin origin
                          :allowed allowed)
                (cond
                 (and origin allowed preflight-request)
                 (preflight context origin)

                 (and origin allowed (not preflight-request))
                 (assoc context :cors-headers {"Access-Control-Allow-Origin" origin
                                               "Access-Control-Allow-Credentials" (str true)})

                 (and origin (not allowed))
                 (assoc context :response {:status 403 :body "Forbidden" :headers {}})

                 :else
                 context)))
            (fn [{:keys [response cors-headers] :as context}]
              (if-not (servlet-interceptor/response-sent? context)
                ;; merge cors headers and expose all response headers
                (if (and cors-headers response)
                  (let [cors-headers (merge cors-headers
                                            {"Access-Control-Expose-Headers"
                                             (convert-header-names (keys (:headers response)))})]
                    (log/info :msg "cors response processing"
                              :cors-headers cors-headers)
                    (update-in context [:response :headers] merge cors-headers))
                  context))))))

(defbefore dev-allow-origin
  [context]
  (let [origin (get-in context [:request :headers "origin"])]
    (log/debug :msg "cors dev processing"
               :origin origin
               :context context)
    (if-not origin
      (assoc-in context [:request :headers "origin"] "")
      context)))


