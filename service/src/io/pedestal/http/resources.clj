; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.resources
  "Creation of routes to expose file system or classpath resources as GET-able URIs.

  This is an alternative to [[io.pedestal.http.ring-middlewares]]
  that provide _interceptors_ (which bypass routing); these functions
  return [[RoutingFragment]]s that can be combined to as part of the application's routing table."
  {:added "0.8.0"}
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            ring.util.time
            [io.pedestal.http.resources.impl :as impl]
            [io.pedestal.http.route.definition.table :as table]))

(def ^:private default-opts
  {:allow-head?     true
   :prefix          "/public"
   :route-namespace "io.pedestal.http.resources"
   :index-files?    true
   :cache?          true
   :fast?           true})

(defn- clean-path
  "The path may contain redundant slashes; remove all from the start and collapse the rest
  down to a single slash."
  [path]
  (-> path
      (string/replace #"^/+" "")
      (string/replace #"/{2,}" "/")))


(def ^:private not-found {:status  404
                          :headers {"Content-Type" "text/plain"}})

(defn- response
  [response-data body]
  (let [{:keys [content-length last-modified]} response-data]
    {:status  200
     :headers {"Content-Length" (str content-length)
               "Last-Modified"  (ring.util.time/format-date last-modified)}
     :body    body}))

(defn- create-get-handler
  [handler-data-supplier k]
  (fn [request]
    (when-let [data (handler-data-supplier request)]
      (response data ((get data k) request)))))

(defn- create-head-handler
  [handler-data-supplier]
  (fn [request]
    (when-let [data (handler-data-supplier request)]
      (response data nil))))

(defn- valid-prefix?
  [s]
  (and (string? s)
       (not (string/ends-with? s "/"))))

(defn- wrap-with-cache
  [delegate-supplier]
  (let [*cache (atom {})]
    (fn cache-data-supplier [path]
      (or (get @*cache path)
          (let [result (delegate-supplier path)]
            (when result
              (swap! *cache assoc path result)
              result))))))

(defn- make-routes
  [response-supplier suffix opts]
  (let [{:keys [allow-head?
                prefix
                route-namespace
                index-files?
                cache?
                fast?]} opts
        _                     (assert (and (valid-prefix? prefix)
                                           (string/starts-with? prefix "/")))
        response-supplier'    (cond-> response-supplier
                                cache? wrap-with-cache)
        handler-data-supplier (fn [request]
                                (let [{:keys [path]} (:path-params request)]
                                  path
                                  (response-supplier' (clean-path (or path "")))))
        route-path            (str prefix "/*path")
        get-handler           (create-get-handler handler-data-supplier
                                                  (if fast? :streamable-body :response-body))
        head-handler          (create-head-handler handler-data-supplier)
        route-name            (fn [prefix]
                                (keyword route-namespace
                                         (str prefix "-" suffix)))
        routes                (cond-> [[route-path
                                        :get
                                        get-handler
                                        :route-name (route-name "get")]]

                                ;; So, a route doesn't match unless there's at least some
                                ;; value for the *path parameter. With index-files? on, the root-path
                                ;; prefix is valid, and this extra route will match on
                                ;; that exact path.
                                index-files?
                                (conj [prefix
                                       :get
                                       get-handler
                                       :route-name (route-name "get-root")])

                                allow-head? (conj [route-path
                                                   :head
                                                   head-handler
                                                   :route-name (route-name "head")])

                                (and allow-head? index-files?)
                                (conj [prefix
                                       :head
                                       head-handler
                                       :route-name (route-name "head-root")]))]
    (table/table-routes opts routes)))

(defn- create-resource-supplier
  [resource-root class-loader cache?]
  (assert (and (valid-prefix? resource-root)
               (not (string/starts-with? resource-root "/"))))
  (let [class-loader' (or class-loader
                          (.getContextClassLoader (Thread/currentThread)))
        *cache        (when cache?
                        (atom {}))]
    (fn [path]
      (when-let [url (io/resource (str resource-root "/" path) class-loader')]
        (impl/resource-data url *cache)))))

(defn resource-routes
  "Returns a [[RoutingFragment]] of routes to access files on the classpath."
  [opts]
  (let [{:keys [resource-root
                class-loader
                cache?] :as opts'} (-> (merge default-opts opts)
                                       ;; :index-files? only makes sense for file routes.
                                       (dissoc :index-files?))
        supplier (create-resource-supplier resource-root class-loader cache?)]
    (make-routes supplier "resource" opts')))

(defn- valid-root-path?
  [path]
  (and (string? path)
       (not (string/ends-with? path "/")))) []

(defn- create-file-supplier
  [root-path opts]
  (let [root-dir (io/file root-path)
        _        (assert (and (.exists root-dir)
                              (.isDirectory root-dir)))
        {:keys [index-files? cache?]} (assoc opts :root root-path)
        *cache   (when cache?
                   (atom {}))]
    (fn [path]
      (when-let [url (impl/url-for-file root-dir path index-files?)]
        (impl/resource-data url *cache)))))

(defn file-routes
  "Returns a [[RoutingFragment]] of routes to access files on the file system."
  [opts]
  (let [{:keys [file-root] :as opts'} (merge default-opts opts)
        _        (assert (valid-root-path? file-root))
        supplier (create-file-supplier file-root opts')]
    (make-routes supplier "file" opts')))
