(ns io.pedestal.service.http
  "Namespace which ties all the pedestal components together in a
  sensible default way to make a full blown application."
  (:require [io.pedestal.service.http.route :as route]
            [io.pedestal.service.http.ring-middlewares :as middlewares]
            [io.pedestal.service.interceptor :as interceptor]
            [io.pedestal.service.http.servlet :as servlet]
            [io.pedestal.service.http.impl.servlet-interceptor :as http-servlet-interceptor]
            [ring.util.mime-type :as ring-mime]
            [ring.util.response :as ring-response]
            [clojure.string :as string]
            [clojure.data.json :as json]
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
  [obj]
  (data-response (partial pr obj) "application/edn;charset=UTF-8"))

(defn json-response
  [obj]
  (data-response (partial json/pprint obj) "application/json;charset=UTF-8"))

;; interceptors

(interceptor/defon-request log-request
  [request]
  (log/info :msg (format "%s %s"
                         (string/upper-case (name (:request-method request)))
                         (:uri request)))
  request)

(interceptor/defafter not-found
  "An interceptor that returns a 404 when routing failed to resolve a route."
  [context]
  (if (ring-response/response? (:response context))
    context
    (assoc context :response (ring-response/not-found "Not Found"))))

(defn add-content-type
  "Based on the given `interceptor`, returns a new interceptor that
  includes a leave fn that sets the HTTP content-type header if there
  is a response body that is a file and the content-type has not been
  set."
  [interceptor]
  (letfn [(content-type [context]
            (let [body (get-in context [:response :body])
                  content-type (get-in context [:response :headers "Content-Type"])]
              (if (and body
                       (not content-type)
                       (= (type body) java.io.File))
                (update-in context [:response] ring-response/content-type
                           (ring-mime/ext-mime-type (.getAbsolutePath ^java.io.File body)))
                context)))]
    (assoc interceptor :leave content-type)))

(defn default-interceptors
  [{routes ::routes
    file-path ::file-path
    resource-path ::resource-path
    method-param-name ::method-param-name
    :or {file-path nil
         resource-path "public"
         method-param-name :_method}
    :as service-map}]
  (assoc service-map ::interceptors
         (cond-> []
                 true (conj not-found)
                 true (conj log-request)
                 true (conj middlewares/content-type)
                 true (conj route/query-params)
                 true (conj (route/method-param method-param-name))
                 (not (nil? resource-path)) (conj (add-content-type (middlewares/resource resource-path)))
                 (not (nil? file-path)) (conj (add-content-type (middlewares/file file-path)))
                 true (conj (route/router routes)))))

(defn dev-interceptors
  [service-map]
  (update-in service-map [::interceptors]
             #(vec (cons http-servlet-interceptor/exception-debug %))))

(defn service-fn
  [{interceptors ::interceptors
    :as service-map}]
  (assoc service-map ::service-fn
         (http-servlet-interceptor/http-interceptor-service-fn interceptors)))

(defn servlet
  [{service-fn ::service-fn
    :as service-map}]
  (assoc service-map ::servlet
         (servlet/servlet :service service-fn)))

(defn create-servlet
  [{interceptors ::interceptors
    :as options}]
  (cond-> options
          (nil? interceptors) default-interceptors
          true service-fn
          true servlet))

(defn- service-map->server-options
  [service-map]
  (let [server-keys [::port ::join? ::jetty-options]]
    (into {} (map (fn [[k v]] [(keyword (name k)) v]) (select-keys service-map server-keys)))))

(defn- server-map->service-map
  [server-map]
  (into {} (map (fn [[k v]] [(keyword "io.pedestal.service.http" (name k)) v]) server-map)))

(defn server
  [{servlet ::servlet
    type ::type
    :or {::type :jetty}
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

