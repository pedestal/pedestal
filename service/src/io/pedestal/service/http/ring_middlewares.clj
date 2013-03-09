(ns io.pedestal.service.http.ring-middlewares
  "This namespace creates interceptors for ring-core middlewares."
  (:require [io.pedestal.service.interceptor :as interceptor :refer [interceptor definterceptorfn defon-request defon-response defmiddleware]]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.file :as file]
            [ring.middleware.file-info :as file-info]
            [ring.middleware.flash :as flash]
            [ring.middleware.head :as head]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.multipart-params :as multipart-params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.params :as params]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.resource :as resource]
            [ring.middleware.session :as session]))

(defn response-fn-adapter
  "Adapts a ring middleware fn taking a response and request to an interceptor context."
  [response-fn & [opts]]
  (fn [{:keys [request response] :as context}]
    (assoc context :response
           (if opts
             (response-fn response request opts)
             (response-fn response request)))))

(defn leave-interceptor
  "Defines an leave only interceptor given a ring fn."
  [name response-fn & args]
  (interceptor/after name (apply response-fn-adapter response-fn args)))

(definterceptorfn content-type
  "Interceptor for content-type ring middleware."
  [& [opts]]
  (leave-interceptor ::content-type-interceptor content-type/content-type-response opts))

(defmiddleware cookies
  "Interceptor for cookies ring middleware. Be sure to persist :cookies
  from the request to response."
  cookies/cookies-request cookies/cookies-response)

(definterceptorfn file
  "Interceptor for file ring middleware."
  [root-path & [opts]]
  (interceptor/handler ::file #(file/file-request % root-path opts)))

(definterceptorfn file-info
  "Interceptor for file-info ring middleware."
  [& [mime-types]]
  (leave-interceptor ::file-info file-info/file-info-response mime-types))

(definterceptorfn flash
  "Interceptor for flash ring middleware. Be sure to persist keys needed
  by session and cookie interceptors."
  []
  (interceptor :name ::flash
               :enter #(update-in % [:request] flash/flash-request)
               :leave (response-fn-adapter flash/flash-response)))

(definterceptorfn head
  "Interceptor for head ring middleware. If used with defroutes, it will not work
  if specified in an interceptors meta-key."
  []
  (interceptor :name ::head
               :enter #(update-in % [:request] head/head-request)
               :leave (response-fn-adapter head/head-response)))

(defon-request keyword-params
  "Interceptor for keyword-params ring middleware."
  keyword-params/keyword-params-request)

(definterceptorfn multipart-params
  "Interceptor for multipart-params ring middleware."
  [& [opts]]
  (interceptor/on-request ::multipart-params
                          multipart-params/multipart-params-request
                          opts))

(definterceptorfn nested-params
  "Interceptor for nested-params ring middleware."
  [& [opts]]
  (interceptor/on-request ::nested-params
                          nested-params/nested-params-request
                          opts))

(definterceptorfn not-modified
  "Interceptor for not-modified ring middleware."
  []
  (leave-interceptor ::not-modified not-modified/not-modified-response))

(definterceptorfn params
  "Interceptor for params ring middleware."
  [& [opts]]
  (interceptor/on-request ::params params/params-request opts))


(definterceptorfn resource
  "Interceptor for resource ring middleware."
  [root-path]
  (interceptor/handler ::resource #(resource/resource-request % root-path)))

(definterceptorfn session
  "Interceptor for session ring middleware. Be sure to persist :session and
  :session/key from request to the response."
  ([] (session {}))
  ([options]
     (let [options (session/session-options options)]
       (interceptor :name ::session
                    :enter (fn [context] (update-in context [:request] #(session/session-request % options)))
                    :leave (response-fn-adapter session/session-response options)))))
