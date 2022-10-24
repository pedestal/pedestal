(ns io.pedestal.versions
  "Utilities to parse strings to version data, advance at a level, and unparse
  versions."
  (:require [clojure.string :as str]))

(defn parse-version
  [version]
  ;; -snapshot not allowed an index, but -beta and -rc require one.
  (let [[_ major minor patch _ stability _ index :as match] (re-matches #"(?ix)
                                                    (\d+)     # major
                                                    \. (\d+)  # minor
                                                    \. (\d+)  # patch
                                                    (\-
                                                      (snapshot|beta|rc)
                                                      (\-
                                                        (\d+))?)?"
                                                                        version)
        stability' (if stability
                     (-> stability str/lower-case keyword)
                     :release)]
    (when (or (nil? match)
              (not= (contains? #{:release :snapshot} stability')
                    (nil? index)))
      (throw (RuntimeException. (format "Version '%s' is not parsable" version))))
    (cond-> {:major (parse-long major)
             :minor (parse-long minor)
             :patch (parse-long patch)
             :stability stability'}
      index (assoc :index (parse-long index)))))

(def ^:private stability->label
  {:snapshot "SNAPSHOT"
   :beta "beta"
   :rc "rc"})

(defn unparse-version
  [version-data]
  (let [{:keys [major minor patch stability index]} version-data]
    (str major
         "."
         minor
         "."
         patch
         (when (not= stability :release)
           (str "-" (stability->label stability)))
         (when (contains? {:rc :beta} stability)
           (str "-" index)))))

(def advance-levels [:major :minor :patch :release :snapshot :beta :rc])

(defn advance
  [{:keys [stability] :as version-data} level]
  (case level
    :major (-> version-data
               (update :major inc)
               (assoc :minor 0 :patch 0 :stability :release)
               (dissoc :index))
    :minor (-> version-data
               (update :minor inc)
               (assoc :patch 0 :stability :release)
               (dissoc :index))
    :patch (-> version-data
               (update :patch inc)
               (assoc :stability :release)
               (dissoc :index))
    :release (if (= :release stability)
               (throw (IllegalStateException. "Already a release version, not a snapshot, beta, or rc"))
               (-> version-data
                   (assoc :stability :release)
                   (dissoc :index)))
    :snapshot (-> version-data
                  (assoc :stability :snapshot)
                  (dissoc :index))
    (:beta :rc) (if (= stability level)
                  (update version-data :index inc)
                  (assoc version-data :stability level :index 1))))
