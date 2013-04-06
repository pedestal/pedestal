(ns ^:shared io.pedestal.app.dataflow
  (:require [io.pedestal.app.messages :as msg]))

(defn matching-path-element?
  "Return true if the two elements match."
  [a b]
  (or (= a b) (= a :*) (= b :*)))

(defn matching-path?
  "Return true if the two paths match."
  [path-a path-b]
  (and (= (count path-a) (count path-b))
       (every? true? (map (fn [a b] (matching-path-element? a b)) path-a path-b))))

(defn descendent?
  "Return true if one path could be the parent of the other."
  [path-a path-b]
  (let [[small large] (if (< (count path-a) (count path-b))
                        [path-a path-b]
                        [path-b path-a])]
    (matching-path? small (take (count small) large))))

(defn- topo-visit
  "Perform a topological sort of the provided graph."
  [graph node]
  (let [n (get graph node)]
    (if (:visited n)
      graph
      (let [graph (assoc-in graph [node :visited] true)
            graph (reduce topo-visit graph (:deps n))]
        (assoc graph ::order (conj (::order graph) node))))))

(defn sort-derive-fns
  "Return a sorted sequence of derive function configurations. The
  input sequence will have the following shape:

  [['a #{[:x]} [:a]]
   ['b #{[:y]} [:b]]]

  This is a vector of vectors where each vector contains three
  elements: the output path, the function and a set of input paths.

  This is not a fast sort so it should only be done once when creating
  a new dataflow."
  [derive-fns]
  (let [index (reduce (fn [a [f :as xs]] (assoc a f xs)) {} derive-fns)
        deps (for [[f ins out] derive-fns in ins] [f in out])
        graph (reduce
               (fn [a [f in]]
                 (assoc a f {:deps (set (map first
                                             (filter (fn [[_ _ out]] (descendent? in out))
                                                     deps)))}))
               {}
               deps)]
    (reduce (fn [a b]
              (conj a (get index b)))
            []
            (::order (reduce topo-visit (assoc graph ::order []) (keys graph))))))

(defn sorted-derive-vector
  "Convert derive function config to a vector and sort in dependency
  order."
  [derive-fns]
  (sort-derive-fns
   (mapv (fn [[{:keys [in out]} f]] [f in out]) derive-fns)))

(defn build
  "Given a dataflow description map, return a dataflow engine. An example dataflow
  configuration is shown below:

  {:transform [[:op [:output :path] transform-fn]]
   :effect {effect-fn #{[:input :path]}}
   :derive {derive-fn {:in #{[:input :path]} :out [:output :path]}}
   :continue {some-continue-fn #{[:input :path]}}
   :emit [[#{[:input :path]} emit-fn]]}
  "
  [description]
  (update-in description [:derive] sorted-derive-vector))

;; TODO: Once we have a way to measure performance, add memoization
(defn find-transform
  "Given a transform configuration vector, find the first transform
  function which matches the given message."
  [transforms topic type]
  (last
   (first (filter (fn [[op path]]
                    (let [[path topic] (if (= (last path) :**)
                                         (let [c (count path)]
                                           [(conj (vec (take (dec c) path)) :*)
                                            (vec (take c topic))])
                                         [path topic])]
                      (and (matching-path-element? op type)
                           (matching-path? path topic))))
                  transforms))))

(defn transform-phase
  "Find the first transform function that matches the message and
  execute it, returning the an updated flow state."
  [{:keys [new dataflow context] :as state}]
  (let [message (:message context)
        transforms (:transform dataflow)
        transform-fn (find-transform transforms (::msg/topic message) (::msg/type message))]
    (if transform-fn
      (let [change-path (concat [:new :data-model] (::msg/topic message))]
        (-> state
            (update-in [:change-paths] conj change-path)
            (update-in change-path transform-fn message)))
      state)))

;; TODO: Once we have a way to measure performance, add memoization
(defn partition-wildcard-path [path]
  (reduce (fn [a b]
            (if (and (= (first b) :*) (> (count b) 1))
              (apply conj a (repeat (count b) [:*]))
              (conj a b)))
          []
          (partition-by #(= % :*) path)))

(defn components-for-path [m wildcard-path]
  (reduce (fn [components sub-path]
            (if (= sub-path [:*])
              (reduce (fn [c [path data]]
                        (assert (map? data) ":* can only be applied to maps")
                        (reduce (fn [c' [k v]]
                                  (assoc c' (conj path k) v))
                                c
                                data))
                      {}
                      components)
              (reduce (fn [c [path data]]
                        (assoc c (into path sub-path) (get-in data sub-path)))
                      {}
                      components)))
          {[] m}
          (partition-wildcard-path wildcard-path)))

(defn components [m paths]
  (reduce (fn [acc path]
            (merge acc (components-for-path m path)))
          {}
          paths))

(defn remap [components]
  (reduce (fn [a [path v]]
            (if (map? v)
              (update-in a path merge v)
              (assoc-in a path v)))
          {}
          components))

(defn changes [context]
  {:added #{}
   :removed #{}
   :updated #{}})

(defn derive-phase
  "Execute each derive function in dependency order only if some input to the
  function has changed. Return an updated flow state."
  [{:keys [dataflow context change-paths] :as state}]
  (let [derives (:derive dataflow)]
    (reduce (fn [{:keys [old new] :as acc} [derive-fn input-paths out-path]]
              (let [old-components (components (:data-model old) input-paths)
                    new-components (components (:data-model new) input-paths)]
                (if (not= old-components new-components)
                  (let [old-input (remap old-components)
                        new-input (remap new-components)]
                    (update-in acc (concat [:new :data-model] out-path)
                               derive-fn old-input new-input
                               (-> context
                                   (assoc :inputs (keys new-components))
                                   (assoc :old-components old-components)
                                   (assoc :new-components new-components))))
                  acc)))
            state
            derives)))

(defn output-phase [state]
  state)

(defn effect-phase [state]
  state)

(defn emit-phase [state]
  state)

(defn flow-step
  "Given a dataflow, a state and a message, run the message through
  the dataflow and return the updated state. The dataflow will be
  run only once. The state is a map with:

  {:data-model {}
   :emit       []
   :output     []
   :continue   []}
   "
  [dataflow state message]
  (let [flow-state {:new state
                    :old state
                    :updated #{}
                    :dataflow dataflow
                    :context {:message message}}]
    (:new (-> flow-state
              transform-phase
              derive-phase
              output-phase
              effect-phase
              emit-phase))))
