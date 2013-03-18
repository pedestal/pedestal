; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.messages
  (:require [clojure.set :as set])
  (:refer-clojure :exclude [type]))

(def topic
  "A keyword used as a key in a message to indicate that message's topic.

   Example Message:
   {msg/topic :todo, msg/type :clear-completed}"
  ::topic)

(def type
  "A keyword used as a key in a message to indicate that message's type.

   Example Message:
   {msg/topic :todo, msg/type :clear-completed}"
  ::type)

(def init
  "A special message type set when initializing a model. Whenever the
   type is init, a :value key will also be present and will contain
   the :init value from that topic in your app's dataflow.

   Example Message:
   {msg/topic :todo, msg/type msg/init, :value {}} "
  ::init)

(def app-model ::app-model)

(def ^:private param-ns
  "A namespace used as the namespace of message params."
  ;; Since CLJS doesn't give us *ns* we have to use a dummy keyword to
  ;; get our current namespace.
  (let [dummy-kw ::dummy]
    (str (namespace dummy-kw) ".param")))

(defn param
  "Return a keyword with name `kw` which can be used mark a missing
  value in a message.

  Example:
  (param :age)
  ; -> :io.pedestal.app.messages.param/age"
  [kw]
  (keyword param-ns (name kw)))

(defn add-message-type
  "Assoc message-type as type in a message, unless a type key is already present."
  [message-type message]
  (if (type message)
    message
    (assoc message type message-type)))

(defn- param-keyword-present?
  "Return if a key is both a symbol and is namespaced with param-namespace."
  [key]
  (and (keyword? key)
       (= param-ns (namespace key))))

(defn- strip-ns
  "Strip the namespace from keyword kw."
  [kw]
  (keyword (name kw)))

(defn- assert-only-param-keys
  "Assert every key of param-map is a param namespaced key."
  [param-map]
  (let [keys (keys param-map)]
    (assert
     (every? (fn [key] (param-keyword-present? key)) keys)
     (str "Every key of param-map must be a namespaced param keyword (see io.pedestal.app.messages/param). These keys are invalid: "
          (into [] (filter (fn [key] (not (param-keyword-present? key))) keys))))))

(defn- fill-params-msg
  "Replace parameter key-value pairs in a message with the appropriate values fromm param-map."
  [param-map msg]
  (into {} (map (fn [[k v :as original-pair]]
                  (if-let [param-val (k param-map)]
                    [(strip-ns k) param-val]
                    original-pair))
                msg)))

(defn fill-params
  "Replace parameter key-value pairs in messages with the appropriate values from param-map.

   Note: asserts that every key in param-map is a namespaced param key (see
   io.pedestal.app.messages/param).

   Example:
   (fill-params {(msg/param :foo) :bar} [{msg/topic :some-model (msg/param :foo) {}}])
   ; -> [{msg/topic :some-model :foo :bar}]"
  [param-map messages]
  (assert-only-param-keys param-map)
  (mapv (partial fill-params-msg param-map) messages))

(defn message-params
  "Return all distinct params present as keys in messages.

   Example:
   (message-params [{(msg/param :name) \"John\", (msg/param :age) 42}, {(msg/param :name) \"Joe\"}])
   ; -> ((msg/param :name) (msg/param :age))"
  [msgs]
  (distinct (for [msg msgs
                  key (keys msg)
                  :when (param-keyword-present? key)]
              key)))

(defn keys-to-param-keys
  "Return mp where every key has been turned into a param.

   Example:
   (keys-to-param-keys {:age 42, :name \"John\"})
   ; -> {(msg/param :age) 42, (msg/param :name) \"John\") "
  [mp]
  (into {} (map (fn [[k v]] [(param k) v]) mp)))

(defn fill
  "Populate each message in messages with the appropriate message-type and
   parameters from input-map (if provided).

   Note: asserts that input-map contains an entry for every param
   keyword in messages.

   Example:
   (fill :set-age
         [{msg/topic :person, :id person-id, (param :age) {}}]
         {:age 42})
   ; -> [{topic :person msg/type :set-age :age 42 :id person-id}]"
  ([message-type messages]
     (fill message-type messages {}))
  ([message-type messages input-map]
     (let [missing-keys (set (map keyword (map name (message-params messages))))
           input-keys (set (map keyword (keys input-map)))]
       (assert (empty? (set/difference missing-keys input-keys))
               (str "Missing keys " missing-keys " is not a subset of " input-keys "."))
       (let [messages (if message-type
                        (map (partial add-message-type message-type) messages)
                        messages)]
         (fill-params (keys-to-param-keys input-map) messages)))))
