; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http.body-params
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptorfn]]
            [io.pedestal.service.log :as log]
            [ring.middleware.params :as params]))

(defn- parser-for
  "Find a parser for the given content-type, never returns nil"
  [parser-map content-type]
  (or (when content-type
        (parser-map (some #(when (re-find % content-type) %) (keys parser-map))))
      identity))

(defn- parse-content-type
  "Runs the request through the appropriate parser"
  [parser-map request]
  ((parser-for parser-map (:content-type request)) request))

(defn- set-content-type
  "Changes the content type of a request"
  [request content-type]
  (-> request
      (assoc :content-type content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(defn- convert-middleware
  "Turn a ring middleware into a parser. If a content type is given, return a parser
   that will ensure that the handler sees that content type in the request"
  ([wrap-fn] (fn [request] (wrap-fn identity)))
  ([wrap-fn expected-content-type]
      (let [parser (wrap-fn identity)]
        (fn [request]
          (let [retyped-request (set-content-type request expected-content-type)
                parsed-request (parser retyped-request)]
            (set-content-type parsed-request (:content-type request)))))))

(defn add-parser
  [parser-map content-type parser-fn]
  (assoc parser-map (re-pattern (str "^" content-type "$") parser-fn)))

(defn add-ring-middleware
  [parser-map content-type middleware]
  (add-parser parser-map content-type (convert-middleware middleware)))

(defn edn-parser [request]
  (let [encoding (or (:character-encoding request) "UTF-8")]
    (assoc request
      :edn-params (->
                   (:body request)
                   (java.io.InputStreamReader. encoding)
                   java.io.PushbackReader.
                   (->> (edn/read {:eof nil}))))))

(defn json-parser [request]
  (let [encoding (or (:character-encoding request) "UTF-8")]
    (assoc request
      :json-params (->
                    (:body request)
                    (java.io.InputStreamReader. encoding)
                    json/read))))

(defn form-parser [request]
  (let [encoding (or (:character-encoding request) "UTF-8")]
    (params/assoc-form-params request encoding)))

(defn default-parser-map
  []
  {#"^application/edn" edn-parser
   #"^application/json" json-parser
   #"^application/x-www-form-urlencoded" form-parser})

(definterceptorfn body-params
  ([] (body-params (default-parser-map)))
  ([parser-map]
     (interceptor/on-request ::body-params
                             (fn [request] (parse-content-type parser-map request)))))



