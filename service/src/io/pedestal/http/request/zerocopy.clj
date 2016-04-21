(ns io.pedestal.http.request.zerocopy
  (:require [io.pedestal.http.request :as request])
  (:import (java.util Map)))


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

(deftype CallThroughRequest [base-request ;; This should be a ContainerRequest
                             ^clojure.lang.IPersistentMap user-data]

  clojure.lang.ILookup
  ;; Upon lookup, transparently dispatch to the container's request.
  (valAt [this k]
    (user-data k ((request/ring-dispatch k request/nil-fn) base-request)))
  (valAt [this k not-found]
    (let [v (user-data k ::not-found)]
      (if (= v ::not-found)
        (let [v (request/ring-dispatch k ::not-found)]
          (if (= v ::not-found)
            not-found
            (v base-request)))
        v)))

  request/ProxyDatastructure
  (realized [this]
    (persistent!
      (reduce-kv (fn [m k v]
                   (assoc! m k (v base-request)))
                 (transient user-data)
                 request/ring-dispatch)))

  clojure.lang.Associative
  (containsKey [this k]
    (or (contains? request/ring-dispatch k)
        (contains? user-data k)))
  (entryAt [this k]
    (or (.entryAt user-data k)
        (and (contains? request/ring-dispatch k)
             (clojure.lang.MapEntry. k ((request/ring-dispatch k) base-request)))
        nil))
  (assoc [this k v]
    (CallThroughRequest. base-request (assoc user-data k v)))

  clojure.lang.IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k default] (.valAt this k default))

  clojure.lang.IPersistentMap
  (without [this k]
    (dissoc (request/realized this) k))

  clojure.lang.Counted
  (count [this] (unchecked-add (if (= base-request nil) 0 (count request/ring-dispatch))
                               (count user-data)))

  clojure.lang.IPersistentCollection
  (cons [this o] (CallThroughRequest. base-request (.cons user-data o)))

  (empty [this] (CallThroughRequest. nil {}))

  ;; Equality is java.util.Map equality, against the fully realized map
  (equiv [this o]
    (.equals this o))

  clojure.lang.Seqable
  (seq [this]
    (seq (request/realized this)))

  java.lang.Iterable
  (iterator [this]
    (let [it ^java.util.Iterator (.iterator ^java.lang.Iterable (seq this))]
      (reify java.util.Iterator
        (hasNext [_] (.hasNext it))
        (next [_]    (.next it))
        (remove [_]  (throw (UnsupportedOperationException.))))))

  java.util.Map
  (containsValue [this v]
    (contains? (set (.values this)) v))
  (entrySet [this]
    (.entrySet (request/realized this)))
  ;; Equality is java.util.Map equality, against the fully realized map
  (equals [this o]
    (if (instance? Map o)
      (= (.entrySet this) (.entrySet o))
      false))
  (get [this k]
    (.valAt this k))
  (isEmpty [this]
    (and (= base-request nil) (empty? user-data)))
  (keySet [this]
    (.keySet (request/realized this)))
  (size [this]
    (.count this))
  (values [this]
    (vals (request/realized this)))
  (clear [this] (throw (UnsupportedOperationException.)))
  (put [this k v] (throw (UnsupportedOperationException.)))
  (putAll [this m] (throw (UnsupportedOperationException.)))
  (remove [this k] (throw (UnsupportedOperationException.))))

(defn call-through-request
  ([container-req]
   (->CallThroughRequest container-req {}))
  ([container-req override-map]
   (->CallThroughRequest container-req override-map)))

