(ns ^:shared io.pedestal.app.util.adapters
  (:require [clojure.set :as set]
            [io.pedestal.app.dataflow :as dataflow]
            [io.pedestal.app.messages :as msg]))

;; Adapter v1 to v2
;; ================================================================================

(defn- rekey-description
  [description]
  (set/rename-keys description
                   {:models :transform
                    :views :derive
                    :combine :derive
                    :emitters :emit
                    :output :effect
                    :feedback :continue
                    :navigation :focus}))

(defn- convert-transform [transforms]
  (reduce (fn [a [k {init :init transform-fn :fn}]]
            (conj a {:key k
                     :out [k]
                     :init [{msg/topic k msg/type msg/init :value init}]
                     :fn transform-fn}))
          []
          transforms))

(defn- old-style-inputs [inputs]
  (reduce (fn [a [path]]
            (assoc a path
                   {:old (get-in inputs [:old-model path])
                    :new (get-in inputs [:new-model path])}))
          {}
          (:input-paths inputs)))

(defn- convert-derive [derives]
  (reduce (fn [a [k {derive-fn :fn in :input}]]
            (conj a {:in (set (map vector in))
                     :out [k]
                     :fn (fn [old-value inputs]
                           (if (= (count in) 1)
                             (derive-fn old-value
                                        k
                                        (get-in inputs [:old-model (first in)])
                                        (get-in inputs [:new-model (first in)]))
                             (derive-fn old-value
                                        (old-style-inputs inputs))))}))
          #{}
          derives))

(defn- convert-continue [continues]
  (reduce (fn [a [k continue-fn]]
            (conj a {:in #{[k]}
                     :fn (fn [inputs]
                           (continue-fn k
                                        (get-in inputs [:old-model k])
                                        (get-in inputs [:new-model k])))}))
          #{}
          continues))

(defn- convert-effect [effects]
  (reduce (fn [a [k effect-fn]]
            (conj a {:in #{[k]}
                     :fn (fn [inputs]
                           (effect-fn (:message inputs)
                                      (get-in inputs [:old-model k])
                                      (get-in inputs [:new-model k])))}))
          #{}
          effects))

(defn- convert-emit [emits]
  (reduce (fn [a [k {emit-fn :fn in :input}]]
            (let [input-vecs (set (map vector in))]
              (conj a {:in input-vecs
                       :init (fn [inputs]
                               (emit-fn (old-style-inputs inputs)))
                       :mode :always
                       :fn (fn [inputs]
                             (let [added (dataflow/added-map inputs)
                                   updated (dataflow/updated-map inputs)
                                   removed (dataflow/removed-map inputs)]
                               (emit-fn (old-style-inputs inputs)
                                        (set (map first (concat (keys updated)
                                                                (keys added)
                                                                (keys removed)))))))})))
          []
          emits))

(defn- remove-empty-vals [description]
  (reduce (fn [a [k v]]
            (if (and (contains? #{:transform :derive :continue :effect :emit} k)
                     (empty? v))
              a
              (assoc a k v)))
          {}
          description))

(defn- remove-topic-map [message]
  (let [t (msg/topic message)]
    (cond (map? t) (:model t)
          :else t)))

(defn adapt-v1-to-v2 [description]
  (-> (rekey-description description)
      (assoc :input-adapter (fn [m] {:out [(remove-topic-map m)]
                                    :key (remove-topic-map m)}))
      (update-in [:transform] convert-transform)
      (update-in [:derive] convert-derive)
      (update-in [:continue] convert-continue)
      (update-in [:effect] convert-effect)
      (update-in [:emit] convert-emit)
      remove-empty-vals))
