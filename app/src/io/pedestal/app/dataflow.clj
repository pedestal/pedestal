; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.dataflow
    (:require [io.pedestal.app.data.tracking-map :as tm]
              [io.pedestal.app.util.platform :as platform]))

(defn- matching-path-element?
  "Return true if the two elements match."
  [a b]
  (or (= a b) (= a :*) (= b :*)))

(defn matching-path?
  "Return true if the two paths match."
  [path-a path-b]
  (and (= (count path-a) (count path-b))
       (every? true? (map (fn [a b] (matching-path-element? a b)) path-a path-b))))

(defn- descendant?
  "Return true if one path could be the parent of the other."
  [path-a path-b]
  (let [[small large] (if (< (count path-a) (count path-b))
                        [path-a path-b]
                        [path-b path-a])]
    (matching-path? small (take (count small) large))))

(defn- get-path
  "Returns a sequence of [path value] tuples"
  ([data path]
     (get-path data [] path))
  ([data context [x & xs]]
     (if (and x (not= data ::nokey))
       (if (= x :*)
         (mapcat #(get-path (get data %) (conj context %) xs) (keys data))
         (get-path (get data x ::nokey) (conj context x) xs))
       [[context data]])))

(letfn [(rekey [k path arg-names]
          (if arg-names
            (let [new-key (get arg-names path)]
              (if (keyword? new-key)
                new-key
                (new-key k)))
            k))
        (value-types [arg-names]
          (if arg-names
            (reduce (fn [a [k v]]
                      (cond (contains? (set k) :*)
                            (assoc a v :seq)
                            (contains? a v)
                            (assoc a v :seq)
                            (nil? (get a v))
                            (assoc a v :single)
                            :else a))
                    {}
                    arg-names)
            (constantly :single)))]
  (defn input-map
    ([inputs]
       (input-map inputs nil))
    ([{:keys [new-model input-paths]} arg-names]
       (let [v-type (value-types arg-names)]
         (reduce (fn [a [k v]]
                   (if (= (v-type k) :seq)
                     (update-in a [k] (fnil conj []) v)
                     (assoc a k v)))
                 {}
                 (for [path input-paths
                       [k v] (get-path new-model path)
                       :when (not= v ::nokey)]
                   [(rekey k path arg-names) v]))))))

(defn input-vals [inputs]
  (vals (input-map inputs)))

(defn single-val [inputs]
  (let [m (input-map inputs)]
    (assert (>= 1 (count m)) "input is expected to have 0 or 1 values")
    (first (vals m))))

(defn- change-map [inputs model-key change-key]
  (let [[model change-paths] ((juxt model-key change-key) inputs)]
    (into {} (for [path change-paths
                   [k v] (get-path model path)
                   :when (not= v ::nokey)]
               [k v]))))

(defn updated-map [inputs]
  (change-map inputs :new-model :updated))

(defn added-map [inputs]
  (change-map inputs :new-model :added))

(defn removed-map [inputs]
  (change-map inputs :old-model :removed))

(defn- changed-inputs [inputs f]
  (let [input-m (input-map inputs)
        changed (keys (f inputs))]
    (reduce (fn [a [k v]]
              (if (some #(descendant? k %) changed)
                (assoc a k v)
                a))
            {}
            input-m)))

(defn added-inputs [inputs]
  (changed-inputs inputs added-map))

(defn updated-inputs [inputs]
  (changed-inputs inputs updated-map))

(defn old-and-new [inputs path]
  {:old (get-in inputs (into [:old-model] path))
   :new (get-in inputs (into [:new-model] path))})

(letfn [(actual-input-paths [{:keys [old-model input-paths]}]
          (for [path input-paths
                [k v] (get-path old-model path)]
            k))
        (removed [input-paths changed-paths f]
          (reduce (fn [acc path]
                    (reduce (fn [a cp] (f a path cp))
                            acc
                            (filter #(descendant? path %) changed-paths)))
                  {}
                  input-paths))]
  (defn removed-inputs [inputs]
    (let [paths (actual-input-paths inputs)]
      (into
       ;; process changes reported as a remove
       (removed paths (keys (removed-map inputs))
                (fn [m path changed-path]
                  (if (<= (count changed-path) (count path))
                    (assoc m path ::removed)
                    (assoc m path (get-in inputs (concat [:new-model] path))))))
       ;; process changes reported as an update
       (removed paths (keys (updated-map inputs))
                (fn [m path changed-path]
                  (if (< (count changed-path) (count path))
                    (let [new-m (:new-model inputs)
                          parent (butlast path)
                          k (last path)
                          parent-m (if (seq parent) (get-in new-m parent) new-m)]
                      (if (contains? parent-m k)
                        (assoc m path (get-in inputs (concat [:new-model] path)))
                        (assoc m path ::removed)))
                    m)))))))

(defn- topo-visit
  "Perform a topological sort of the provided graph."
  [graph node]
  (let [n (get graph node)]
    (if (:visited n)
      graph
      (let [graph (assoc-in graph [node :visited] true)
            graph (reduce topo-visit graph (:deps n))]
        (assoc graph ::order (conj (::order graph) node))))))

(defn- sort-derive-fns
  "Return a sorted sequence of derive function configurations."
  [derive-fns]
  (let [derive-fns (map #(assoc % :id (gensym)) derive-fns)
        index (reduce (fn [a x] (assoc a (:id x) x)) {} derive-fns)
        deps (for [{:keys [in out id]} derive-fns i in] [id i out])
        graph (reduce
               (fn [a [f _ out]]
                 (assoc a f {:deps (set (map first
                                             (filter (fn [[_ in]] (descendant? in out))
                                                     deps)))}))
               {}
               deps)]
    (reverse (reduce (fn [a b]
                       (conj a (dissoc (get index b) :id)))
                     []
                     (::order (reduce topo-visit (assoc graph ::order []) (keys graph)))))))

(defn find-message-transformer
  "Given a transform configuration vector, find the first transform
  function which matches the given message."
  [transforms out-path key]
  (let [out-path (platform/vec-unless-seqable out-path)]
    (:fn
     (first (filter (fn [{op :key path :out}]
                      (let [[path out-path] (if (= (last path) :**)
                                              (let [c (count path)]
                                                [(conj (vec (take (dec c) path)) :*)
                                                 (vec (take c out-path))])
                                              [path out-path])]
                        (and (matching-path-element? op key)
                             (matching-path? path out-path))))
                    transforms)))))

(defn- merge-changes [old-changes new-changes]
  (merge-with into old-changes new-changes))

(defn- update-flow-state [state tracking-map]
  (-> state
      (assoc-in [:new :data-model] @tracking-map)
      (update-in [:change] merge-changes (tm/changes tracking-map))))

(defn- track-update-in [data-model out-path f & args]
  (apply update-in (tm/tracking-map data-model) out-path f args))

(defn- apply-in [state out-path f & args]
  (let [data-model (get-in state [:new :data-model])
        new-data-model (apply track-update-in data-model out-path f args)]
    (update-flow-state state new-data-model)))

(defn- transform-phase
  "Find the first transform function that matches the message and
  execute it, returning the updated flow state."
  [{:keys [new dataflow context] :as state}]
  (let [{out-path :out key :key} ((:input-adapter dataflow) (:message context))
        transform-fn (find-message-transformer (:transform dataflow) out-path key)]
    (if transform-fn
      (apply-in state out-path transform-fn (:message context))
      state)))

(defn input-change-propagator
  "The default propagator predicate. Return true if any of the changed
  paths are on the same path as the input path."
  [state changed-inputs input-path]
  (some (partial descendant? input-path) changed-inputs))

(defn- propagate?
  "Return true if a dependent function should be run based on the
  state of its input paths.

  Custom propagator predicates can be provided by attaching
  :propagator metadata to any input path."
  [{:keys [change] :as state} input-paths]
  (let [changed-inputs (if (seq change) (reduce into (vals change)) [])]
    (some (fn [input-path]
            (let [propagator-pred (:propagator (meta input-path))]
              (propagator-pred state changed-inputs input-path)))
          input-paths)))

(defn- input-set [changes f input-paths]
  (set (f (fn [x] (some (partial descendant? x) input-paths)) changes)))

(defn- update-input-sets [m ks f input-paths]
  (reduce (fn [a k]
            (update-in a [k] input-set f input-paths))
          m
          ks))

(defn- flow-input [context state input-paths change]
  (-> context
      (assoc :new-model (get-in state [:new :data-model]))
      (assoc :old-model (get-in state [:old :data-model]))
      (assoc :input-paths input-paths)
      (merge (select-keys change [:added :updated :removed]))
      (update-input-sets [:added :updated :removed] filter input-paths)))

(defn- dataflow-fn-args [inputs args-key arg-names]
  (case args-key
    :vals [(input-vals inputs)]
    :map [(input-map inputs arg-names)]
    :map-seq (apply concat (seq (input-map inputs arg-names)))
    :single-val [(single-val inputs)]
    :default [inputs]
    [inputs]))

(defn- derive-phase
  "Execute each derive function in dependency order only if some input to the
  function has changed. Return an updated flow state."
  [{:keys [dataflow context] :as state}]
  (let [derives (:derive dataflow)]
    (reduce (fn [{:keys [change] :as acc}
                {input-paths :in derive-fn :fn out-path :out args :args arg-names :arg-names}]
              (if (propagate? acc input-paths)
                (apply apply-in acc out-path derive-fn
                       (dataflow-fn-args
                        (flow-input context acc input-paths change)
                        args
                        arg-names))
                acc))
            state
            derives)))

(defn- output-phase
  "Execute each function. Return an updated flow state."
  [{:keys [dataflow context] :as state} k]
  (let [fns (k dataflow)]
    (reduce (fn [{:keys [change] :as acc} {f :fn input-paths :in args :args arg-names :arg-names}]
              (if (propagate? acc input-paths)
                (update-in acc [:new k] (fnil into [])
                           (apply f (dataflow-fn-args
                                     (flow-input context acc input-paths change)
                                     args
                                     arg-names)))
                acc))
            state
            fns)))

(defn- continue-phase
  "Execute each continue function. Return an updated flow state."
  [state]
  (output-phase state :continue))

(defn- effect-phase
  "Execute each effect function. Return an updated flow state."
  [state]
  (output-phase state :effect))

(letfn [(remover [change-set input-paths]
          (set (remove #(some (partial matching-path? %) input-paths) change-set)))]
  (defn remove-matching-changes [change input-paths]
    (reduce (fn [a k]
              (update-in a [k] remover input-paths))
            change
            [:inspect :added :updated :removed])))

(defn- emit-phase
  [{:keys [dataflow context change] :as state}]
  (let [emits (:emit dataflow)]
    (-> (reduce (fn [{:keys [change remaining-change processed-inputs] :as acc}
                    {input-paths :in emit-fn :fn mode :mode}]
                  (let [report-change (if (= mode :always) change remaining-change)]
                    (if (propagate? (assoc acc :change report-change) input-paths)
                      (-> acc
                          (update-in [:remaining-change] remove-matching-changes input-paths)
                          (update-in [:processed-inputs] (fnil into []) input-paths)
                          (update-in [:new :emit] (fnil into [])
                                     (emit-fn (-> (flow-input context acc input-paths report-change)
                                                  (assoc :mode mode :processed-inputs processed-inputs)))))
                      acc)))
                (assoc state :remaining-change change)
                emits)
        (dissoc :remaining-change))))

(defn- flow-phases-step
  "Given a dataflow, a state and a message, run the message through
  the dataflow and return the updated state. The dataflow will be
  run only once."
  [state dataflow message]
  (let [state (update-in state [:new] dissoc :continue)]
    (-> (assoc-in state [:context :message] message)
        transform-phase
        derive-phase
        continue-phase)))

(defn- run-flow-phases
  [state dataflow message]
  (let [{{continue :continue} :new :as result} (flow-phases-step state dataflow message)
        input (filter #(:input (meta %)) continue)
        continue (remove #(:input (meta %)) continue)
        new-state (if (empty? continue)
                    (update-in result [:new] dissoc :continue)
                    (reduce (fn [a c-message]
                              (run-flow-phases (assoc a :old (:new a))
                                               dataflow
                                               c-message))
                            result
                            continue))]
    (if (empty? input)
      new-state
      (update-in new-state [:new :continue-inputs] (fnil into []) input))))

(defn- run-all-phases
  [model dataflow message]
  (let [state {:old model
               :new model
               :change {}
               :dataflow dataflow
               :context {}}
        new-state (run-flow-phases state dataflow message)]
    (:new (-> (assoc-in new-state [:context :message] message)
              effect-phase
              emit-phase))))

(defn- add-default [v d]
  (or v d))

(defn with-propagator
  "Add a propagator predicate to each input path returning a set of
  input paths.

  The single argument version will add the default propagator
  predicate."
  ([ins]
     (with-propagator ins input-change-propagator))
  ([ins propagator]
     (set (mapv (fn [i]
                  (if (:propagator (meta i))
                    i
                    (vary-meta i assoc :propagator propagator)))
                ins))))

(defn transform-maps [transforms]
  (mapv (fn [x]
          (if (vector? x)
            (let [[key out fn] x] {:key key :out out :fn fn})
            x))
        transforms))

(defn- add-arg-names [{:keys [in] :as m}]
  (let [arg-names (when (map? in) in)
        in (if (map? in) (set (keys in)) in)]
    (assoc m :in in :arg-names arg-names)))

(defn- dataflow-maps [coll f]
  (mapv (fn [x]
          (-> (if (vector? x) (f x) x)
              add-arg-names
              (update-in [:in] with-propagator)))
        coll))

(defn- derive-maps [derives]
  (dataflow-maps derives (fn [[in out fn args]] {:in in :out out :fn fn :args args})))

(defn- output-maps [outputs]
  (dataflow-maps outputs (fn [[in fn args]] {:in in :fn fn :args args})))

(defn build
  "Given a dataflow description map, return a dataflow engine. An example dataflow
  configuration is shown below:

  {:transform [[:op [:output :path] transform-fn]]
   :effect    #{{:fn effect-fn :in #{[:input :path]}}}
   :derive    #{{:fn derive-fn :in #{[:input :path]} :out [:output :path]}}
   :continue  #{{:fn some-continue-fn :in #{[:input :path]}}}
   :emit      [[#{[:input :path]} emit-fn]]}
  "
  [description]
  (-> description
      (update-in [:transform] transform-maps)
      (update-in [:derive] derive-maps)
      (update-in [:continue] (comp set output-maps))
      (update-in [:effect] (comp set output-maps))
      (update-in [:emit] output-maps)
      (update-in [:derive] sort-derive-fns)
      (update-in [:input-adapter] add-default identity)))

(defn run [model dataflow message]
  (if (:debug dataflow)
    (let [start (.getTime (platform/date))
          new-model (run-all-phases model dataflow message)
          end (.getTime (platform/date))]
      (run-all-phases new-model
                      (assoc dataflow :input-adapter identity)
                      {:key :debug :out [:pedestal :debug :dataflow-time] :value (- end start)}))
    (run-all-phases model dataflow message)))
