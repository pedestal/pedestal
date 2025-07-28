; Copyright 2023-2025 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.body-params
  "Provides interceptors and support for parsing the body of a request, generally according to
  the content type header.  This results in new keys on the request map, depending on the type
  of data parsed."
  (:require [clojure.edn :as edn]
            [charred.api :as json]
            [io.pedestal.http.params :as pedestal-params]
            [io.pedestal.interceptor :refer [interceptor]]
            [cognitect.transit :as transit]
            [io.pedestal.internal :refer [deprecated]]
            [ring.middleware.params :as params])
  (:import (java.io EOFException InputStream InputStreamReader PushbackReader)
           (java.util.regex Pattern)))

(defn- search-for-parser
  [parser-map content-type]
  (reduce-kv (fn [_ k v]
               (when (re-find k content-type)
                 (reduced v)))
             nil
             parser-map))

(defn- parser-for
  "Find a parser for the given content-type, never returns nil"
  [parser-map content-type]
  (or
    (when content-type
      (search-for-parser parser-map content-type))
    identity))


(defn- parse-content-type
  "Runs the request through the appropriate parser.  Returns the request unchanged if the :body
  is nil (or absent)."
  [parser-map request]
  (if (-> request :body some?)
    (let [parser-fn (parser-for parser-map (:content-type request))]
      (parser-fn request))
    request))

(defn add-parser
  "Adds a parser to the parser map; content type can either be a string (to exactly match the content type),
  or a regular expression.

  The parser-fn is passed the request map and returns a modified request map with an appropriate key and parsed value.
  For example, the default JSON parser adds a :json-params key."
  [parser-map content-type parser-fn]
  (let [content-pattern (if (instance? Pattern content-type)
                          content-type
                          (re-pattern (str "^" (Pattern/quote content-type) "$")))]
    (assoc parser-map content-pattern parser-fn)))

(defn custom-edn-parser
  "Return an edn-parser fn that, given a request, will read the body of that
  request with `edn/read`. options are key-val pairs that will be passed as a
  hash-map to `edn/read`."
  [& options]
  (let [edn-options (merge {:eof nil}
                           (apply hash-map options))]
    (fn [request]
      (let [encoding     (or (:character-encoding request) "UTF-8")
            input-stream (InputStreamReader.
                           ^InputStream (:body request)
                           ^String encoding)]
        (assoc request
               :edn-params (->
                             input-stream
                             PushbackReader.
                             (->> (edn/read edn-options))))))))

(defn custom-json-parser
  "Return a function that, given a request, will read the body of request
  using a JSON parser via charred.api/read-json. Provided options are merged onto
  defaults:

  - :key-fn - keyword
  - :eof-error? - false
  - :eof-value - nil

  The deprecated :array-coerce-fn is supported in 0.8.0, but overrides the :value-fn
  option, if provided."
  [& {:as options}]
  (let [{:keys [array-coerce-fn]
         :as   full-options} (merge {:key-fn     keyword
                                     :eof-error? false
                                     :eof-value  nil} options)
        value-fn (when array-coerce-fn
                   (deprecated ::array-coerce-fn
                     :noun ":array-coerce-fn option to io.pedestal.http.body-params/custom-json-parser")
                   (fn [k v]
                     (into (array-coerce-fn k) v)))
        options' (cond-> full-options
                   value-fn (assoc :value-fn value-fn))]
    (fn [request]
      (let [encoding (or (:character-encoding request) "UTF-8")]
        (assoc request
               :json-params
               (json/read-json
                 (InputStreamReader.
                   ^InputStream (:body request)
                   ^String encoding)
                 options'))))))

(defn custom-transit-parser
  "Return a transit-parser fn that, given a request, will read the
  body of that request with `transit/read`. options is a sequence to
  pass to transit/reader along with the body of the request."
  [& options]
  (fn [{:keys [body] :as request}]
    ;; Alas, exceptions are a poor form of flow control, but
    ;; the prior check, via InputStream/available, was not always accurate
    ;; (see https://github.com/pedestal/pedestal/issues/764).
    ;; Fortunately, this code is only invoked when the request's content type
    ;; identifies transit and it should be quite rare for a client to do so and
    ;; provide an empty body.
    (let [transit-params (try
                           (transit/read (apply transit/reader body options))
                           ;; com.cognitect.transit.impl.ReaderFactory/read catches
                           ;; the EOFException and rethrows it, wrapped in a RuntimeException.
                           (catch RuntimeException e
                             (when-not (some->> e ex-cause (instance? EOFException))
                               (throw e))

                             nil))]
      (cond-> request
        transit-params (assoc :transit-params transit-params)))))


(defn form-parser
  "Take a request and parse its body as a form."
  [request]
  (let [encoding (or (:character-encoding request) "UTF-8")
        request  (params/assoc-form-params request encoding)]
    (update request :form-params pedestal-params/keywordize-keys)))

(defn default-parser-map
  "Return a map of MIME-type to parsers. Included types are edn, json and
  form-encoding. parser-options are key-val pairs, valid options are:

    :edn-options A hash-map of options to be used when invoking `edn/read`.
    :json-options A hash-map of options to be used when invoking `json/parse-stream`.
    :transit-options A vector of options to be used when invoking `transit/reader` - must apply to both json and msgpack

  Examples:

  (default-parser-map :json-options {:key-fn keyword})
  ;; This parser-map would parse the json body '{\"foo\": \"bar\"}' as
  ;; {:foo \"bar\"}

  (default-parser-map :edn-options {:readers *data-readers*})
  ;; This parser-map will parse edn bodies using any custom edn readers you
  ;; define (in a data_readers.clj file, for example.)

  (default-parser-map :transit-options [{:handlers {\"custom/model\" custom-model-read-handler}}])
  ;; This parser-map will parse the transit body using a handler defined by
  ;; custom-model-read-handler."
  [& parser-options]
  (let [{:keys [edn-options json-options transit-options]} (apply hash-map parser-options)
        edn-options-vec  (apply concat edn-options)
        json-options-vec (apply concat json-options)]
    (-> {}
        (add-parser #"^application/edn" (apply custom-edn-parser edn-options-vec))
        (add-parser #"^application/json" (apply custom-json-parser json-options-vec))
        (add-parser #"^application/x-www-form-urlencoded" form-parser)
        (add-parser #"^application/transit\+json" (apply custom-transit-parser :json transit-options))
        (add-parser #"^application/transit\+msgpack" (apply custom-transit-parser :msgpack transit-options)))))

(defn body-params
  "Returns an interceptor that will parse the body of the request according to the content type.
  The normal rules are provided by [[default-parser-map]] which maps a regular expression identifying a content type
  to a parsing function for that type.

  Parameters parsed from the body are added as a new key to the request map dependending on
  which parser does the work; for the default parsers, one of the following will be used:

  - :json-params
  - :edn-params
  - :form-params
  - :transit-params

  When the body is absent (or nil), then the request is not changed (no new keys are added, no
  parsing is attempted)."
  ([] (body-params (default-parser-map)))
  ([parser-map]
   (interceptor
     {:name  ::body-params
      :enter (fn [context]
               (update context :request #(parse-content-type parser-map %)))})))

