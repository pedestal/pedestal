(ns ^:shared io.pedestal.app.dataflow
    (:require [io.pedestal.app.data.tracking-map :as tm]))

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

(defn find-transform
  "Given a transform configuration vector, find the first transform
  function which matches the given message."
  [transforms topic type]
  (:fn
   (first (filter (fn [{op :key path :out}]
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
  (let [{out-path :out key :key} ((:input dataflow) (:message context))
        transforms (:transform dataflow)
        transform-fn (find-transform transforms out-path key)]
    (if transform-fn
      (apply-in state out-path transform-fn (:message context))
      state)))

(defn inputs-changed? [change input-paths]
  (when (seq change)
    (let [all-inputs (reduce into (vals change))]
      (some (fn [x] (some (partial descendent? x) all-inputs)) input-paths))))

(defn input-set [changes f input-paths]
  (set (f (fn [x] (some (partial descendent? x) input-paths)) changes)))

(defn update-input-sets [m ks f input-paths]
  (reduce (fn [a k]
            (update-in a [k] input-set f input-paths))
          m
          ks))

(defn flow-input [context state input-paths change]
  (-> context
      (assoc :new-model (get-in state [:new :data-model]))
      (assoc :old-model (get-in state [:old :data-model]))
      (assoc :input-paths input-paths)
      (merge (select-keys change [:added :updated :removed]))
      (update-input-sets [:added :updated :removed] filter input-paths)))

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

(defn- output-phase
  "Execute each function. Return an updated flow state."
  [{:keys [dataflow context] :as state} k]
  (let [fns (k dataflow)]
    (reduce (fn [{:keys [change] :as acc} {f :fn input-paths :in}]
              (update-in state [:new k] (fnil into [])
                         (f (flow-input context acc input-paths change))))
            state
            fns)))

(defn continue-phase
  "Execute each continue function. Return an updated flow state."
  [state]
  (output-phase state :continue))

(defn effect-phase
  "Execute each effect function. Return an updated flow state."
  [state]
  (output-phase state :effect))

(defn remove-matching-changes [change input-paths]
  (update-input-sets change [:inspect :added :updated :removed] remove input-paths))

(defn emit-phase
  [{:keys [dataflow context change] :as state}]
  (let [all-change change
        emits (:emit dataflow)]
    (-> (reduce (fn [{:keys [change] :as acc} {input-paths :in emit-fn :fn}]
                  (if (inputs-changed? change input-paths)
                    (-> acc
                        (update-in [:change] remove-matching-changes input-paths)
                        (update-in [:new :emit] (fnil into [])
                                   (emit-fn (flow-input context acc input-paths change))))
                    acc))
                state
                emits)
        (assoc :change all-change))))

(defn flow-phases-step
  "Given a dataflow, a state and a message, run the message through
  the dataflow and return the updated state. The dataflow will be
  run only once."
  [dataflow state message]
  (let [state (update-in state [:new] dissoc :continue)]
    (-> state
        transform-phase
        derive-phase
        continue-phase)))

(defn run-flow-phases
  [dataflow state message]
  (let [{{continue :continue} :new :as result} (flow-phases-step dataflow state message)]
    (if (empty? continue)
      (update-in result [:new] dissoc :continue)
      (reduce (fn [a c-message]
                (run-flow-phases dataflow a c-message))
              result
              continue))))

(defn run-all-phases
  [dataflow model message]
  (let [dm {:data-model model}
        state {:old dm
               :new dm
               :change {}
               :dataflow dataflow
               :context {:message message}}
        new-state (run-flow-phases dataflow state message)]
    (:new (-> new-state
              effect-phase
              emit-phase))))


;; Public API
;; ================================================================================

(defn add-default [v d]
  (or v d))

(defn transform-maps [transforms]
  (mapv (fn [x]
          (if (vector? x)
            (let [[key out fn] x] {:key key :out out :fn fn})
            x))
        transforms))

(defn derive-maps [derives]
  (mapv (fn [x]
          (if (vector? x)
            (let [[in out fn] x] {:in in :out out :fn fn})
            x))
        derives))

(defn output-maps [outputs]
  (mapv (fn [x]
          (if (vector? x)
            (let [[in fn] x] {:in in :fn fn})
            x))
        outputs))

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
  (-> description
      (update-in [:transform] transform-maps)
      (update-in [:derive] derive-maps)
      (update-in [:continue] (comp set output-maps))
      (update-in [:effect] (comp set output-maps))
      (update-in [:emit] output-maps)
      (update-in [:derive] sorted-derive-vector)
      (update-in [:input] add-default identity)))

(defn run [dataflow model message]
  (run-all-phases dataflow model message))

(defn get-path
  "Returns a sequence of [path value] tuples"
  ([data path]
     (get-path data [] path))
  ([data context [x & xs]]
     (if x
       (if (= x :*)
         (mapcat #(get-path (get data %) (conj context %) xs) (keys data))
         (get-path (get data x) (conj context x) xs))
       [[context data]])))

(defn input-map [{:keys [new-model input-paths]}]
  (into {} (for [path input-paths
                 [k v] (get-path new-model path)
                 :when v]
             [k v])))

(defn input-vals [inputs]
  (vals (input-map inputs)))

(defn single-val [inputs]
  (let [m (input-map inputs)]
    (assert (= 1 (count m)) "input is expected to contain exactly one value")
    (first (vals m))))

(defn update-map [{:keys [new-model updated]}]
  (into {} (for [path updated
                 [k v] (get-path new-model path)
                 :when v]
             [k v])))
