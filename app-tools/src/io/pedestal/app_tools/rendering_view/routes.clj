; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.rendering-view.routes
  (:use [io.pedestal.app.templates :only [load-html construct-html render]])
  (:require [clojure.java.io :as io]
            [net.cgrand.enlive-html :as html]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptorfn]]
            [io.pedestal.app-tools.host-page :as host-page]
            [ring.util.response :as ring-response]
            [clojure.edn :as edn]))

(defn read-recording [config recording-name]
  (try (let [dir (-> config :built-in :render :dir)
             f (io/file (str "tools/recordings/" dir "/" recording-name ".clj"))]
         (if (.exists f)
           (edn/read-string (slurp f))
           {:error (str "No recording named '" recording-name
                        "' found in tools/recordings/" dir)}))
       (catch Throwable e {:error (.getMessage e)})))

(defn read-recording-menu [dir]
  (try (let [files (filter #(.endsWith (.getName %) ".clj")
                           (file-seq (io/file "tools/recordings" dir)))]
         (mapv #(:config (edn/read-string (slurp %)))
               files))
       (catch Throwable e {:error (.getMessage e)})))

(defn recording-menu-html [config]
  (let [recordings-dir (-> config :built-in :render :dir)]
    (assert recordings-dir "recordings-dir is required")
    (apply str
           (mapv
            #(let [uri (str "/_tools/render/recording?recording=" (name (:name %)))]
               (str "<tr>"
                   "<td>"
                   "<a href='" uri "'>" (:description %) "</a>"
                   "</td>"
                   "<td>"
                   "<a href='" uri "&mode=break'>break</a>"
                   "</td>"
                   "<td>"
                   "<a href='" uri "&mode=step'>step</a>"
                   "</td>"
                   "</tr>"))
            (sort-by :order (read-recording-menu recordings-dir))))))

(defn generate-render-menu-html [config]
  (let [dir (get-in config [:built-in :render :dir])]
    (html/html-snippet (str "<div class='page-header'>"
                            "<h1>Render <small>Step through UI recordings</small></h1>"
                            "</div>"
                            "<div class='section panel'>"
                            "<h2>Recordings</h2>"
                            "<div class='row'>"
                            "<div class='span6'>"
                            "<table class='table table-bordered'>"
                            (recording-menu-html config)
                            "</table>"
                            "</div>"
                            "</div>"
                            
                            "<div class='alert alert-info'>"
                            "<strong>Note:</strong>
                            The recordings listed above live in the <code>tools/recordings/" dir "</code>
                            directory as Clojure files. You may add, edit or remove these files
                            to change the information above."
                            "</div>"
                            "<div>"
                            
                            "</div>"))))

(definterceptorfn serve-render-menu
  [config]
  (interceptor/handler
   ::serve-render-menu
   (fn [req]
     (assert (-> config :built-in :render) "The render view is not configured")
     (let [template (or (-> config :built-in :render :menu-template)
                        (-> config :application :default-template))]
       (assert template "No template found in config")
       (-> (render (html/transform (construct-html (html/html-resource template))
                                [:body :div#content]
                                (html/append (generate-render-menu-html config))))
           ring-response/response
           (ring-response/content-type "text/html")
        )))))

(defn serve-recording-page
  [config]
  (interceptor/handler
   ::serve-recording-page
   (fn [req]
     (let [template (or (get-in config [:built-in :render :template])
                        (get-in config [:application :default-template]))
           js (get-in config [:application :generated-javascript])
           js-file (or (get-in config [:built-in :render :js-file])
                       (get-in config [:aspects :development :out-file]))
           renderer (get-in config [:built-in :render :renderer])
           log? (or (get-in config [:built-in :render :logging?]) false)
           scripts (concat [(host-page/script (html/set-attr :src (str "/" js "/out/goog/base.js")))
                            (host-page/script (html/set-attr :src (str "/" js "/" js-file)))]
                           (mapcat #(host-page/script (html/content %))
                                   ["goog.require('io.pedestal.app_tools.rendering_view.client');"
                                    (str "goog.require('" renderer "');")
                                    (str "io.pedestal.app_tools.rendering_view.client.main("
                                         renderer ".render_config()," log? ");")]))]
       (assert template "No template found in config")
       (-> (render (html/transform (construct-html (html/html-resource template))
                                [:body]
                                (apply html/append scripts)))
           ring-response/response
           (ring-response/content-type "text/html"))))))

(definterceptorfn serve-recording
  [config]
  (interceptor/handler
   ::serve-recording
   (fn [request]
     (let [recording-name (-> request :path-params :recording)]
       {:status 200
        :headers {"Content-Type" "application/edn"}
        :body (pr-str (read-recording config recording-name))}))))

(defn- format-output [recording-string]
  (let [data (edn/read-string recording-string)]
    (str "{:config " (pr-str (:config data)) "\n"
         " :data\n"
         " [\n"
         (apply str (mapv #(str "  " (pr-str %) "\n") (:data data)))
         " ]}")))

(definterceptorfn save-recording
  [config]
  (interceptor/handler
   ::save-recording
   (fn [request]
     (let [recording-name (get-in request [:path-params :recording])
           recording (format-output (slurp (io/reader (:body request))))
           dir (get-in config [:built-in :render :dir])]
       (assert recording-name "Recordings must have a name")
       (assert dir "No dir found in config under [:built-in :render :dir]")
       (let [f (io/file (str "tools/recordings/" dir "/" recording-name ".clj"))]
         (.mkdirs (.getParentFile f))
         (spit f recording)))
     {:status 200
      :headers {"Content-Type" "application/edn"}
      :body (pr-str {:status :ok})})))
