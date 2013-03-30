(ns ^:shared io.pedestal.app.dataflow)

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

(defn transform-derive [derive-fns]
  (sort-derive-fns
   (mapv (fn [[f {:keys [in out]}]] [f in out]) derive-fns)))

(defn build
  "Given a dataflow description map, return a dataflow engine. An example dataflow
  configuration is shown below:

  {:transform [[:op [:path :to :update] transform-fn]]
   :effect {effect-fn #{[:input :path]}}
   :derive {derive-fn {:out [:output :path] :in #{[:input :path]}}}
   :continue {some-continue-fn #{[:input :path]}}
   :emit [[[:path :in :data :model] {:init emit-init-fn :change emit-change-fn}]]}
  "
  [description]
  (-> description
      (update-in [:derive] transform-derive)))
