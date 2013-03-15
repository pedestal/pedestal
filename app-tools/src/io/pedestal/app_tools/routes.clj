; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.routes
  (:use [ring.middleware.cookies :only [wrap-cookies]]
        [ring.middleware.file :only [wrap-file]]
        [ring.middleware.file-info :only [wrap-file-info]]
        [ring.middleware.params :only [wrap-params]]
        [ring.util.response :only [file-response status redirect]]
        [compojure.core :only [routes ANY]]
        [io.pedestal.app-tools.host-page :only [add-control-panel]]
        [io.pedestal.app-tools.rendering-view.routes :only [rendering-view-routes]])
  (:require [io.pedestal.app-tools.build :as build]
            [io.pedestal.app-tools.middleware :as middleware]
            io.pedestal.app-tools.compile))

(defn maybe-redirect-to-service [handler config]
  (fn [request]
    (let [uri (:uri request)
          aspect (first (keep (fn [[k v]] (when (= (:uri request) (:uri v)) v))
                              (:aspects config)))
          {:keys [host port]} (get-in config [:application :api-server])]
      (if (:use-api-server? aspect)
        (redirect (str "http://" host ":" port uri))
        (handler request)))))

(defn dev-routes [config application-routes]
  (-> (routes
       application-routes
       (rendering-view-routes config)
       (ANY "*" request (-> (file-response "404.html" {:root build/*tools-public*})
                            (status 404))))
      (wrap-file build/*tools-public*)
      (wrap-file build/*public*)
      (maybe-redirect-to-service config)
      (build/wrap-build config)
      wrap-params
      wrap-file-info
      middleware/js-encoding
      (add-control-panel config)
      wrap-cookies
      middleware/wrap-stacktrace))
