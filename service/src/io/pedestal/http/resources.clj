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
  "Expose file system or classpath resources as GET-able URIs.

  "
  {:added "0.8.0"}
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.response :as response]
            [io.pedestal.http.route.definition.table :as table]))


(def ^:private default-resource-opts
  {:allow-head?     true
   :prefix          "/public"
   :route-namespace "io.pedestal.http.resources"}

  )

(defn- get-handler
  [resource-accessor]
  (fn [request]
    (let [resource-url (resource-accessor request)
          response     (when resource-url
                         (response/url-response resource-url))]
      (or response
          {:status 404
           :headers {"Content-Type" "text/plain"}}))))

(defn- head-handler
  [resource-accessor]
  (let [do-get (get-handler resource-accessor)]
    (fn [request]
      #trace/result request
      (assoc (do-get request) :body nil))))

(defn- valid-prefix?
  [s]
  (and (string? s)
       (not (string/ends-with? s "/"))))

(defn- traversal?
  [path]
  (->> (string/split path #"/|\\")
       (some #(= ".." %))
       boolean))

(defn- create-resource-loader
  [resource-root class-loader]
  (let [class-loader' (or class-loader
                          (.getContextClassLoader (Thread/currentThread)))]
    (fn [path]
      (when-not (traversal? path)
        (io/resource (str resource-root "/" path) class-loader')))))

(defn resource-routes
  "Returns a [[RoutingFragment]] of routes to access files on the classpath."
  [opts]
  (let [{:keys [allow-head?
                resource-root
                prefix
                class-loader
                route-namespace]} (merge default-resource-opts opts)
        _                 (do
                            (assert (and (valid-prefix? resource-root)
                                         (not (string/starts-with? resource-root "/"))))
                            (assert (and (valid-prefix? prefix)
                                         (string/starts-with? prefix "/"))))
        resource-loader   (create-resource-loader resource-root class-loader)
        resource-accessor (fn [request]
                            (let [{:keys [path]} (:path-params request)]
                              (resource-loader path)))
        route-path        (str prefix "/*path")
        routes            (cond-> [[route-path
                                    :get
                                    (get-handler resource-accessor)
                                    :route-name (keyword route-namespace "get-resource")]]
                            allow-head? (conj [route-path
                                               :head
                                               (head-handler resource-accessor)
                                               :route-name (keyword route-namespace "head-resource")]))]
    (table/table-routes opts routes)))
