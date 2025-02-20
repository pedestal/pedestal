; Copyright 2023-2024 Nubank NA
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

(ns io.pedestal.interceptor
  "Public API for creating interceptors, and various utility fns for
  common interceptor creation patterns."
  (:require [clojure.string :as string]
            [io.pedestal.internal :as i]
            [clj-commons.format.exceptions :as exceptions])
  (:import (clojure.lang Cons Fn IPersistentList IPersistentMap Symbol Var)
           (java.io Writer)))

(defrecord Interceptor [name enter leave error])

(defmethod print-method Interceptor
  [^Interceptor i ^Writer w]
  (.write w (if-let [n (.name i)]
              (str "#Interceptor{:name " (pr-str n) "}")
              "#Interceptor{}")))

(defn- default-handler-name
  [f]
  (let [class-name (-> f class .getName)
        [namespace-name & raw-function-ids] (string/split class-name #"\$")
        fn-name    (->> raw-function-ids
                        (map #(string/replace % #"__\d+" ""))
                        (map exceptions/demangle)
                        (string/join "/"))]
    (keyword (exceptions/demangle namespace-name)
             fn-name)))

(defn- fn->interceptor
  [f]
  (let [m                (meta f)
        interceptor-name (or (:name m)
                             (default-handler-name f))]
    {:name  interceptor-name
     :enter (fn [context]
              (assoc context :response (f (:request context))))}))

(defprotocol IntoInterceptor

  "Conversion into Interceptor, ready for execution as part of an interceptor chain."

  (-interceptor [t] "Given a value, produce an Interceptor Record."))

(extend-protocol IntoInterceptor

  IPersistentMap
  (-interceptor [m] (map->Interceptor m))

  ; This is the `handler` case
  Fn
  (-interceptor [f]
    (-interceptor (fn->interceptor f)))

  IPersistentList
  (-interceptor [l]
    ^{:noun "conversion of expressions to interceptors"
      :in   "0.7.0"}
    (i/deprecated ::expression
      (-interceptor (eval l))))

  Cons
  (-interceptor [c]
    ^{:noun "conversion of expressions to interceptors"
      :in   "0.7.0"}
    (i/deprecated ::expression
      (-interceptor (eval c))))

  Symbol
  (-interceptor [sym] (-interceptor (resolve sym)))

  Var
  (-interceptor [v] (-interceptor (deref v)))

  Interceptor
  (-interceptor [interceptor] interceptor))

(defn interceptor-name
  "Ensures that an interceptor name (to eventually be the :name key of an Interceptor)
  is either a keyword or nil.  Generally, interceptor names should be namespace-qualified keywords."
  [n]
  (if-not (or (nil? n) (keyword? n))
    (throw (ex-info (str "Name must be keyword or nil; Got: " (pr-str n)) {:name n}))
    n))

(defn interceptor?
  "Returns true if object o is an instance of the Interceptor record; the result of
  invoking [[interceptor]]."
  [o]
  (= (type o) Interceptor))

(defn valid-interceptor?
  [o]
  (if-let [int-vals (and (interceptor? o)
                         (vals (select-keys o [:enter :leave :error])))]
    (and (some identity int-vals)
         (every? fn? (remove nil? int-vals))
         (or (interceptor-name (:name o)) true)             ;; Could return `nil`
         true)
    false))

(defn- anon-deprecated
  [interceptor]
  (when-not (:name interceptor)
    ^{:in   "0.8.0"
      :noun "anonymous (unamed) interceptors"} (i/deprecated ::anon-interceptor))
  true)

(defn interceptor
  "Given a value, produces and returns an Interceptor (Record)."
  [t]
  {:pre  [(if-not (satisfies? IntoInterceptor t)
            (throw (ex-info "You're trying to use something as an interceptor that isn't supported by the protocol; Perhaps you need to extend it?"
                            {:t    t
                             :type (type t)}))
            true)]
   :post [(valid-interceptor? %)
          (anon-deprecated %)]}
  (-interceptor t))

