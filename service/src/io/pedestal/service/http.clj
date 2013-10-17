; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http
  "Namespace which ties all the pedestal components together in a
  sensible default way to make a full blown application."
  (:require [io.pedestal.service.http.route :as route]
            [io.pedestal.service.http.ring-middlewares :as middlewares]
            [io.pedestal.service.interceptor :as interceptor]
            [io.pedestal.service.http.servlet :as servlet]
            [io.pedestal.service.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.service.http.cors :as cors]
            [ring.util.mime-type :as ring-mime]
            [ring.util.response :as ring-response]
            [clojure.string :as string]
            [cheshire.core :as json]
            [io.pedestal.service.log :as log])
  (:import (java.io OutputStreamWriter)))

;; edn and json response formats

(defn- print-fn
  [prn-fn]
  (fn [output-stream]
    (with-open [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (prn-fn))
      (.flush writer))))

(defn- data-response
  [f content-type]
  (ring-response/content-type
   (ring-response/response (print-fn f))
   content-type))

(defn edn-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in EDN format."
  [obj]
  (data-response #(pr obj) "application/edn;charset=UTF-8"))

(defn json-print
  "Print object as JSON to *out*"
  [obj]
  (json/generate-stream obj *out*))

(defn json-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in JSON format."
  [obj]
  (data-response #(json-print obj) "application/json;charset=UTF-8"))

;; interceptors

(interceptor/defon-request log-request
  "Logs the request's method and uri."
  [request]
  (log/info :msg (format "%s %s"
                         (string/upper-case (name (:request-method request)))
                         (:uri request)))
  request)

(defn- response?
  "A valid response is any map that includes an integer :status
  value."
  [resp]
  (and (map? resp)
       (integer? (:status resp))))

(interceptor/defafter not-found
  "An interceptor that returns a 404 when routing failed to resolve a route."
  [context]
  (if-not (servlet-interceptor/response-sent? context)
    (if-not (response? (:response context))
      (assoc context :response (ring-response/not-found "Not Found"))
      context)
    context))

(interceptor/defon-response html-body
  "Sets the content-type headers to text/html if the body is a string and no content type is set."
  [response]
  (let [body (:body response)
        content-type (get-in response [:headers "Content-Type"])]
    (if (and (string? body) (not content-type))
      (ring-response/content-type response "text/html;charset=UTF-8")
      response)))

(interceptor/defon-response json-body
  "Sets the content-type headers and converts the body to JSON if there's no content type and the
  body is true for coll? i.e. is a map, vector or list. It uses Cheshire to generate the JSON body."
  [response]
  (let [body (:body response)
        content-type (get-in response [:headers "Content-Type"])]
    (if (and (coll? body) (not content-type))
      (-> response
          (ring-response/content-type "application/json;charset=UTF-8")
          (assoc :body (print-fn #(json-print body))))
      response)))

(defn default-interceptors
  "Builds interceptors given an options map with keyword keys prefixed by namespace e.g.
  :io.pedestal.service.http/routes or ::bootstrap/routes if the namespace is aliased to bootstrap.

  Options:

  * :routes: A seq of route maps that defines a service's routes. It's recommended to build this
    using io.pedestal.service.http.route.definition/defroutes.
  * :file-path: File path used as root by the middlewares/file interceptor. If nil, this interceptor
    is not added. Default is nil.
  * :resource-path: File path used as root by the middlewares/resource interceptor. If nil, this interceptor
    is not added. Default is 'public'.
  * :method-param-name: Query string parameter used to set the current HTTP verb. Default is _method.
  * :allowed-origins: Determines what origins are allowed for the cors/allow-origin interceptor. If
     nil, this interceptor is not added. Default is nil.
  * :not-found-interceptor: Interceptor to use when returning a not found response. Default is
     the not-found interceptor.
  * :mime-types: Mime-types map used by the middlewares/content-type interceptor. Default is {}."
  [{routes ::routes
    file-path ::file-path
    resource-path ::resource-path
    method-param-name ::method-param-name
    allowed-origins ::allowed-origins
    not-found-interceptor ::not-found-interceptor
    ext-mime-types ::mime-types
    :or {file-path nil
         resource-path "public"
         not-found-interceptor not-found
         method-param-name :_method
         ext-mime-types {}}
    :as service-map}]
  (assoc service-map ::interceptors
         (cond-> []
                 true (conj log-request)
                 (not (nil? allowed-origins)) (conj (cors/allow-origin allowed-origins))
                 true (conj not-found-interceptor)
                 true (conj (middlewares/content-type {:mime-types ext-mime-types}))
                 true (conj route/query-params)
                 true (conj (route/method-param method-param-name))
                 (not (nil? resource-path)) (conj (middlewares/resource resource-path))
                 (not (nil? file-path)) (conj (middlewares/file file-path))
                 true (conj (route/router routes)))))

(defn dev-interceptors
  [service-map]
  (update-in service-map [::interceptors]
             #(vec (->> %
                        (cons cors/dev-allow-origin)
                        (cons servlet-interceptor/exception-debug)))))

(defn service-fn
  [{interceptors ::interceptors
    :as service-map}]
  (assoc service-map ::service-fn
         (servlet-interceptor/http-interceptor-service-fn interceptors)))

(defn servlet
  [{service-fn ::service-fn
    :as service-map}]
  (assoc service-map ::servlet
         (servlet/servlet :service service-fn)))

(defn create-servlet
  "Creates a servlet given an options map with keyword keys prefixed by namespace e.g.
  :io.pedestal.service.http/interceptors or ::bootstrap/interceptors if the namespace is aliased to bootstrap.

  Options:

  * :interceptors: A vector of interceptors that defines a service.

  Note: Additional options are passed to default-interceptors if :interceptors is not set."
  [{interceptors ::interceptors
    :as options}]
  (cond-> options
          (nil? interceptors) default-interceptors
          true service-fn
          true servlet))

(defn- service-map->server-options
  [service-map]
  (let [server-keys [::host ::port ::join? ::jetty-options]]
    (into {} (map (fn [[k v]] [(keyword (name k)) v]) (select-keys service-map server-keys)))))

(defn- server-map->service-map
  [server-map]
  (into {} (map (fn [[k v]] [(keyword "io.pedestal.service.http" (name k)) v]) server-map)))

(defn server
  [{servlet ::servlet
    type ::type
    :or {type :jetty}
    :as service-map}]
  (let [server-ns (symbol (str "io.pedestal.service.http." (name type)))
        server-fn (do (require server-ns)
                      (resolve (symbol (name server-ns) "server")))
        server-map (server-fn servlet (service-map->server-options service-map))]
    (merge service-map (server-map->service-map server-map))))

(defn create-server
  [options]
  (log/init-java-util-log)
  (-> options
      create-servlet
      server))

(defn start [service-map] ((::start-fn service-map)))

(defn stop [service-map] ((::stop-fn service-map)))

