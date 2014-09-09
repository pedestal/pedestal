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

(ns io.pedestal.http
  "Namespace which ties all the pedestal components together in a
  sensible default way to make a full blown application."
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.cors :as cors]
            [ring.util.mime-type :as ring-mime]
            [ring.util.response :as ring-response]
            [clojure.string :as string]
            [cheshire.core :as json]
            [io.pedestal.log :as log])
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
  "Log the request's method and uri."
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
  (if-not (response? (:response context))
    (assoc context :response (ring-response/not-found "Not Found"))
    context))

(interceptor/defon-response html-body
  "Set the Content-Type header to \"text/html\" if the body is a string and a
  type has not been set."
  [response]
  (let [body (:body response)
        content-type (get-in response [:headers "Content-Type"])]
    (if (and (string? body) (not content-type))
      (ring-response/content-type response "text/html;charset=UTF-8")
      response)))

(interceptor/defon-response json-body
  "Set the Content-Type header to \"application/json\" and convert the body to
  JSON if the body is a collection and a type has not been set."
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
  :io.pedestal.http/routes or ::bootstrap/routes if the namespace is aliased to bootstrap.

  Note:
    No additional interceptors are added if :interceptors key is set.

  Options:

  * :routes: A seq of route maps that defines a service's routes. It's recommended to build this
    using io.pedestal.http.route.definition/defroutes.
  * :file-path: File path used as root by the middlewares/file interceptor. If nil, this interceptor
    is not added. Default is nil.
  * :resource-path: File path used as root by the middlewares/resource interceptor. If nil, this interceptor
    is not added. Default is nil.
  * :method-param-name: Query string parameter used to set the current HTTP verb. Default is _method.
  * :allowed-origins: Determines what origins are allowed for the cors/allow-origin interceptor. If
     nil, this interceptor is not added. Default is nil.
  * :not-found-interceptor: Interceptor to use when returning a not found response. Default is
     the not-found interceptor.
  * :mime-types: Mime-types map used by the middlewares/content-type interceptor. Default is {}.
  * :enable-session: A settings map to include the session middleware interceptor. If nil, this interceptor
     is not added.  Default is nil.
  * :enable-csrf: A settings map to include the csrf-protection interceptor. This implies
     sessions are enabled. If nil, this interceptor is not added. Default is nil.
  * :secure-headers: A settings map for various secure headers.
     Keys are: [:hsts-settings :frame-options-settings :content-type-settings :xss-protection-settings]
     If nil, this interceptor is not added.  Default is the default secure-headers settings"
  [service-map]
  (let [{interceptors ::interceptors
         routes ::routes
         file-path ::file-path
         resource-path ::resource-path
         method-param-name ::method-param-name
         allowed-origins ::allowed-origins
         not-found-interceptor ::not-found-interceptor
         ext-mime-types ::mime-types
         enable-session ::enable-session
         enable-csrf ::enable-csrf
         secure-headers ::secure-headers
         :or {file-path nil
              resource-path nil
              not-found-interceptor not-found
              method-param-name :_method
              ext-mime-types {}
              enable-session nil
              enable-csrf nil
              secure-headers {}}} service-map]
    (if-not interceptors
      (assoc service-map ::interceptors
             (cond-> []
                     true (conj log-request)
                     (not (nil? allowed-origins)) (conj (cors/allow-origin allowed-origins))
                     true (conj not-found-interceptor)
                     (or enable-session enable-csrf) (conj (middlewares/session (or enable-session {})))
                     enable-csrf (conj (csrf/anti-forgery enable-csrf))
                     true (conj (middlewares/content-type {:mime-types ext-mime-types}))
                     true (conj route/query-params)
                     true (conj (route/method-param method-param-name))
                     (not (nil? resource-path)) (conj (middlewares/resource resource-path))
                     (not (nil? file-path)) (conj (middlewares/file file-path))
                     (not (nil? secure-headers)) (conj (sec-headers/secure-headers secure-headers))
                     true (conj (route/router routes))))
      service-map)))

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
  :io.pedestal.http/interceptors or ::bootstrap/interceptors if the namespace is aliased to bootstrap.

  Options:

  * :io.pedestal.http/interceptors: A vector of interceptors that defines a service.

  Note: Additional options are passed to default-interceptors if :interceptors is not set."
  [service-map]
  (-> service-map
      default-interceptors
      service-fn
      servlet))

(defn- service-map->server-options
  [service-map]
  (let [server-keys [::host ::port ::join? ::jetty-options]]
    (into {} (map (fn [[k v]] [(keyword (name k)) v]) (select-keys service-map server-keys)))))

(defn- server-map->service-map
  [server-map]
  (into {} (map (fn [[k v]] [(keyword "io.pedestal.http" (name k)) v]) server-map)))

(defn server
  [{servlet ::servlet
    type ::type
    :or {type :jetty}
    :as service-map}]
  (let [server-ns (symbol (str "io.pedestal.http." (name type)))
        server-fn (do (require server-ns)
                      (resolve (symbol (name server-ns) "server")))
        server-map (server-fn servlet (service-map->server-options service-map))]
    (when (= type :jetty)
      ;; Load in container optimizations (NIO)
      (require 'io.pedestal.http.jetty.container))
    (merge service-map (server-map->service-map server-map))))

(defn create-server
  ([service-map]
   (create-server service-map log/init-java-util-log))
  ([service-map init-fn]
   (init-fn)
   (-> service-map
      create-servlet
      server)))

(defn start [service-map]
  ((::start-fn service-map))
  service-map)

(defn stop [service-map]
  ((::stop-fn service-map))
  service-map)

;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.

(defn servlet-init
  [service config]
  (let [service (create-servlet service)]
    (.init ^javax.servlet.Servlet (::servlet service) config)
    service))

(defn servlet-destroy [service]
  (dissoc service ::servlet))

(defn servlet-service [service servlet-req servlet-resp]
  (.service ^javax.servlet.Servlet (::servlet service) servlet-req servlet-resp))

