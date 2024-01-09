; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.internal
  "Internal utilities, not for reuse, subject to change at any time."
  {:no-doc true
   :added  "0.7.0"})

(defn- column-width
  [title-width k values]
  (reduce max title-width (map #(-> % k str count) values)))

(defn- padded [s width ^String p]
  (let [b (StringBuilder. (str s))]
    (while (< (.length b) width)
      (.append b p))
    (.toString b)))

(defn print-routing-table
  [expanded-routes]
  (let [path-width   (column-width 4 :path expanded-routes)
        name-width   (column-width 4 :route-name expanded-routes)
        method-width (column-width 6 :method expanded-routes)]
    (println (str "┏━"
                  (padded nil method-width "━")
                  "━┳━"
                  (padded nil path-width "━")
                  "━┳"
                  (padded nil name-width "━")
                  "━━┓"))
    (println (str "┃ "
                  (padded "Method" method-width " ")
                  " ┃ "
                  (padded "Path" path-width " ")
                  " ┃ "
                  (padded "Name" name-width " ")
                  " ┃"))
    (println (str "┣━"
                  (padded nil method-width "━")
                  "━╋━"
                  (padded nil path-width "━")
                  "━╋━"
                  (padded nil name-width "━")
                  "━┫"))
    (doseq [{:keys [method path route-name]} (sort-by :path expanded-routes)]
      (println (str "┃ "
                    (padded method method-width " ")
                    " ┃ "
                    (padded path path-width " ")
                    " ┃ "
                    (padded route-name name-width " ")
                    " ┃"
                    )))
    (println (str "┗━"
                  (padded nil method-width "━")
                  "━┻━"
                  (padded nil path-width "━")
                  "━┻"
                  (padded nil name-width "━")
                  "━━┛"))))


(defn print-routing-table-on-change
  "Checks if the routing table has changed visibly and prints it if so. Returns the new routing
  table."
  [*prior-routes new-routes]
  (let [new-routes' (->> new-routes
                         ;; Ignore keys that aren't needed (and cause comparison problems).
                         (map #(select-keys % [:method :path :route-name]))
                         set)]
    (when (not= @*prior-routes new-routes')
      (println "Routing table:")
      (print-routing-table new-routes')
      (reset! *prior-routes new-routes')))
  new-routes)
