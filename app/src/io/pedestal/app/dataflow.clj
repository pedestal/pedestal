(ns ^:shared io.pedestal.app.dataflow
    (:require [io.pedestal.app.messages :as msg]
              [io.pedestal.app.data.tracking-map :as tm]))

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
  (let [derive-fns (map (fn [[f ins out]] [(gensym) f ins out]) derive-fns)
        index (reduce (fn [a [id f :as xs]] (assoc a id xs)) {} derive-fns)
        deps (for [[id f ins out] derive-fns in ins] [id in out])
        graph (reduce
               (fn [a [f _ out]]
                 (assoc a f {:deps (set (map first
                                             (filter (fn [[_ in]] (descendent? in out))
                                                     deps)))}))
               {}
               deps)]
    (reverse (reduce (fn [a b]
                       (let [[_ f ins out] (get index b)]
                         (conj a [f ins out])))
                     []
                     (::order (reduce topo-visit (assoc graph ::order []) (keys graph)))))))

(defn sorted-derive-vector
  "Convert derive function config to a vector and sort in dependency
  order."
  [derive-fns]
  (sort-derive-fns
   (mapv (fn [{:keys [in out fn]}] [fn in out]) derive-fns)))

(defn build
  "Given a dataflow description map, return a dataflow engine. An example dataflow
  configuration is shown below:

  {:transform [[:op [:output :path] transform-fn]]
   :effect {effect-fn #{[:input :path]}}
   :derive [{:fn derive-fn :in #{[:input :path]} :out [:output :path]}]
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

(defn- merge-changes [old-changes new-changes]
  (merge-with into old-changes new-changes))

(defn update-flow-state [state tracking-map]
  (-> state
      (assoc-in [:new :data-model] (.map tracking-map))
      (update-in [:change] merge-changes (tm/changes tracking-map))))

(defn track-update-in [data-model out-path f & args]
  (apply update-in (tm/->TrackingMap data-model {}) out-path f args))

(defn apply-in [state out-path f & args]
  (let [data-model (get-in state [:new :data-model])
        new-data-model (apply track-update-in data-model out-path f args)]
    (update-flow-state state new-data-model)))

(defn transform-phase
  "Find the first transform function that matches the message and
  execute it, returning the updated flow state."
  [{:keys [new dataflow context] :as state}]
  (let [message (:message context)
        transforms (:transform dataflow)
        transform-fn (find-transform transforms (::msg/topic message) (::msg/type message))]
    (if transform-fn
      (apply-in state (::msg/topic message) transform-fn message)
      state)))

(defn inputs-changed? [change input-paths]
  (let [all-inputs (reduce into (vals change))]
    (some (fn [x] (some (partial descendent? x) all-inputs)) input-paths)))

(defn filter-inputs [input-paths changes]
  (set (filter (fn [x] (some (partial descendent? x) input-paths)) changes)))

(defn flow-input [context state input-paths change]
  (-> context
      (assoc :new-model (get-in state [:new :data-model]))
      (assoc :old-model (get-in state [:old :data-model]))
      (assoc :input-paths input-paths)
      (assoc :added (filter-inputs input-paths (:added change)))
      (assoc :updated (filter-inputs input-paths (:updated change)))
      (assoc :removed (filter-inputs input-paths (:removed change)))))

(defn derive-phase
  "Execute each derive function in dependency order only if some input to the
  function has changed. Return an updated flow state."
  [{:keys [dataflow context] :as state}]
  (let [derives (:derive dataflow)]
    (reduce (fn [{:keys [old new change] :as acc} [derive-fn input-paths out-path]]
              (if (inputs-changed? change input-paths)
                (apply-in acc out-path derive-fn (flow-input context acc input-paths change))
                acc))
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

;; Public API
;; ================================================================================

(defn input-vals [{:keys [new-model input-paths]}]
  ;; TODO: Update to work with wildcard paths
  (map #(get-in new-model %) input-paths))

