(ns io.pedestal.versions
  (:require [net.lewisship.trace :refer [trace]]
            [clojure.string :as str]))

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


(comment
  (parse-version "1.3.2")
  (parse-version "1.3.2-snapshot")
  (parse-version "1.3.2-rc-3")
  )

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

(comment
  (defn t [version level] (-> version parse-version (advance level) unparse-version))

  (t "1.2.3" :snapshot)
  (t "1.2.3-snapshot" :snapshot)
  (t "1.2.3-snapshot" :release)
  (t "1.2.3" :release)
  (t "1.2.3" :patch)
  (t "1.2.3" :beta)
  (t "1.2.3-snapshot" :patch)

  )
