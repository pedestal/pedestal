(ns io.pedestal.app.tracking-map)

(defn context-meta [map key val]
  (with-meta val (update-in (meta map) [:context] (fnil conj []) key)))

(deftype TrackingMap [map]
  clojure.lang.IPersistentMap
  (assoc [this key val]
    (let [{:keys [context changed] :as md} (meta map)
          change (if (seq context)
                   (conj context key)
                   [key])
          md (dissoc md :context)
          md (if (get map key)
               (update-in md [:updated] (fnil conj #{}) change)
               (update-in md [:added] (fnil conj #{}) change))
          md (merge-with (comp set concat) md (meta val))]
      (TrackingMap. (with-meta (.assoc map key val) md))))
  (assocEx [this key val]
    (TrackingMap. (with-meta (.assocEx map key val)
                    (update-in (meta map) [:added] (fnil conj []) key))))
  (without [this key]
    (TrackingMap. (with-meta (.without map key)
                    (update-in (meta map) [:removed] (fnil conj []) key))))
  clojure.lang.ILookup
  (valAt [this key]
    (let [v (.valAt map key)]
      (cond (instance? TrackingMap v) (context-meta map key v)
            (map? v) (TrackingMap. (context-meta map key v))
            :else v)))
  (valAt [this key not-found]
    (if-let [v (.valAt map key)]
      v
      not-found))
  clojure.lang.IFn
  (invoke [this arg]
    (if (keyword? arg)
      (get map arg)
      (throw (IllegalArgumentException. "Only a keyword argument is supported."))))
  java.util.Map
  (clear [this]
    (.clear map))
  (containsKey [this key]
    (.containsKey map key))
  (containsValue [this val]
    (.containsValue map val))
  (entrySet [this]
    (.entrySet map))
  (equals [this m]
    (.equals map m))
  (get [this k]
    (.get map k))
  (hashCode [this]
    (.hashCode map))
  (isEmpty [this]
    (.isEmpty map))
  (keySet [this]
    (.keySet map))
  (put [this k v]
    (.put map k v))
  (putAll [this m]
    (.putAll map m))
  (remove [this k]
    (.remove map k))
  (size [this]
    (.size map))
  (values [this]
    (.values map))
  clojure.lang.Counted
  (count [this]
    (.count map))
  java.lang.Iterable
  (iterator [this]
    (.iterator map))
  clojure.lang.Seqable
  (seq [this]
    (seq map))
  clojure.lang.IObj
  (withMeta [this meta]
    (.withMeta map meta))
  clojure.lang.IMeta
  (meta [this]
    (.meta map)))

(defmethod clojure.core/print-method TrackingMap [tm writer]
  (pr (.map tm)))

(comment
  
  (meta (assoc (->TrackingMap {}) :a 1))
  (meta (assoc (->TrackingMap {:a 0}) :a 1))
  (meta (assoc-in (->TrackingMap {}) [:a :b :c] 1))
  (meta (assoc-in (->TrackingMap {:a {:b {}}}) [:a :b :c] 1))
  (meta (assoc-in (->TrackingMap {:a {:b {:c 3}}}) [:a :b :c] 1))
  
  (meta (update-in (->TrackingMap {:a {:b {:c 3}}}) [:a :b :c] inc))

  (-> (TrackingMap. {:a {}})
      (assoc :a {:b {}})
      (assoc :c 11)
      (assoc-in [:a :b :c] 10))

  (meta (-> (TrackingMap. {:a {}})
            (assoc :a {:b {}})
            (assoc :c 11)
            (assoc-in [:a :b :c] 10)))
  
  )

