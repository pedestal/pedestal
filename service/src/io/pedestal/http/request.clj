(ns io.pedestal.http.request)

(defn- derefing-delays [v]
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

  ;; TODO: Seqable for first/second?
  )

(defn derefing-map-entry
  "Create a new MapEntry-like object that derefs any values
  that are delays."
  ([kv]
   (DerefingMapEntry. (key kv) (val kv)))
  ([k v]
   (DerefingMapEntry. k v)))

(defprotocol RawAccess
  "Utilities for exposing raw access to advanced data
  structures that layer new semantics onto simpler types."
  (raw [this] "Return the raw data structure underlying a more advanced wrapper")
  (touch [this] "Realize all delays, returning this"))

(deftype LazyRequest [m]

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
  (touch [this]
    (doseq [[k v] m]
      (derefing-delays v))
    this)

  clojure.lang.Associative
  (containsKey [this key]
    (contains? m key))
  (entryAt [this key]
    (get m key))
  (assoc [this key val]
    (->LazyRequest (assoc m key val)))

  clojure.lang.IPersistentMap
  (without [this key]
    (->LazyRequest (dissoc m key)))
  ;; TODO: No assocEx -- what does it used for?

  clojure.lang.Counted
  clojure.lang.IPersistentCollection
  (count [this] (count m))
  (empty [this] (->LazyRequest {}))
  (equiv [this o] (= m o)) ;; TODO: Do we require deeper semantics?

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

(comment
  (get lr :a)
  (get lr :b :c)
  (assoc lr :c :d))

