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

(ns io.pedestal.interceptor
  "Public API for creating interceptors, and various utility fns for
  common interceptor creation patterns."
  (:require [io.pedestal.impl.interceptor :as impl]))

(defrecord Interceptor [name enter leave error])

(defprotocol IntoInterceptor
  (-interceptor [t] "Given a value, produce an Interceptor Record."))

(declare interceptor)
(extend-protocol IntoInterceptor

  clojure.lang.IPersistentMap
  (-interceptor [t] (map->Interceptor t))

  ; This is the `handler` case
  clojure.lang.Fn
  (-interceptor [t]
    (let [int-meta (meta t)]
      ;; To some degree, support backwards compatibility
      (if (or (:interceptor int-meta)
              (:interceptorfn int-meta))
        (interceptor (t))
        (interceptor {:enter (fn [context]
                               (assoc context :response (t (:request context))))}))))

  clojure.lang.IPersistentList
  (-interceptor [t] (interceptor (eval t)))

  clojure.lang.Cons
  (-interceptor [t] (interceptor (eval t)))

  clojure.lang.Symbol
  (-interceptor [t] (interceptor (resolve t)))

  clojure.lang.Var
  (-interceptor [t] (interceptor (deref t)))

  Interceptor
  (-interceptor [t] t))

;; For prosterity, I'm going to keep the multimethod version handy as well
;
;(defmulti -interceptor class)
;
;(defmethod -interceptor clojure.lang.IPersistentMap [t]
;  (map->Interceptor t))
;
;(defmethod -interceptor clojure.lang.PersistentArrayMap [t]
;  (map->Interceptor t))
;
;(defmethod -interceptor clojure.lang.Fn [t]
;  (interceptor (t)))
;
;(defmethod -interceptor clojure.lang.IPersistentList [t]
;  (interceptor (eval t)))
;
;(defmethod -interceptor clojure.lang.PersistentList [t]
;  (interceptor (eval t)))
;
;(defmethod -interceptor clojure.lang.Cons [t]
;  (interceptor (eval t)))
;
;(defmethod -interceptor Interceptor [t]
;  t)

(defn interceptor-name
  [n]
  (if-not (or (nil? n) (keyword? n))
    (throw (ex-info "Name must be keyword or nil" {:name n}))
    n))

(defn interceptor?
  [o]
  (= (type o) Interceptor))

(defn valid-interceptor?
  [o]
  (if-let [int-vals (and (interceptor? o)
                           (vals (select-keys o [:enter :leave :error])))]
    (and (some identity int-vals)
         (every? fn? (remove nil? int-vals))
         (interceptor-name (:name o))
         true)
    false))

(defn interceptor
  "Given a value, produces and returns an Interceptor (Record)."
  [t]
  {:pre [(if-not (satisfies? IntoInterceptor t) ; For multi- ((.getMethodTable -interceptor) (class t))
           (throw (ex-info "You're trying to use something as an interceptor
                           that isn't supported by the protocol; Perhaps you need to extend it?"
                           {:t t
                            :type (type t)}))
           true)]
   :post [valid-interceptor?]}
  (-interceptor t))


