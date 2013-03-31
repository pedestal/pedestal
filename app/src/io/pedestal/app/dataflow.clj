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
   (mapv (fn [[f {:keys [in out]}]] [f in out]) derive-fns)))

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

(defn find-transform [transforms message]
  (let [{topic ::msg/topic type ::msg/type} message]
    (first (filter (fn [[op path]] (and (= op type) (= path topic))) transforms))))

(defn transform-phase
  "Find the first transform function that matches the message and
  execute it, returning the an updated flow state."
  [{:keys [new dataflow context] :as state}]
  (let [message (:message context)
        transforms (:transform dataflow)
        [_ _ transform-fn] (find-transform transforms message)]
    (if transform-fn
      (-> state
          (assoc :old new)
          (update-in (concat [:new :data-model] (::msg/topic message))
                     transform-fn message))
      state)))

(defn changed?
  "Return true is the data at any path in path-set has changed."
  [path-set old-model new-model]
  (some (fn [path] (not= (get-in old-model path)
                        (get-in new-model path)))
        path-set))

(defn gather-inputs
  "Given an old and new model and a set of paths, return an input map.
  An input map is a map of paths to the old and new values at the
  path.

  {[:path] {:old {...} :new {...}}}
  "
  [path-set old-model new-model]
  (reduce (fn [a b]
            (assoc a b {:old (get-in old-model b)
                        :new (get-in new-model b)}))
          {}
          path-set))

(defn updated-inputs
  "Given an inputs map, return the set of keys that have different old
  and new values."
  [inputs]
  (reduce (fn [a [k {:keys [old new]}]]
            (if (not= old new)
              (conj a k)
              a))
          #{}
          inputs))

(defn derive-phase
  "Execute each derive function in dependency order only if some input to the
  function has changed. Return an updated flow state."
  [{:keys [dataflow context] :as state}]
  (let [derives (:derive dataflow)]
    (reduce (fn [{:keys [old new] :as acc} [derive-fn ins out-path]]
              ;; TODO: Figure out what wildcards in input paths mean
              ;; TODO: Support wildcards in input paths.
              (if (changed? ins (:data-model old) (:data-model new))
                (let [inputs (gather-inputs ins (:data-model old) (:data-model new))
                      context (assoc context :updated (updated-inputs inputs))]
                  (-> acc
                      (assoc :old new)
                      (update-in (concat [:new :data-model] out-path)
                                 derive-fn inputs context)))
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
                    :dataflow dataflow
                    :context {:message message}}]
    (:new (-> flow-state
              transform-phase
              derive-phase
              output-phase
              effect-phase
              emit-phase))))
