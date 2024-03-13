; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.
(ns io.pedestal.http.request.lazy
  {:deprecated "0.7.0"}
  (:require [io.pedestal.http.request :as request]
            [io.pedestal.internal :as i])
  (:import (clojure.lang Associative Counted IFn ILookup IMapEntry IPersistentCollection IPersistentMap Seqable)
           (java.util HashMap Iterator)))

;; TODO: Consider wrapping everything in a delay on entry to the map
(defn- derefing-delays
  "For values that are delays, return the derefed value, otherwise return the
  original value."
  [v]
  (if (delay? v)
    (deref v)
    v))

;; MapEntry implementation that deref's values that are delays.
(deftype DerefingMapEntry [k v]
  IMapEntry
  (key [_] k)
  (val [_] (derefing-delays v))

  Object
  (getKey [_] k)
  (getValue [_] (derefing-delays v))
  ;; TODO: provide facilities for equiv a la MapEntry (which extends AMapEntry)

  Seqable
  (seq [this] (lazy-seq [(key this) (val this)])))

(defn derefing-map-entry
  "Create a new MapEntry-like object, but allow for values to be transparently
  derefed when accessed.

  Does not provide the same level of 'equivalency' checking that MapEntry does.
  Use 'seq' to get a realized pair of key-value."
  ([kv]
   (DerefingMapEntry. (key kv) (val kv)))
  ([k v]
   (DerefingMapEntry. k v)))

(defprotocol RawAccess
  "Utilities for exposing raw access to advanced data
  structures that layer new semantics onto simpler types."
  (raw [this] "Return the raw data structure underlying a more advanced wrapper"))

(defprotocol LazyDatastructure
  "Utilities for manipulating/realizing lazy data structures."
  (touch [this] "Realize all portions of the underlying data structure. Returns this."))

(deftype LazyRequest [^IPersistentMap m]

  ILookup
  ;; Upon lookup, transparently deref delayed values.
  (valAt [_ key]
    (let [val (get m key)]
      (derefing-delays val)))
  (valAt [_ key not-found]
    (let [val (get m key ::not-found)]
      (if (= val ::not-found)
        not-found
        (derefing-delays val))))

  RawAccess
  (raw [_] m)

  LazyDatastructure
  (touch [this]
    (doseq [[_k v] m]
      (derefing-delays v))
    this)

  request/ProxyDatastructure
  (realized [this]
    (into {} this))

  Associative
  (containsKey [_ key]
    (contains? m key))
  (entryAt [_ key]
    (get m key))
  (assoc [_ key val]
    (LazyRequest. (assoc m key val)))

  IFn
  (invoke [this k] (get this k))
  (invoke [this k default] (get this k default))

  IPersistentMap
  (without [_ key]
    (LazyRequest. (dissoc m key)))
  ;; TODO: No assocEx -- what does it used for?

  Counted
  IPersistentCollection
  (count [_] (count m))
  (cons [_ o] (LazyRequest. (.cons m o)))
  (empty [_] (LazyRequest. {}))
  ;; Equality exists only between LazyRequest's with the same underlying map.
  ;; If you want deeper equality, use RawAccess's `raw` or `realized`
  (equiv [_ o]
    (if (instance? LazyRequest o)
      (= m (raw o))
      false))

  Seqable
  (seq [_]
    (map derefing-map-entry m))

  Iterable
  ;; Quite similar to map's implementation, but turn `next`
  ;; MapEntry into a DerefingMapEntry
  (iterator [_]
    (let [it (.iterator ^Iterable m)]
      (reify Iterator
        (hasNext [_] (.hasNext it))
        (next [_] (derefing-map-entry (.next it)))
        (remove [_] (throw (UnsupportedOperationException.)))))))

(defprotocol IntoLazyRequest
  (-lazy-request [_] "Create a lazy request"))

(extend-protocol IntoLazyRequest

  IPersistentMap
  (-lazy-request [t] (->LazyRequest t))

  HashMap
  (-lazy-request [t] (->LazyRequest (into {} t))))

(defn lazy-request
  "Return a LazyRequest map that transparently derefs values that are delays.

  Example:
  (:foo (lazy-request {:foo (delay :bar)}))
  ;; => :bar

  LazyRequest's are value-equal to other LazyRequest's that share the same
  underlying map, but not to raw maps. Use `raw` or `realized` to return plain
  maps of original key-vals or realized key-vals, respectively."
  [m]
  (i/deprecated `lazy-request
    (-lazy-request m)))

(defn classify-keys
  "Classify key-value pair based on whether its value is
  delayed, realized, or normal."
  [[_k v]]
  (cond
    (not (delay? v)) :normal
    (and (delay? v)
         (realized? v)) :realized
    (and (delay? v)
         (not (realized? v))) :delayed))

;; Override LazyRequest printing to inhibit eager realiziation
;; of delayed keys
(defmethod print-method LazyRequest [lazy-request ^java.io.Writer w]
  (let [m             (raw lazy-request)
        {:keys [normal delayed realized]} (group-by classify-keys m)
        delayed-keys  (map key delayed)
        normal-keys   (map key normal)
        realized-keys (map key realized)
        normal-map    (select-keys m normal-keys)
        realized-map  (->> (select-keys m realized-keys)
                           (map (fn [[k v]] [k (deref v)]))
                           (into {}))
        all-realized  (merge normal-map realized-map)]
    (.write w (str "#<LazyRequest delayed keys: "
                   (pr-str delayed-keys)
                   " realized map: "
                   (pr-str all-realized)
                   ">"))))

