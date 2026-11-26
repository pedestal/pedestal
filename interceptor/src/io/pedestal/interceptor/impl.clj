; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.interceptor.impl
  "Internal namespace subject to change at any time."
  {:added "0.8.0"}
  (:require clojure.core.async.impl.protocols
            [clojure.core.async :refer [go <!]]
            [clojure.string :as string]
            [clj-commons.format.exceptions :as exceptions])
  (:import (clojure.core.async.impl.protocols ReadPort)))

(defn channel?
  [c]
  (instance? ReadPort c))

(defn fn-name
  "Derives a keyword from the class name of function f: its namespace and the function name
  (prefixed by the names of any enclosing functions)."
  {:added "0.8.3"}
  [f]
  (let [class-name (-> f class .getName)
        [namespace-name & raw-function-ids] (string/split class-name #"\$")
        fn-name    (->> raw-function-ids
                        (map #(string/replace % #"__\d+" ""))
                        (map exceptions/demangle)
                        (string/join "/"))]
    (keyword (exceptions/demangle namespace-name)
             fn-name)))

(defn- class->derived-name
  "Like fn-name, but for a name that's only derived: the number in the name of a function
  created by eval (for example, a form at the top of a file or entered at the REPL), which changes
  from one run to the next, is dropped; and a class that isn't a Clojure function class (no $ in
  its name) is named by its package and simple name."
  [f]
  (let [class-name (-> f class .getName)]
    (if (string/includes? class-name "$")
      (let [n (fn-name f)]
        (keyword (namespace n)
                 (string/replace (name n) #"(^|/)eval\d+(?=/|$)" "$1eval")))
      (let [dot (string/last-index-of class-name ".")]
        (if dot
          (keyword (subs class-name 0 dot) (subs class-name (inc dot)))
          (keyword class-name))))))

(defn derive-name
  "Gives an interceptor that has no :name a name derived from the class of function f, and marks
  that name as derived (see [[explicit-name]]).  The mark is kept in the interceptor's metadata, as
  the derived name itself: it survives assoc, and a :name changed later (to any other value) is no
  longer considered derived."
  {:added "1.0.0"}
  [interceptor f]
  (let [n (class->derived-name f)]
    (-> interceptor
        (assoc :name n)
        (vary-meta assoc ::derived-name n))))

(defn derived-name?
  "Returns true if the interceptor's :name was derived automatically, rather than provided."
  {:added "1.0.0"}
  [interceptor]
  (let [n (:name interceptor)]
    (and (some? n)
         (= n (-> interceptor meta ::derived-name)))))

(defn explicit-name
  "Returns the interceptor's :name, or nil if the name was derived automatically.

  Route names are built from explicit names only, so that a derived name never becomes a route name."
  {:added "1.0.0"}
  [interceptor]
  (when-not (derived-name? interceptor)
    (:name interceptor)))

(defn rename
  "Sets the :name of an interceptor (as an explicit name), clearing any derived-name mark."
  {:added "1.0.0"}
  [interceptor new-name]
  (-> interceptor
      (assoc :name new-name)
      (vary-meta dissoc ::derived-name)))

(defn wrap-handler
  [handler-fn]
  (fn [context]
    (let [response (-> context :request handler-fn)]
      (if (channel? response)
        (go (assoc context :response (<! response)))
        (assoc context :response response)))))
