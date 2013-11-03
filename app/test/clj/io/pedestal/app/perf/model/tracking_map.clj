; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.perf.model.tracking-map)

(declare plain-map merge-when-tracking-map record-change)

(deftype TrackingMap [basis map change-map]

  java.io.Serializable
  clojure.lang.MapEquivalence

  clojure.lang.IPersistentMap
  (assoc [this key val]
    (TrackingMap. basis
                  (.assoc map key (plain-map val))
                  (record-change :assoc map key val change-map)))
  (assocEx [this key val]
    (TrackingMap. basis
                  (.assocEx map key val) (record-change :assoc map key val change-map)))
  (without [this key]
    (TrackingMap. basis
                  (.without map key) (record-change :dissoc map key val change-map)))

  clojure.lang.ILookup
  (valAt [this key not-found]
    (if-let [v (.valAt map key)]
      (cond (instance? TrackingMap v)
            (TrackingMap. basis (.map v) (update-in change-map [:context] (fnil conj []) key))

            (map? v)
            (TrackingMap. basis v (update-in change-map [:context] (fnil conj []) key))

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
    (TrackingMap. basis (.withMeta map meta) change-map))

  clojure.lang.IMeta
  (meta [this]
    (.meta map))

  clojure.lang.IPersistentCollection
  (empty [this]
    (.empty map))
  (cons [this o]
    (TrackingMap. basis (.cons map o)
                  (let [{:keys [context]} change-map
                        inspect-paths (mapv #(if (seq context) (conj context %) [%])
                                            (keys o))
                        cs (-> change-map
                               (dissoc :context)
                               (update-in [:inspect] #(apply (fnil conj #{}) % inspect-paths)))]
                    (merge-when-tracking-map cs o))))
  (equiv [this m]
    (.equiv map (plain-map m)))

  clojure.lang.IEditableCollection
  (asTransient [this]
    (.asTransient map))

  clojure.lang.IHashEq
  (hasheq [this]
    (.hasheq map))

  clojure.lang.IDeref
  (deref [this] map))

(defn- plain-map [m]
  (if (instance? TrackingMap m) (.map m) m))

(defn- merge-when-tracking-map [change-map tracking-map]
  (merge-with (comp set concat)
              change-map
              (dissoc (when (instance? TrackingMap tracking-map)
                        (.change-map tracking-map))
                      :context)))

(defn- record-change [action map key val change-map]
  (let [{:keys [context updated] :as cs} change-map
        change (if (seq context)
                 (conj context key)
                 [key])

        cs (cond (= action :dissoc)
                 (update-in cs [:removed] (fnil conj #{}) change)

                 (and (get map key) (not= (get map key) (plain-map val)))
                 (update-in cs [:updated] (fnil conj #{}) change)

                 (not (get map key))
                 (update-in cs [:added] (fnil conj #{}) change)

                 :else cs)

        cs (cond (and (= action :assoc) (map? val) (not (instance? TrackingMap val)))
                 (update-in cs [:inspect] (fnil conj #{}) change)
                 (and (= action :assoc) (nil? val))
                 (update-in cs [:inspect] (fnil conj #{}) change)
                 :else
                 cs)]
    (merge-when-tracking-map cs val)))

(defn changes [v]
  (when (instance? TrackingMap v)
    (.change-map v)))

(defmethod clojure.core/print-method TrackingMap [tm writer]
  (pr (.map tm)))

(defn tracking-map [map]
  (->TrackingMap map map {}))
