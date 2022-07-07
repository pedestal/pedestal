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

(ns io.pedestal.http.cors
  (:require [io.pedestal.interceptor.helpers :as interceptor]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.log :as log]
            [clojure.string :as str]
            [ring.util.response :as ring-response]))

(defn- convert-header-name
  [header-name]
  (str/join "-" (map str/capitalize (str/split (name header-name) #"-"))))

(defn- convert-header-names
  [header-names]
  (str/join ", " (map convert-header-name header-names)))

(defn- preflight
  [{request :request :as context} origin {:keys [creds max-age methods] :as args}]
  (let [requested-headers (get-in request [:headers "access-control-request-headers"])
        cors-headers (merge  {"Access-Control-Allow-Origin" origin
                              "Access-Control-Allow-Headers"
                              (str "Content-Type, "
                                   (when requested-headers (str requested-headers ", "))
                                   (convert-header-names (keys (:headers request))))
                              "Access-Control-Allow-Methods" (if methods
                                                               methods
                                                               "GET, POST, PUT, DELETE, HEAD, PATCH, OPTIONS")}
                             (when creds {"Access-Control-Allow-Credentials" (str creds)})
                             (when max-age {"Access-Control-Max-Age" (str max-age)}))]
    (log/info :msg "cors preflight"
              :requested-headers requested-headers
              :headers (:headers request)
              :cors-headers cors-headers)
    (log/meter ::preflight)
    (assoc context :response {:status 200
                              :headers cors-headers})))

(defn- normalize-args
  [arg]
  (if (map? arg)
    (update-in arg [:allowed-origins]
               (fn [x] (if (fn? x)
                         x
                         (let [x-set (into #{} x)]
                           ;; We could just return x-set, but this adheres to the old API
                           (fn [origin] (x-set origin))))))
    (normalize-args {:allowed-origins arg})))

(defn allow-origin
  "Builds a CORS interceptor that allows calls from the specified `allowed-origins`, which is one of the following:

  - a sequence of strings

  - a function of one argument that returns a truthy value when an origin is allowed

  - a map containing the following keys and values

    :allowed-origins - either sequence of strings or a function as above

    :creds - true or false, indicates whether client is allowed to send credentials

    :max-age - a long, indicates the number of seconds a client should cache the response from a preflight request

    :methods - a string, indicates the accepted HTTP methods.  Defaults to \"GET, POST, PUT, DELETE, HEAD, PATCH, OPTIONS\"
  "
  [allowed-origins]
  (let [{:keys [creds max-age methods allowed-origins] :as args} (normalize-args allowed-origins)]
    (interceptor/around ::allow-origin
                        (fn [context]
                          (let [origin (get-in context [:request :headers "origin"])
                                allowed (allowed-origins origin)
                                preflight-request (= :options (get-in context [:request :request-method]))]
                            (log/info :msg "cors request processing"
                                      :origin origin
                                      :allowed allowed)
                            (cond
                              ;; origin is allowed and this is preflight
                              (and origin allowed preflight-request)
                              (preflight context origin args)

                              ;; origin is allowed and this is real
                              (and origin allowed (not preflight-request))
                              (do (log/meter ::origin-real)
                                (assoc context :cors-headers (merge {"Access-Control-Allow-Origin" origin}
                                                                  (when creds {"Access-Control-Allow-Credentials" (str creds)}))))

                              ;; origin is not allowed
                              (and origin (not allowed))
                              (assoc context :response {:status 403 :body "Forbidden" :headers {}})

                              ;; no origin
                              :else
                              context)))

                        (fn [{:keys [response cors-headers] :as context}]
                          (if (and cors-headers response)
                            (let [cors-headers (merge cors-headers
                                                      {"Access-Control-Expose-Headers"
                                                       (convert-header-names (keys (:headers response)))})]
                              (log/info :msg "cors response processing"
                                        :cors-headers cors-headers)
                              (update-in context [:response :headers] merge cors-headers))
                            context)))))

(def dev-allow-origin
  (interceptor/before
    ::dev-allow-origin
    (fn [context]
      (let [origin (get-in context [:request :headers "origin"])]
        (log/debug :msg "cors dev processing"
                   :origin origin
                   :context context)
        (if-not origin
          (assoc-in context [:request :headers "origin"] "")
          context)))))

