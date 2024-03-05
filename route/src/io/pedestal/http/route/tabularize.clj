; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.tabularize
  "Support for tabular output.  If useful, may go public and move elsewhere (maybe org.clj-commons/pretty)."
  {:added  "0.7.0"
   :no-doc true}
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]))

(defn- rpad
  [s width ^String p]
  (let [b (StringBuilder. (str s))]
    (while (< (.length b) width)
      (.append b p))
    (.toString b)))

(defn- expand-column
  [column]
  (if (keyword? column)
    {:key   column
     :title (-> column name (string/replace "-" " ") string/capitalize)}
    column))

(defn- set-width
  [column data]
  (let [{:keys [key ^String title]} column
        title-width (.length title)
        width       (->> data
                         (map key)
                         (map str)
                         (map #(.length %))
                         (reduce max title-width))]
    (assoc column :width width)))

(defn print-table
  "Similar to clojure.pprint/print-table, but with fancier graphics and more control
  over column titles."
  [columns data]
  (let [columns'    (->> columns
                         (map expand-column)
                         (map #(set-width % data))
                         (map-indexed #(assoc %2 :index %1)))
        last-column (dec (count columns'))]
    (print "┏━")
    (doseq [{:keys [width index]} columns']
      (print (rpad nil width "━"))
      (when-not (= index last-column)
        (print "━┳━")))
    (println "━┓")

    (print "┃")
    (doseq [{:keys [width title]} columns']
      (print " ")
      (print (rpad title width " "))
      (print " ┃"))
    (println)
    (print "┣━")
    (doseq [{:keys [width index]} columns']
      (print (rpad nil width "━"))
      (when-not (= index last-column)
        (print "━╋━")))
    (println "━┫")

    (doseq [item data]
      (print "┃")
      (doseq [{:keys [width key]} columns']
        (print " ")
        (print (rpad (get item key) width " "))
        (print " ┃"))
      (println))

    (print "┗━")
    (doseq [{:keys [width index]} columns']
      (print (rpad nil width "━"))
      (when-not (= index last-column)
        (print "━┻━")))
    (println "━┛")))

(s/fdef print-table
        :args (s/cat :columns (s/coll-of ::column)
                     :data (s/coll-of map?)))

(s/def ::column
  (s/or :simple keyword?
        :full ::column-full))

(s/def ::column-full
  (s/keys :req-un [::key
                   ::title]))

(s/def ::key keyword?)
(s/def ::title string?)
