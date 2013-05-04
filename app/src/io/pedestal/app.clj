; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app
    (:require [clojure.set :as set]
              [io.pedestal.app.protocols :as p]
              [io.pedestal.app.messages :as msg]
              [io.pedestal.app.queue :as queue]
              [io.pedestal.app.tree :as tree]
              [io.pedestal.app.dataflow :as dataflow]))

(defn default-emitter-fn
  "The default emitter function used by the previous dataflow
  version. All new dataflows should use the default-emitter function."
  ([inputs]
     (vec (mapcat (fn [[k v]]
                    [[:node-create [k] :map]
                     [:value [k] nil (:new v)]])
                  inputs)))
  ([inputs changed-inputs]
     (mapv (fn [changed-input]
             [:value [changed-input] (:new (get inputs changed-input))])
           changed-inputs)))

(letfn [(prefixed [k p] (vec (concat (if (keyword? p) [p] p) k)))]
  (defn default-emitter
    "Return an emitter function which will emit deltas under the
    provided path prefix."
    [prefix]
    (fn [inputs]
      (vec (concat (let [added (dataflow/added-inputs inputs)]
                     (mapcat (fn [[k v]]
                               (let [k (prefixed k prefix)]
                                 [[:node-create k :map]
                                  [:value k v]]))
                             added))
                   (let [updates (dataflow/updated-inputs inputs)]
                     (mapv (fn [[k v]] [:value (prefixed k prefix) v]) updates))
                   (let [removed (dataflow/removed-inputs inputs)]
                     (mapcat (fn [[k v]]
                               (let [k (prefixed k prefix)]
                                 (if v
                                   [[:value k v]]
                                   [[:value k v] [:node-destroy k]])))
                             removed)))))))

(defmulti ^:private process-app-model-message (fn [state flow message] (msg/type message)))

(defmethod process-app-model-message :default [state flow message]
  state)

(defn- refresh-emitters [state flow]
  (reduce (fn [deltas {in :in init-emitter :init emitter :fn}]
            (let [init-emitter (or init-emitter emitter)
                  dm (:data-model state)
                  inputs {:new-model dm
                          :old-model dm
                          :input-paths in
                          :added in
                          :updated #{}
                          :removed #{}
                          :message (::input state)}]
              (if init-emitter
                (into deltas (init-emitter inputs))
                deltas)))
          []
          (:emit flow)))

