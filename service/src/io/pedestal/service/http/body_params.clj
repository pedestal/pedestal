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
            [io.pedestal.service.interceptor :as interceptor
             :refer [definterceptorfn interceptor]]
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

(defn custom-edn-parser
  "Return an edn-parser fn that, given a request, will read the body of that
  request with `edn/read`. options are key-val pairs that will be passed as a
  hash-map to `edn/read`."
  [& options]
  (let [edn-options (merge {:eof nil}
                           (apply hash-map options))]
    (fn [request]
      (let [encoding (or (:character-encoding request) "UTF-8")]
        (assoc request
          :edn-params (->
                       (:body request)
                       (java.io.InputStreamReader. encoding)
                       java.io.PushbackReader.
                       (->> (edn/read edn-options))))))))

(def edn-parser
  "Take a request and parse its body as edn."
  (custom-edn-parser))

(defn custom-json-parser
  "Return a json-parser fn that, given a request, will read the body of that
  request with `json/read`. options are key-val pairs that will be passed along
  to `json/read`."
  [& options]
  (fn [request]
    (let [encoding (or (:character-encoding request) "UTF-8")]
      (assoc request
        :json-params
        (apply json/read
               (-> (:body request)
                   (java.io.InputStreamReader. encoding))
               options)))))

(def json-parser
  "Take a request and parse its body as json."
  (custom-json-parser))

(defn form-parser
  "Take a request and parse its body as a form."
  [request]
  (let [encoding (or (:character-encoding request) "UTF-8")]
    (params/assoc-form-params request encoding)))

(defn default-parser-map
  "Return a map of MIME-type to parsers. Included types are edn, json and
  form-encoding. parser-options are key-val pairs, valid options are:

    :edn-options A hash-map of options to be used when invoking `edn/read`.
    :json-options A hash-map of options to be used when invoking `json/read`.

  Examples:

  (default-parser-map :json-options {:key-fn keyword})
  ;; This parser-map would parse the json body '{\"foo\": \"bar\"}' as
  ;; {:foo \"bar\"}

  (default-parser-map :edn-options {:readers *data-readers*})
  ;; This parser-map will parse edn bodies using any custom edn readers you
  ;; define (in a data_readers.clj file, for example.)"
  [& parser-options]
  (let [{:keys [edn-options json-options]} (apply hash-map parser-options)
        edn-options-vec (apply concat edn-options)
        json-options-vec (apply concat json-options)]
    {#"^application/edn" (apply custom-edn-parser edn-options-vec)
     #"^application/json" (apply custom-json-parser json-options-vec)
     #"^application/x-www-form-urlencoded" form-parser}))

(definterceptorfn body-params
  ([] (body-params (default-parser-map)))
  ([parser-map]
     (interceptor :name ::body-params :enter
                  (fn [context]
                    (try (update-in context [:request] (partial parse-content-type parser-map))
                         (catch Exception e (assoc context :response {:status 400 :body nil :headers {}})))))))
