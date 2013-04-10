(ns io.pedestal.app.data.tracking-map)

(declare changes)

(deftype TrackingMap [map change-map]
  
  clojure.lang.IPersistentMap
  (assoc [this key val]
    (let [{:keys [context updated] :as cs} change-map
          change (if (seq context)
                   (conj context key)
                   [key])
          cs (dissoc cs :context)
          cs (if (get map key)
               (update-in cs [:updated] (fnil conj #{}) change)
               (update-in cs [:added] (fnil conj #{}) change))
          cs (merge-with (comp set concat) cs (dissoc (changes val) :context))]
      (TrackingMap. (.assoc map key val) cs)))
  (assocEx [this key val]
    (TrackingMap. (.assocEx map key val)
                  (update-in change-map [:added] (fnil conj []) key)))
  (without [this key]
    (TrackingMap. (.without map key)
                  (update-in change-map [:removed] (fnil conj []) key)))
  
  clojure.lang.ILookup
  (valAt [this key not-found]
    (if-let [v (.valAt map key)]
      (cond (instance? TrackingMap v)
            (TrackingMap. (.map v)
                          (update-in change-map [:context] (fnil conj []) key))
            
            (map? v)
            (TrackingMap. v (update-in change-map [:context] (fnil conj []) key))
            
            :else v)
      not-found))
  (valAt [this key]
    (.valAt this key nil))
  clojure.lang.IFn
  (invoke [this arg]
    (.invoke map arg))
  
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
    (TrackingMap. (.withMeta map meta) change-map))
  
  clojure.lang.IMeta
  (meta [this]
    (.meta map))
  
  clojure.lang.IPersistentCollection
  (empty [this]
    (.empty map))
  (cons [this o]
    (.cons map this o))
  (equiv [this m]
    (.equiv map (if (instance? TrackingMap m)
                  (.map m)
                  m))))

(defn changes [v]
  (when (instance? TrackingMap v) (.change-map v)))

(defmethod clojure.core/print-method TrackingMap [tm writer]
  (pr (.map tm)))

(comment
  
  (def a (->TrackingMap {} {}))
  (changes a)
  (def b (assoc a :a 1))
  (changes b)
  (def c (assoc b :c 2))
  (changes c)
  (changes (-> c
               (assoc :d {:b {}})
               (assoc-in [:d :b :c] 10)
               (update-in [:d :b :c] inc)))
  
  (changes (:a (assoc (->TrackingMap {} {}) :a (with-meta {} {:name "Brenton"}))))
  
  (changes (assoc (->TrackingMap {} {}) :a 1))
  (changes (assoc (->TrackingMap {:a 0} {}) :a 1))
  (changes (assoc-in (->TrackingMap {} {}) [:a :b :c] 1))
  (changes (assoc-in (->TrackingMap {:a {:b {}}} {}) [:a :b :c] 1))
  (changes (assoc-in (->TrackingMap {:a {:b {:c 3}}} {}) [:a :b :c] 1))
  
  (changes (update-in (->TrackingMap {:a {:b {:c 3}}} {}) [:a :b :c] inc))
  
  (changes (get (->TrackingMap {:a {:b {:c 3}}} {}) :a))
  
  (def d (-> (TrackingMap. {:a {}} {})
             (assoc :a {:b {}})
             (assoc :c 11)
             (assoc-in [:a :b :c] 10)
             (update-in [:a :b :c] inc)))
  (changes d)
  (changes (get-in d [:a :b]))
  
  (changes (-> (TrackingMap. {:a {}} {})
               (assoc :a {:b {}})
               (assoc :c 11)
               (assoc-in [:a :b :c] 10)))
  
  (changes (-> (TrackingMap. {:a {}} {})
               (assoc-in [:a :b :c] 10)
               (update-in [:a :b :c] (fnil inc 0))))
  
  (count c)
  (:a c)
  (c :a)
    
  )
