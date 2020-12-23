; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns template-server.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [ring.util.response :as ring-resp]
            [clojure.java.io :as io]
            [hiccup.page :as hiccup]
            [net.cgrand.enlive-html :as enlive :refer [deftemplate]]
            [clostache.parser :as clostache]
            [comb.template :as comb]
            [selmer.parser :as selmer]
            [clojure.string :as str]))

;; The home page is just a plain html page.
(defn home-page
  [request]
  (ring-resp/response
   (format "<html><body>%s<br/>%s</body></html>"
           "Each of the links below is rendered by a different templating library. Check them out:"
           (str "<ul>"
                (->> ["hiccup" "enlive" "clostache" "stringtemplate" "comb" "selmer"]
                     (map #(format "<li><a href='/%s'>%s</a></li>" % %))
                     (str/join ""))
                "</ul>"))))

(defn- title-as     [t] (format "Hello from %s!" t))
(defn- current-date []  (str (java.util.Date.)))

;; The /hiccup page uses hiccup.
;; https://github.com/weavejester/hiccup
(defn hiccup-page
  [request]
  (ring-resp/response (hiccup/html5
    [:body
      [:h1 {:id "the-title"} (title-as "Hiccup")]
      [:hr]
      [:p "This page was rendered with Hiccup!"]
      [:br]
      [:p {:id "the-text"}   "Hello from the Hiccup demo. Do you need a glass of water?"]
      [:p {:id "the-date"}   (current-date)]])))

;; The /enlive page uses enlive, plugging in
;; values for title and text. https://github.com/cgrand/enlive
(deftemplate enlive-template "public/enlive-template.html"
  [ctxt]
  [:h1] (enlive/content (:title ctxt))
  [:#the-text] (enlive/content (:text ctxt))
  [:#the-date] (enlive/content (:date ctxt)))

(defn enlive-page
  [request]
  (ring-resp/response
   (apply str
          (enlive-template {:title (title-as   "Enlive")
                            :text  "This is a special message from Enlive."
                            :date  (current-date)}))))

;; The /mustache page is done in (what else?) mustache.
;; https://github.com/fhd/clostache
(defn clostache-page
  [request]
  (ring-resp/response
    (clostache/render-resource "public/clostache-template.html"
                               {:title (title-as   "Clostache")
                                :text  "With Clostache, it's November every month."
                                :date  (current-date)})))

;; The /comb page is done with the very ERB/JSP-like comb
;; templating package. https://github.com/weavejester/comb
(defn comb-page
  [request]
  (let [template (slurp (io/resource "public/comb-template.html"))]
    (ring-resp/response
      (comb/eval template
                 {:title (title-as   "Comb")
                  :text  "You might be able to tame hairy markup with Comb."
                  :date  (current-date)}))))

;; The /stringtemplate page is done with the Java based String
;; template. http://www.stringtemplate.org
(defn stringtemplate-page
  [request]
  (let [template (org.stringtemplate.v4.ST. (slurp (io/resource "public/string-template.html")) \{ \})]
    (ring-resp/response (-> template
                            (.add "title" (title-as   "String Template"))
                            (.add "text"  "String Template might be useful when importing templates from Java.")
                            (.add "date"  (current-date))
                            (.render)))))

;; The /selmer page uses selmer.
;; https://github.com/yogthos/Selmer
(defn selmer-page
  [request]
  (ring-resp/response
   (selmer/render-file "public/selmer-template.html"
                               {:title (title-as   "Selmer")
                                :text  "Ha Ha! I'm Selmer."
                                :date  (current-date)})))

;; Define the routes that pull everything together.
(def routes
  `[[["/" {:get home-page} ^:interceptors [http/html-body]
      ["/hiccup" {:get hiccup-page}]
      ["/enlive" {:get enlive-page}]
      ["/clostache" {:get clostache-page}]
      ["/stringtemplate" {:get stringtemplate-page}]
      ["/comb" {:get comb-page}]
      ["/selmer" {:get selmer-page}]]]])

;; Consumed by template-server.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ::http/routes routes
              ::http/resource-path "/public"
              ::http/type :jetty
              ::http/port 8080})