(defmethod process-app-model-message :navigate [state flow message]
  (let [deltas (refresh-emitters state flow)
        paths (get-in state [::named-paths (:name message)])
        old-paths (::subscriptions state)
        destroy-paths (remove (set paths) old-paths)]
    (assoc state ::subscriptions paths
           :emit (into (mapv #(vector :navigate-node-destroy %) destroy-paths)
                       deltas))))

;; map :set-focus to :navigate message
(defmethod process-app-model-message :set-focus [state flow message]
  (process-app-model-message state flow (assoc message msg/type :navigate)))

(defmethod process-app-model-message :subscribe [state flow message]
  (let [deltas (refresh-emitters state flow)]
    (-> state
        (update-in [::subscriptions] (fnil into []) (:paths message))
        (assoc :emit deltas))))

(defmethod process-app-model-message :unsubscribe [state flow message]
  (let [paths (set (:paths message))]
    (-> state
        (update-in [::subscriptions] (fn [s] (remove #(contains? paths %) s)))
        (assoc :emit (mapv #(vector :navigate-node-destroy %) paths)))))

(defmethod process-app-model-message :add-named-paths [state flow message]
  (let [{:keys [paths name]} message]
    (assoc-in state [::named-paths name] paths)))

(defmethod process-app-model-message :remove-named-paths [state flow message]
  (let [{:keys [name]} message]
    (update-in state [::named-paths] dissoc name)))

(defn- path-starts-with? [path prefix]
  (= (take (count prefix) path)
     prefix))

(def ^:private special-ops {:navigate-node-destroy :node-destroy})

(defn- filter-deltas [state deltas]
  (let [subscriptions (::subscriptions state)]
    (mapv (fn [[op & xs :as delta]]
            (if (special-ops op) (apply vector (special-ops op) xs) delta))
          (filter (fn [[op path]]
                    (or (special-ops op)
                        (some (fn [s] (path-starts-with? path s)) subscriptions)))
                  (mapcat tree/expand-map deltas)))))

(defn- run-dataflow [state flow message]
  (dataflow/run (dissoc state :effect) flow message))

(defn- process-message
  "Using the given flow, process the given message producing a new
  state."
  [state flow message]
  (let [old-state state
        new-state (cond (= (msg/topic message) msg/app-model)
                        (process-app-model-message state flow message)
                        (= (msg/topic message) msg/output)
                        (assoc state :effect [(:payload message)])
                        :else (run-dataflow state flow message))
        new-deltas (filter-deltas new-state (:emit new-state))]
    (-> new-state
        (assoc ::emitter-deltas new-deltas)
        (dissoc :emit))))

(defn- transact-one [state flow message]
  (process-message (assoc state ::input message) flow message))

(defn- pre-process [flow message]
  (let [{out-path :out key :key} ((:input-adapter flow) message)
        pre-fn (dataflow/find-message-transformer (:pre flow) out-path key)]
    (if pre-fn
      (pre-fn message)
      [message])))

(defn- receive-input-message [state flow input-queue]
  (p/take-message input-queue
                  (fn [message]
                    (if (:pre flow)
                      (doseq [message (pre-process flow message)]
                        (swap! state transact-one flow message))
                      (swap! state transact-one flow message))
                    (receive-input-message state flow input-queue))))

(defn- post-process-effects [flow message]
  (let [post-fn (some (fn [[pred f]] (when (pred message) f))
                      (-> flow :post :effect))]
    (if post-fn
      (post-fn message)
      [message])))

(defn- send-effects [app flow output-queue]
  (add-watch app :effects-watcher
             (fn [_ _ _ new-state]
               (doseq [message (:effect new-state)]
                 (if (-> flow :post :effect)
                   (doseq [message (post-process-effects flow message)]
                     (p/put-message output-queue message))
                   (p/put-message output-queue message))))))

(defn- post-process-deltas [flow deltas]
  (let [post-processors (-> flow :post :app-model)]
    (reduce (fn [acc [op path :as delta]]
              (if-let [post-fn (dataflow/find-message-transformer post-processors path op)]
                (into acc (post-fn delta))
                (conj acc delta)))
            []
            deltas)))

(defn- send-app-model-deltas [app flow app-model-queue]
  (add-watch app :app-model-delta-watcher
             (fn [_ _ old-state new-state]
               (let [deltas (::emitter-deltas new-state)]
                 (when (not (or (empty? deltas)
                                (= (::emitter-deltas old-state) deltas)))
                   (let [deltas (if (-> flow :post :app-model)
                                  (post-process-deltas flow deltas)
                                  deltas)]
                     (p/put-message app-model-queue
                                    {msg/topic msg/app-model
                                     msg/type :deltas
                                     :deltas deltas})))))))

(defn- ensure-default-emitter [emit]
  (if (empty? emit)
    [[#{[:*]} (default-emitter [])]]
    emit))

(defn- ensure-input-adapter [input-adapter]
  (if-not input-adapter
    (fn [m] {:key (msg/type m) :out (msg/topic m)})
    input-adapter))

(defn- rekey-transforms [transforms]
  (mapv #(if (map? %)
           (set/rename-keys % {msg/type :key msg/topic :out})
           %)
        transforms))

(defn- standardize-pre-if-exists [description]
  (if (:pre description)
    (update-in description [:pre] dataflow/transform-maps)
    description))

(defn- standardize-post-app-model-if-exists [description]
  (if (-> description :post :app-model)
    (update-in description [:post :app-model] dataflow/transform-maps)
    description))

(defn build [description]
  (let [app-atom (atom {:data-model {}})
        description (-> description
                        standardize-pre-if-exists
                        standardize-post-app-model-if-exists
                        (update-in [:emit] ensure-default-emitter)
                        (update-in [:input-adapter] ensure-input-adapter)
                        (update-in [:transform] rekey-transforms))
        dataflow (dataflow/build description)
        input-queue (queue/queue :input)
        output-queue (queue/queue :output)
        app-model-queue (queue/queue :app-model)]
    (receive-input-message app-atom dataflow input-queue)
    (send-effects app-atom dataflow output-queue)
    (send-app-model-deltas app-atom dataflow app-model-queue)
    {:state app-atom
     :description description
     :dataflow dataflow
     :input input-queue
     :output output-queue
     :app-model app-model-queue}))

(defn- create-start-messages [focus]
  (into (mapv (fn [[name paths]]
                {msg/topic msg/app-model
                 msg/type :add-named-paths
                 :paths paths
                 :name name})
              (remove (fn [[k v]] (= k :default)) focus))
        (when-let [n (:default focus)]
          [{msg/topic msg/app-model
            msg/type :navigate
            :name n}])))

(defn begin
  ([app]
     (begin app nil))
  ([app start-messages]
     (let [{:keys [description dataflow default-emitter]} app
           start-messages (cond start-messages
                                ;; use the user provided start messages
                                start-messages 
                                
                                (:focus description)
                                ;; create start messages from
                                ;; :focus description
                                (create-start-messages (:focus description))
                                
                                :else
                                ;; subscribe to everything
                                ;; this makes simple one-screen apps
                                ;; easire to confgure
                                [{msg/topic msg/app-model msg/type :subscribe :paths [[]]}])]
       (let [init-messages (vec (mapcat :init (:transform description)))]
         (doseq [message (concat start-messages init-messages)]
           (p/put-message (:input app) message))))))


;; Queue consumers
;; ================================================================================

(defn- consume-output-queue [out-queue in-queue services-fn]
  (p/take-message out-queue
                  (fn [message]
                    (services-fn message in-queue)
                    (consume-output-queue out-queue in-queue services-fn))))

(defn consume-output [app services-fn]
  (consume-output-queue (:output app) (:input app) services-fn))

;; renaming
(defn consume-effects [app services-fn]
    (consume-output app services-fn))


;; Runners
;; ================================================================================

(defn run! [app script]
  (assert (or (vector? script) (list? script)) "The passed script must be a vector or list")
  (assert (every? map? script) "Each element of the passed script must be a map")
  (doseq [message script]
    (p/put-message (:input app) message)))


;; Adapter
;; ================================================================================
;; Convert old versions of dataflow descritpions into the latest
;; version.

;; support new and old description keys
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

(defn- adapt-description [description]
  (-> description
      (assoc :input-adapter (fn [m] {:out [(remove-topic-map m)]
                                    :key (remove-topic-map m)}))
      (update-in [:transform] convert-transform)
      (update-in [:derive] convert-derive)
      (update-in [:continue] convert-continue)
      (update-in [:effect] convert-effect)
      (update-in [:emit] convert-emit)
      remove-empty-vals))

(defn adapt-v1
  "Convert a version 1 dataflow description to the current version."
  [description]
  (let [description (rekey-description description)]
    (adapt-description description)))
