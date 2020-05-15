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

(ns io.pedestal.http.ring-middlewares
  "This namespace creates interceptors for ring-core middlewares."
  (:require [clojure.java.io :as io]
            [io.pedestal.http.params :as pedestal-params]
            [io.pedestal.http.request :as request]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.helpers :as interceptor]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.file :as file]
            [ring.middleware.file-info :as file-info]
            [ring.middleware.flash :as flash]
            [ring.middleware.head :as head]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.multipart-params :as multipart-params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [ring.middleware.session :as session]
            [ring.util.mime-type :as mime]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp])
  (:import (java.nio.channels FileChannel)
           (java.nio.file OpenOption
                          StandardOpenOption)
           (java.io File)))

(defn response-fn-adapter
  "Adapts a ring middleware fn taking a response and request to an interceptor context."
  ([response-fn]
   (fn [{:keys [request response] :as context}]
     (if response
       (assoc context :response (response-fn response request))
       context)))
  ([response-fn opts]
   (if (seq opts)
     (fn [{:keys [request response] :as context}]
       (if response
         (assoc context :response (response-fn response request opts))
         context))
     (response-fn-adapter response-fn))))

(defn- leave-interceptor
  "Defines an leave only interceptor given a ring fn."
  [name response-fn & [args]]
  (interceptor/after name (response-fn-adapter response-fn args)))

(defn- content-type-response
  "Tries adding a content-type header to response by request URI (unless one
  already exists)."
  [resp req & [opts]]
  (if-let [mime-type (or (get-in resp [:headers "Content-Type"])
                         (mime/ext-mime-type (:uri req) (:mime-types opts)))]
    (assoc-in resp [:headers "Content-Type"] mime-type)
    resp))

(defn content-type
  "Interceptor for content-type ring middleware."
  [& [opts]]
  (leave-interceptor ::content-type-interceptor content-type-response opts))

(def cookies
  "Interceptor for cookies ring middleware. Be sure to persist :cookies
  from the request to response."
  (interceptor/middleware
    ::cookies
    cookies/cookies-request cookies/cookies-response))

(defn file
  "Interceptor for file ring middleware."
  [root-path & [opts]]
  (interceptor/handler ::file #(file/file-request % root-path opts)))

(defn file-info
  "Interceptor for file-info ring middleware."
  [& [mime-types]]
  (leave-interceptor ::file-info file-info/file-info-response mime-types))

(defn flash
  "Interceptor for flash ring middleware. Be sure to persist keys needed
  by session and cookie interceptors."
  []
  (interceptor {:name ::flash
                :enter #(update-in % [:request] flash/flash-request)
                :leave (response-fn-adapter flash/flash-response)}))

(defn head
  "Interceptor to handle head requests. If used with defroutes, it will not work
  if specified in an interceptor's meta-key."
  []
  (interceptor {:name ::head
                :enter (fn [ctx]
                         (if (= :head (get-in ctx [:request :request-method]))
                           (-> ctx
                               (assoc :head-request? true)
                               (assoc-in [:request :request-method] :get))
                           ctx))
                :leave (fn [{:keys [request response] :as ctx}]
                         (if (and response (:head-request? ctx))
                           (update ctx :response assoc :body nil)
                           ctx))}))

(def keyword-params
  "Retained for backward compatibility. io.pedestal.http.params/keyword-params is recommended"
  pedestal-params/keyword-params)

(defn multipart-params
  "Interceptor for multipart-params ring middleware."
  [& [opts]]
  (interceptor/on-request ::multipart-params
                          multipart-params/multipart-params-request
                          opts))

(defn nested-params
  "Interceptor for nested-params ring middleware."
  [& [opts]]
  (interceptor/on-request ::nested-params
                          nested-params/nested-params-request
                          opts))

(defn not-modified
  "Interceptor for not-modified ring middleware."
  []
  (leave-interceptor ::not-modified not-modified/not-modified-response))

(defn params
  "Interceptor for params ring middleware."
  [& [opts]]
  (interceptor/on-request ::params params/params-request opts))

(defn resource
  "Interceptor for resource ring middleware"
  [root-path]
  (interceptor/handler ::resource #(resource/resource-request % root-path)))

(defn fast-resource
  "Interceptor for resource handling.
  This interceptor will return async responses for large files (files larger than the HTTP Buffer)
  If your container doesn't recognize FileChannel response bodies, this interceptor will cause errors
  Supports a map of options:
  :index? - If path is a directory, will attempt to find an 'index.*' file to serve. Defaults to true
  :follow-symlinks? - Serve files through symbolic links. Defaults to false
  :loader - A class loader specific for these resource fetches. Default to nil (use the main class loader)"
  ([root-path]
   (fast-resource root-path {:index? true
                             :allow-symlinks? false
                             :loader nil}))
  ([root-path opts]
   (let [ {:keys [loader]} opts]
     (interceptor
       {:name ::fast-resource
        :enter (fn [ctx]
                 (let [{:keys [request]} ctx
                       {:keys [servlet-response uri path-info request-method]} request]
                   (if (#{:head :get} request-method)
                     (let [buffer-size-bytes (if servlet-response
                                               (request/response-buffer-size servlet-response)
                                               ;; let's play it safe and assume 1500 MTU
                                               1460)
                           uri-path (subs (codec/url-decode (or path-info uri)) 1)
                           path (->  (str  (or root-path "") "/" uri-path)
                                    (.replace "//" "/")
                                    (.replaceAll "^/" ""))
                           resource (if loader
                                      (io/resource path loader)
                                      (io/resource path))
                           file-resp (and resource
                                          (ring-resp/file-response (.getAbsolutePath ^File (io/as-file resource))
                                                                   opts))
                           response (and file-resp
                                         (if (>= buffer-size-bytes
                                                 ;; TODO: Nothing like losing the data to private functions
                                                 ;;  - rewrite the above to do the file lookup and response generation directly
                                                 (Long/parseLong (get-in file-resp [:headers "Content-Length"])))
                                           file-resp
                                           (assoc file-resp
                                                  :body (FileChannel/open (.toPath ^File (:body file-resp))
                                                                          (into-array OpenOption [StandardOpenOption/READ])))))]
                       (if response
                         (assoc ctx :response response)
                         ctx))
                     ctx)))}))))

(defn session
  "Interceptor for session ring middleware. Be sure to persist :session and
  :session/key from request to the response."
  ([] (session {}))
  ([options]
     (let [options ((deref #'session/session-options) options)]
       (interceptor {:name ::session
                     :enter (fn [context] (update-in context [:request] #(session/session-request % options)))
                     :leave (response-fn-adapter session/session-response options)}))))
