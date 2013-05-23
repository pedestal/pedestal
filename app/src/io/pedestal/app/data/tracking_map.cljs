; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.data.tracking-map
  (:require [io.pedestal.app.data.change :as chg]))

(declare plain-map merge-when-tracking-map record-change)

(deftype TrackingMap [basis map change-map]

  Object
  (toString [_] (pr-str map))

  IWithMeta
  (-with-meta [_ meta] (TrackingMap. basis (-with-meta map meta) change-map))

  IMeta
  (-meta [_] (-meta map))

  ICollection
  (-conj [coll entry]
    (if (vector? entry)
      (-assoc coll (-nth entry 0) (-nth entry 1))
      (reduce -conj coll entry)))

  IEmptyableCollection
  (-empty [_] (-empty map))

  IEquiv
  (-equiv [_ other] (-equiv map other))

  IHash
  (-hash [_] (-hash map))

  ISeqable
  (-seq [_] (-seq map))

  ICounted
  (-count [_] (-count map))

  ILookup
  (-lookup [coll k] (-lookup coll k nil))

  (-lookup [_ k not-found]
    (if-let [v (-lookup map k)]
      (cond (instance? TrackingMap v)
            (TrackingMap. basis (.-map v) (update-in change-map [:context] (fnil conj []) k))

            (map? v)
            (TrackingMap. basis v (update-in change-map [:context] (fnil conj []) k))

            :else v)
      not-found))

  IAssociative
  (-assoc [_ k v]
    (TrackingMap. basis
                  (-assoc map k (plain-map v))
                  (record-change :assoc map k v change-map)))

  (-contains-key? [_ k] (-contains-key? map k))

  IMap
  (-dissoc [_ k]
    (TrackingMap. basis
                  (-dissoc map k)
                  (record-change :dissoc map k nil change-map)))

  IKVReduce
  (-kv-reduce [_ f init] (-kv-reduce map f init))

  IFn
  (-invoke [_ k] (-lookup map k))

  (-invoke [_ k not-found] (-lookup map k not-found))

  IEditableCollection
  (-as-transient [_] (-as-transient map))

  IDeref
  (-deref [o] map))

(defn- plain-map [m]
  (if (instance? TrackingMap m) (.-map m) m))

(defn- merge-when-tracking-map [change-map tracking-map]
  (merge-with (comp set concat)
              change-map
              (dissoc (when (instance? TrackingMap tracking-map)
                        (.-change-map tracking-map))
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
    (chg/compact (.-basis v) (.-map v) (.-change-map v))))

(defn tracking-map [map]
  (TrackingMap. map map {}))
