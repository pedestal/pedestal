(ns io.pedestal.versions)

(defn parse-version
  [version]
  (let [[_ major minor patch _ stability index :as match] (re-matches #"(?ix)
                                                    (\d+)     # major
                                                    \. (\d+)  # minor
                                                    \. (\d+)  # patch
                                                    (\-
                                                      (snapshot|beta|rc)
                                                      \-
                                                      (\d+))?"
                                                                      version)]
    (when-not match
      (throw (RuntimeException. (format "Version '%s' is not parsable" version))))
    (cond-> {:major (parse-long major)
             :minor (parse-long minor)
             :patch (parse-long patch)
             :stability (if stability (keyword stability) :release)}
      stability (assoc :index (parse-long index)))))


(comment
  (parse-version "1.3.2")
  (parse-version "1.3.2-snapshot-3")
  )

(defn unparse-version
  [version-data]
  (let [{:keys [major minor patch stability index]} version-data]
    (str major
         "."
         minor
         "."
         patch
         (when (not= stability :release)
           (str "-" (name stability) "-" index)))))

(def advance-levels [:major :minor :patch :release :snapshot :beta :rc])

(defn advance
  [{:keys [stability] :as version-data} level]
  (case level
    :major (-> version-data
               (update :major inc)
               (assoc :minor 0 :patch 0 :stability :release))
    :minor (-> version-data
               (update :minor inc)
               (assoc :patch 0 :stability :release))
    :patch (-> version-data
               (update :patch inc)
               (assoc :stability :release))
    :release (if (= :release stability)
               (throw (IllegalStateException. "Already a release version, not a snapshot, beta, or rc"))
               (assoc version-data :stability :release))
    (:snapshot :beta :rc) (if (= stability level)
                            (update version-data :index inc)
                            (assoc version-data :stability level :index 1))))

(comment
  (defn t [version level] (-> version parse-version (advance level) unparse-version))

  (t "1.2.3" :snapshot)
  (t "1.2.3-snapshot-7" :snapshot)
  (t "1.2.3-snapshot-7" :release)
  (t "1.2.3" :release)
  (t "1.2.3" :patch)
  (t "1.2.3" :beta)
  (t "1.2.3-snapshot-5" :patch)

  )
