; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.request.zerocopy
  {:deprecated "0.7.0"}
  (:require [io.pedestal.http.request :as request]
            [io.pedestal.internal :as i])
  (:import (clojure.lang Associative Counted IFn ILookup IPersistentCollection IPersistentMap MapEntry Seqable)
           (java.util Iterator Map)))


;; Containers optimize the data structure behind request objects,
;; for efficient time and space properties.
;; For Jetty, the base request information is stored in an array, with a
;; a tree of indexes for key pieces of data -- https://webtide.com/jetty-9-goes-fast-with-mechanical-sympathy/

;; This request type calls functions on lookup, which can close-over zero/low-copy datastructures.
;; No data is copied or cached, but all lookups involve an additional stackframe
;; (which may be optimized out by the JIT)


;; This request doesn't copy any data from the container,
;; and proxies all Ring-like map lookups to the containers request data structure.
;; Care is given to uphold all value semantics.

(deftype CallThroughRequest [base-request                   ;; This should be a ContainerRequest
                             ^IPersistentMap user-data]

  ILookup
  ;; Upon lookup, transparently dispatch to the container's request.
  (valAt [_ k]
    (user-data k ((request/ring-dispatch k request/nil-fn) base-request)))
  (valAt [_ k not-found]
    (let [v (user-data k ::not-found)]
      (if (= v ::not-found)
        (let [v (request/ring-dispatch k ::not-found)]
          (if (= v ::not-found)
            not-found
            (v base-request)))
        v)))

  request/ProxyDatastructure

  (realized [_]
    (persistent!
      (reduce-kv (fn [m k v]
                   (assoc! m k (v base-request)))
                 (transient user-data)
                 request/ring-dispatch)))

  Associative

  (containsKey [_ k]
    (or (contains? request/ring-dispatch k)
        (contains? user-data k)))
  (entryAt [_ k]
    (or (.entryAt user-data k)
        (and (contains? request/ring-dispatch k)
             (MapEntry. k ((request/ring-dispatch k) base-request)))
        nil))
  (assoc [_ k v]
    (CallThroughRequest. base-request (assoc user-data k v)))

  IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k default] (.valAt this k default))

  IPersistentMap
  (without [this k]
    (dissoc (request/realized this) k))

  Counted
  (count [_] (unchecked-add (if (nil? base-request) 0 (count request/ring-dispatch))
                            (count user-data)))

  IPersistentCollection
  (cons [_ o] (CallThroughRequest. base-request (.cons user-data o)))

  (empty [_] (CallThroughRequest. nil {}))

  ;; Equality is java.util.Map equality, against the fully realized map
  (equiv [this o]
    (.equals this o))

  Seqable
  (seq [this]
    (seq (request/realized this)))

  Iterable
  (iterator [this]
    (let [it (.iterator ^Iterable (seq this))]
      (reify Iterator
        (hasNext [_] (.hasNext it))
        (next [_] (.next it))
        (remove [_] (throw (UnsupportedOperationException.))))))

  Map
  (containsValue [this v]
    (contains? (set (.values this)) v))
  (entrySet [this]
    (.entrySet ^Map (request/realized this)))
  ;; Equality is java.util.Map equality, against the fully realized map
  (equals [this o]
    (if (instance? Map o)
      (= (.entrySet ^Map this) (.entrySet ^Map o))
      false))
  (get [this k]
    (.valAt this k))
  (isEmpty [_]
    (and (nil? base-request) (empty? user-data)))
  (keySet [this]
    (.keySet ^Map (request/realized this)))
  (size [this]
    (.count this))
  (values [this]
    (vals (request/realized this)))
  (clear [_] (throw (UnsupportedOperationException.)))
  (put [_ _k _v] (throw (UnsupportedOperationException.)))
  (putAll [_ _m] (throw (UnsupportedOperationException.)))
  (remove [_ _k] (throw (UnsupportedOperationException.))))

(defn call-through-request
  ([container-req]
   (->CallThroughRequest container-req {}))
  ([container-req override-map]
   (i/deprecated `call-through-request
     (->CallThroughRequest container-req override-map))))

