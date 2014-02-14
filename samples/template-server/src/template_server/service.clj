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

(ns template-server.service
  (:require [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as content-type]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp]
            [clojure.java.io :as io]
            [hiccup.page :as page]
            [net.cgrand.enlive-html :as html]
            [clostache.parser :as mustache]
            [comb.template :as comb]
            [clojure.string :as str]))

;; The home page is just a plain html page.
(defn home-page
  [request]
  (ring-resp/response
   (format "<html><body>%s<br/>%s</body></html>"
           "Each of the links below is rendered by a different templating library. Check them out:"
           (str "<ul>"
                (->> ["hiccup" "enlive" "mustache" "stringtemplate" "comb"]
                     (map #(format "<li><a href='/%s'>%s</a></li>" % %))
                     (str/join ""))
                "</ul>"))))

;; The /hiccup page is done with hiccup.
;; https://github.com/weavejester/hiccup

(defn hiccup-page
  [request]
  (ring-resp/response (page/html5 [:body [:p "Hello from Hiccup"]])))

;; The /enlive page is done with enlive, plugging in
;; values for title and text. https://github.com/cgrand/enlive

(html/deftemplate enlive-template "public/enlive-template.html"
  [ctxt]
  [:h1] (html/content (:title ctxt))
  [:#the-text] (html/content (:text ctxt))
  [:#the-date] (html/content (:date ctxt)))

(defn enlive-page
  [request]
  (ring-resp/response (apply str (enlive-template {:title "Enlive Demo Page"
                                              :text "Hello from the Enlive demo page. Have a nice day!"
                                              :date (str (java.util.Date.))}))))

;; The /mustache page is done in (what else?) mustache.
;; https://github.com/fhd/clostache

(defn mustache-page
  [request]
  (ring-resp/response (mustache/render-resource "public/mustache-template.html"
                                                 {:title "Mustache Demo Page"
                                                  :text "Hello from the Mustache demo page. Have a great day!"
                                                  :date (str (java.util.Date.))})))

;; The /stringtemplate page is done with the Java based String
;; template. http://www.stringtemplate.org

(def template-string
  "<html>
    <body>
      <h1>Hello from {name}</h1>
    </body>
  </html>")

(defn stringtemplate-page
  [request]
  (let [template (org.stringtemplate.v4.ST. template-string \{ \})]
    (ring-resp/response (-> template
                       (.add "name" "String Template")
                       (.render)))))

;; The /comb page is done with the very ERB/JSP-like comb
;; templating package. https://github.com/weavejester/comb

(defn comb-page
  [request]
  (ring-resp/response
   (comb/eval (slurp (io/resource "public/comb.html")) {:name "erb"})))

;; Define the routes that pull everything together.

(defroutes routes
  [[["/" {:get home-page} ^:interceptors [bootstrap/html-body]
     ["/hiccup" {:get hiccup-page}]
     ["/enlive"  {:get enlive-page}]
     ["/mustache"  {:get mustache-page}]
     ["/stringtemplate"  {:get stringtemplate-page}]
     ["/comb" {:get comb-page}]]]])

;; You can use this fn or a per-request fn via io.pedestal.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by template-server.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes
              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;; Choose from [:jetty :tomcat].
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})

