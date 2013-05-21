; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.host-page
  "Functions to create an HTML page that hosts a ClojureScript
  application."
  (:use [io.pedestal.app.templates :only [load-html construct-html render html-parse]])
  (:require [net.cgrand.enlive-html :as html]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptorfn]])
  (:import java.io.File))

(def ^:private script-snippet
  (html/html-snippet "<script type='text/javascript'></script>"))

(defn script
  [f]
  (html/transform script-snippet [:script] f))

(defn- application-view
  [config aspect & scripts]
  (let [template (or (:template aspect)
                     (get-in config [:application :default-template]))]
    (html/transform (construct-html (html/html-resource template))
                    [:body]
                    (apply html/append scripts))))

(defn goog-base-required? [aspect]
  (not (#{:simple :advanced} (:optimizations aspect))))

(defn built-ins [config]
  (mapv (fn [[k v]]
          (case k
            :render {:name "Render"
                     :uri "/_tools/render"
                     :order (:order v)}
            nil))
        (:built-in config)))

(defn generate-control-panel [config environment]
  (html/html-snippet
   (str "<div id='pedestal-toolbar'
                  style='opacity:0'
                  onmouseover='this.style.opacity=0.75;'
                  onmouseout='this.style.opacity=0;'>"
        (apply str
               (map (fn [v]
                      (when-let [uri (:uri v)]
                        (let [url (if (:params v) (str uri "?" (:params v)) uri)]
                          (str "<a href='" url "'>" (:name v) "</a>"))))
                    (sort-by :order
                             (concat (vals (:control-panel config))
                                     (built-ins config)
                                     (vals (:aspects config))))))
        "</div>"
        "<div id='pedestal-status-panel' style='opacity:0'></div>")))

(defn- environment [config uri]
  (first (keep (fn [[k v]] (when (= uri (:uri v)) k))
               (:aspects config))))

(defn- append-control-panel [page-html config environment]
  (render (html/transform (html-parse page-html)
                          [:body]
                          (html/append (generate-control-panel config environment)))))

(definterceptorfn add-control-panel
  "Pedestal interceptor which adds a control panel to a page."
  [config]
  (interceptor/after
   ::add-control-panel
   (fn [{{:keys [headers body status] :as response} :response
         {:keys [uri]} :request
         :as context}]
     (if (and (= status 200)
              (or (= uri "/")
                  (and (.startsWith uri "/design") (.endsWith uri ".html"))
                  (or (= uri "/_tools/render") (= uri "/_tools/render/recording"))
                  (contains? (set (keep :uri (vals (:aspects config)))) uri)))
       (let [environment (environment config uri)
             aspect (get-in config [:aspects environment])
             body (if (= (type body) File) (slurp body) body)
             new-body (append-control-panel body config environment)
             new-content-length (str (count (.getBytes new-body)))]
         (-> context
             (update-in [:response :body]
                        (constantly new-body))
             (update-in [:response :headers "Content-Length"]
                        (constantly new-content-length))))
       context))))

(defn- add-recording [s aspect]
  (if (:recording? aspect)
    (-> s
        (update-in [:requires] #(assoc % "goog.require('io.pedestal.app_tools.tooling');" (count %)))
        (update-in [:start] #(str "io.pedestal.app_tools.tooling.add_recording(" % ")")))
    s))

(defn- add-logging [s aspect]
  (if (:logging? aspect)
    (-> s
        (update-in [:requires] #(assoc % "goog.require('io.pedestal.app_tools.tooling');" (count %)))
        (update-in [:start] #(str "io.pedestal.app_tools.tooling.add_logging(" % ")")))
    s))

(defn- get-scripts [aspect]
  (if (:scripts aspect)
    (:scripts aspect)
    (let [main (:main aspect)]
      (assert main "Config must have :main or :scripts")
      (let [script (-> {:requires (if (goog-base-required? aspect)
                                    {(str "goog.require('" main "');") 0}
                                    {})
                        :start (str main ".main()")}
                       (add-recording aspect)
                       (add-logging aspect))]
        (conj (vec (map first (sort-by second (:requires script))))
              (str (:start script) ";"))))))

(defn- js-path [base file]
  (str "/" base "/" file))

(defn application-host
  "Given a configuration map and an environment, return HTML (as a
  string) that can host a ClojureScript application. The environment
  must be either `:development` or `:production` - any other value results
  in an exception. The generated HTML is based on the contents of
  application.html, which is loaded as an Enlive resource.

  In production mode, the HTML (as a sequence of Enlive nodes) is
  transformed via the `:prod-transform` function from the config map.

  This function is normally called in two situations:

  1. From a Ring application to dynamically generate the application
     HTML.

  2. From the build script to create static deployment artifacts."
  [config environment]
  (let [aspect (get-in config [:aspects environment])
        transform (or (:transform aspect) identity)
        base (get-in config [:application :generated-javascript])
        scripts (cons (script (html/set-attr :src (js-path base (:out-file aspect))))
                      (mapcat #(script (html/content %)) (get-scripts aspect)))
        scripts (if (goog-base-required? aspect)
                  (cons (script (html/set-attr :src (js-path base "out/goog/base.js")))
                        scripts)
                  scripts)]
    (render (transform (apply application-view config aspect scripts)))))
