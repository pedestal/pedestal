(ns io.pedestal.http.impl.zerocopy-request
  (:import (java.util Map)))


;; Containers optimize the data structure behind request objects,
;; for efficient time and space properties.
;; For Jetty, the base request information is stored in an array, with a
;; a tree of indexes for key pieces of data -- https://webtide.com/jetty-9-goes-fast-with-mechanical-sympathy/

;; This request type calls functions on lookup, which can close-over zero/low-copy datastructures.
;; No data is copied or cached, but all lookups involve an additional stackframe
;; (which may be optimized out by the JIT)
(defprotocol ProxyDatastructure
  (realized [this] "Return fully-realized version of underlying data structure."))
(extend-protocol ProxyDatastructure
  nil
  (realized [t] nil))

(defprotocol ContainerRequest
  (server-port [x])
  (server-name [x])
  (remote-addr [x])
  (uri [x])
  (query-string [x])
  (scheme [x])
  (request-method [x])
  (protocol [x])
  (headers [x])
  (ssl-client-cert [x])
  (body [x])
  (context [x])
  (path-info [x]))
(extend-protocol ContainerRequest
  nil
  (server-port [x] nil)
  (server-name [x] nil)
  (remote-addr [x] nil)
  (uri [x] nil)
  (query-string [x] nil)
  (scheme [x] nil)
  (request-method [x] nil)
  (protocol [x] nil)
  (headers [x] nil)
  (ssl-client-cert [x] nil)
  (body [x] nil)
  (context [x] nil)
  (path-info [x] nil))

(def ring-dispatch
  {:server-port server-port
   :server-name server-name
   :remote-addr remote-addr
   :uri uri
   :query-string query-string
   :scheme scheme
   :request-method request-method
   :headers headers
   :ssl-client-cert ssl-client-cert
   :body body})

(def nil-fn (constantly nil))


(deftype CallThroughRequest [base-request ;; This should be a ContainerRequest
                             ^clojure.lang.IPersistentMap user-data]

  clojure.lang.ILookup
  ;; Upon lookup, transparently deref delayed values.
  (valAt [this k]
    (user-data k ((ring-dispatch k nil-fn) base-request)))
  (valAt [this k not-found]
    (let [v (user-data k ::not-found)]
      (if (= v ::not-found)
        (let [v (ring-dispatch k ::not-found)]
          (if (= v ::not-found)
            not-found
            (v base-request)))
        v)))

  ProxyDatastructure
  (realized [this]
    (persistent!
      (reduce-kv (fn [m k v]
                   (assoc! m k (v base-request)))
                 (transient user-data)
                 ring-dispatch)))

  clojure.lang.Associative
  (containsKey [this k]
    (or (contains? ring-dispatch k)
        (contains? user-data k)))
  (entryAt [this k]
    (or (.entryAt user-data k)
        (and (contains? ring-dispatch k)
             (clojure.lang.MapEntry. k ((ring-dispatch k) base-request)))
        nil))
  (assoc [this k v]
    (CallThroughRequest. base-request (assoc user-data k v)))

  clojure.lang.IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k default] (.valAt this k default))

  clojure.lang.Counted
  (count [this] (unchecked-add (if (= base-request nil) 0 (count ring-dispatch))
                               (count user-data)))

  clojure.lang.IPersistentCollection
  (cons [this o] (CallThroughRequest. base-request (.cons user-data o)))

  (empty [this] (CallThroughRequest. nil {}))
  ;; Equality exists only between LazyRequest's with the same underlying map.
  ;; If you want deeper equality, use RawAccess's `raw` or `realized`
  (equiv [this o]
    (.equals this o))

  clojure.lang.Seqable
  (seq [this]
    (seq (realized this)))

  java.lang.Iterable
  ;; Quite similar to map's implementation, but turn `next`
  ;; MapEntry into a DerefingMapEntry
  (iterator [this]
    (let [it ^java.util.Iterator (.iterator ^java.lang.Iterable (seq this))]
      (reify java.util.Iterator
        (hasNext [_] (.hasNext it))
        (next [_]    (.next it))
        (remove [_]  (throw (UnsupportedOperationException.))))))

  java.util.Map
  (containsValue [this v]
    ((set (.values this)) v))
  (entrySet [this]
    (.entrySet (realized this)))
  (equals [this o]
    (if (instance? Map o)
      (= (.entrySet this) (.entrySet o))
      false))
  (get [this k]
    (.valAt this k))
  (isEmpty [this]
    (and (= base-request nil) (empty? user-data)))
  (keySet [this]
    (.keySet (realized this)))
  (size [this]
    (.count this))
  (values [this]
    (vals (realized this)))
  (clear [this] (throw (UnsupportedOperationException.)))
  (put [this k v] (throw (UnsupportedOperationException.)))
  (putAll [this m] (throw (UnsupportedOperationException.)))
  (remove [this k] (throw (UnsupportedOperationException.))))

(defn call-through-request
  ([req]
   (->CallThroughRequest req {}))
  ([req override-map]
   (->CallThroughRequest req override-map)))

