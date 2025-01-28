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
  which provides _interceptors_ (which bypass routing); these functions
  return [[RoutingFragment]]s that can be combined to form the application's routing table.

  "
  {:added "0.8.0"}
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.response :as response]
            [io.pedestal.http.route.definition.table :as table]))

(def ^:private default-opts
  {:allow-head?     true
   :prefix          "/public"
   :route-namespace "io.pedestal.http.resources"
   :index-files?    true
   :allow-symlinks? false})


(defn- create-get-handler
  [response-supplier]
  (fn [request]
    (or (response-supplier request)
        {:status  404
         :headers {"Content-Type" "text/plain"}})))

(defn- create-head-handler
  [get-handler]
  (fn [request]
    (assoc (get-handler request) :body nil)))

(defn- valid-prefix?
  [s]
  (and (string? s)
       (not (string/ends-with? s "/"))))

(defn- create-resource-loader
  [resource-root class-loader]
  (assert (and (valid-prefix? resource-root)
               (not (string/starts-with? resource-root "/"))))
  (let [class-loader' (or class-loader
                          (.getContextClassLoader (Thread/currentThread)))
        opts          {:root   resource-root
                       :loader class-loader'}]
    (fn [path]
      (response/resource-response path opts))))

(defn- make-routes
  [resource-loader suffix opts]
  (let [{:keys [allow-head?
                prefix
                route-namespace
                index-files?]} opts
        _                 (assert (and (valid-prefix? prefix)
                                       (string/starts-with? prefix "/")))
        ;; The loader is responsible for checking that the resource in the path
        ;; exists, and is readable, etc. It returns nil on failure, a response map
        ;; on success.
        response-supplier (fn [request]
                            (let [{:keys [path]} (:path-params request)]
                              (resource-loader path)))
        route-path        (str prefix "/*path")
        get-handler       (create-get-handler response-supplier)
        route-name        (fn [prefix]
                            (keyword route-namespace
                                     (str prefix "-" suffix)))
        routes            (cond-> [[route-path
                                    :get
                                    get-handler
                                    :route-name (route-name "get")]]

                            index-files?
                            (conj [prefix
                                   :get
                                   get-handler
                                   :route-name (route-name "get-root")])

                            allow-head? (conj [route-path
                                               :head
                                               (create-head-handler get-handler)
                                               :route-name (route-name "head")])

                            (and allow-head? index-files?)
                            (conj [prefix
                                   :head
                                   (create-head-handler get-handler)
                                   :route-name (route-name "head-root")]))]
    (table/table-routes opts routes)))

(defn resource-routes
  "Returns a [[RoutingFragment]] of routes to access files on the classpath."
  [opts]
  (let [{:keys [resource-root
                class-loader] :as opts'} (-> (merge default-opts opts)
                                             ;; :index-files? only makes sense for file routes.
                                             (dissoc :index-files?))
        resource-loader (create-resource-loader resource-root class-loader)]
    (make-routes resource-loader "resource" opts')))

(defn- valid-root-path?
  [path]
  (and (string? path)
       (not (string/ends-with? path "/")))) []

(defn- create-file-loader
  [root-path opts]
  (let [root-dir (io/file root-path)
        _        (assert (and (.exists root-dir)
                              (.isDirectory root-dir)))
        opts'    (assoc opts :root root-path)]
    (fn [path]
      (response/file-response (or path "") opts'))))

(defn file-routes
  "Returns a [[RoutingFragment]] of routes to access files on the file system."
  [opts]
  (let [{:keys [file-root] :as opts'} (merge default-opts opts)
        _           (assert (valid-root-path? file-root))
        file-loader (create-file-loader file-root opts)]
    (make-routes file-loader "file" opts')))
