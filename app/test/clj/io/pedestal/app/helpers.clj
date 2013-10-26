(ns io.pedestal.app.helpers
  (:require [clojure.core.async :refer [alts!! timeout]]))

(defn take-n
  "Helper function which returns n items taken from the given
  channel."
  [n rc]
  (loop [n n
         t (timeout 1000)
         results []]
    (let [[v c] (alts!! [rc t])]
      (if (or (= c t) (= n 1))
        (conj results v)
        (recur (dec n)  t (conj results v))))))
