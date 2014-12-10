(ns io.pedestal.http.impl.lazy-request)

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
  clojure.lang.IMapEntry
  (key [this] k)
  (val [this] (derefing-delays v))

  Object
  (getKey [this] k)
  (getValue [this] (derefing-delays v))
  ;; TODO: provide facilities for equiv a la MapEntry (which extends AMapEntry)

  clojure.lang.Seqable
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
  (touch [this] "Realize all portions of the underlying data structure. Returns this.")
  (realized [this] "Return fully-realized version of underlying data structure."))

(deftype LazyRequest [^clojure.lang.IPersistentMap m]

  clojure.lang.ILookup
  ;; Upon lookup, transparently deref delayed values.
  (valAt [this key]
    (let [val (get m key)]
      (derefing-delays val)))
  (valAt [this key not-found]
    (let [val (get m key ::not-found)]
      (if (= val ::not-found)
        not-found
        (derefing-delays val))))

  RawAccess
  (raw [this] m)

  LazyDatastructure
  (touch [this]
    (doseq [[k v] m]
      (derefing-delays v))
    this)
  (realized [this]
    (into {} this))

  clojure.lang.Associative
  (containsKey [this key]
    (contains? m key))
  (entryAt [this key]
    (get m key))
  (assoc [this key val]
    (LazyRequest. (assoc m key val)))

  clojure.lang.IFn
  (invoke [this k] (get this k))
  (invoke [this k default] (get this k default))

  clojure.lang.IPersistentMap
  (without [this key]
    (LazyRequest. (dissoc m key)))
  ;; TODO: No assocEx -- what does it used for?

  clojure.lang.Counted
  clojure.lang.IPersistentCollection
  (count [this] (count m))
  (cons [this o] (LazyRequest. (.cons m o)))
  (empty [this] (LazyRequest. {}))
  ;; Equality exists only between LazyRequest's with the same underlying map.
  ;; If you want deeper equality, use RawAccess's `raw` or `realized`
  (equiv [this o]
    (if (instance? LazyRequest o)
      (= m (raw o))
      false))

  clojure.lang.Seqable
  (seq [this]
    (map derefing-map-entry m))

  java.lang.Iterable
  ;; Quite similar to map's implementation, but turn `next`
  ;; MapEntry into a DerefingMapEntry
  (iterator [this]
    (let [it ^java.util.Iterator (.iterator ^java.lang.Iterable m)]
      (reify java.util.Iterator
        (hasNext [_] (.hasNext it))
        (next [_]    (derefing-map-entry (.next it)))
        (remove [_]  (throw (UnsupportedOperationException.)))))))

(defprotocol IntoLazyRequest
  (-lazy-request [t] "Create a lazy request"))

(extend-protocol IntoLazyRequest

  clojure.lang.IPersistentMap
  (-lazy-request [t] (->LazyRequest t))

  java.util.HashMap
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
  (-lazy-request m))

(defn classify-keys
 "Classify key-value pair based on whether its value is
 delayed, realized, or normal."
 [[k v]]
 (cond
   (not (delay? v))          :normal
   (and (delay? v)
        (realized? v))       :realized
   (and (delay? v)
        (not (realized? v))) :delayed))

;; Override LazyRequest printing to inhibit eager realiziation
;; of delayed keys
(defmethod print-method LazyRequest [lazy-request ^java.io.Writer w]
  (let [m                                 (raw lazy-request)
        {:keys [normal delayed realized]} (group-by classify-keys m)
        delayed-keys                      (map key delayed)
        normal-keys                       (map key normal)
        realized-keys                     (map key realized)
        normal-map                        (select-keys m normal-keys)
        realized-map                      (->> (select-keys m realized-keys)
                                               (map (fn [[k v]] [k (deref v)]))
                                               (into {}))
        all-realized                      (merge normal-map realized-map)]
  (.write w (str "#<LazyRequest delayed keys: "
                 (pr-str delayed-keys)
                 " realized map: "
                 (pr-str all-realized)
                 ">"))))

