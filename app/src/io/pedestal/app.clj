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
    "Implementaiton of Pedestal's application behavior model. Allows for behavior to be
    written as pure functions linked by a dataflow.

    Application behavior is defined in terms of five functions.

    There is one function for handling input (model), three for
    handling output (output, feedback, and emitter) and one for building
    up dataflows which transform data from the data model to something
    that is easy to consume by one of the output functions.

    * model functions receive messages and produce a new data model

    * output functions generate messages to send to external services

    * view functions transform and combine data models in arbitrary dataflows

    * feedback functions generate new messages as input to models

    * emitter functions generate changes to the application model
    "
    (:require [io.pedestal.app.protocols :as p]
              [io.pedestal.app.messages :as msg]
              [io.pedestal.app.queue :as queue]
              [io.pedestal.app.tree :as tree]))

(defn changed-inputs
  "Given an input map, return the keys of all inputs which have changed."
  [inputs]
  (set (keep (fn [[k v]] (when (not= (:old v) (:new v))) k) inputs)))


;; Default functions
;; ================================================================================

(defn default-output-fn [message old-model new-model]
  nil)

(defn default-view-fn [state input-name old new]
  new)

(defn default-feedback-fn [view-name old-view new-view]
  nil)

(defn default-emitter-fn
  ([inputs]
     (vec (mapcat (fn [[k v]]
                    [[:node-create [k] :map]
                     [:value [k] nil (:new v)]])
                  inputs)))
  ([inputs changed-inputs]
     (mapv (fn [changed-input]
             [:value [changed-input] (:new (get inputs changed-input))])
           changed-inputs)))


;; Create dataflow description
;; ================================================================================

(defn- generate-kw
  "Generate a namespace qualified keyword for a view or emitter which
  does not exist."
  [prefix k]
  (keyword (str "io.pedestal.app/" prefix (name k))))

(defn- views-for-input
  "Return a set of all views names that will be updated when the given input changes."
  [views input-name]
  (let [view-names (map first (filter (fn [[k v]] (contains? (:input v) input-name)) views))]
    (if (empty? view-names)
      #{(generate-kw "view-" input-name)}
      (set view-names))))

(defn- emitters-for-input
  [emitters input]
  (if (and (empty? emitters) (= (:type input) :view))
    #{::default-emitter}
    (set (map first (filter (fn [[k v]] (contains? (:input v) (:k input))) emitters)))))

(defn- add-generated-names
  "Return a set of the keys in coll plus all the keys in each value of
  m."
  [coll m]
  (set (into (keys coll)
             (apply concat (vals m)))))

(defn- add-defaults
  "Return a map of view/emitter names to fuctions and inputs. If a view
  or emitter does not appear in the provided system description then
  use the provided default function."
  [default-fn existing all-names input-map]
  (reduce (fn [a n]
            (if-let [e (get existing n)]
              (assoc a n e)
              (assoc a n {:fn default-fn
                          :input (set (keep (fn [[k v]] (when (contains? v n) k)) input-map))})))
          {}
          all-names))

(defn make-flow
  "Given a description of the relationships between functions in an application,
  generate a data structure which describes how data flows through the
  system.

  This data structure will be used to drive each transaction."
  [description]
  (let [{:keys [models output views feedback emitters]} description
        input->views (reduce (fn [a k] (assoc a k (views-for-input views k)))
                             {}
                             (keys models))
        input->views (reduce (fn [a [input view-name]]
                               (if (contains? views input)
                                 (update-in a [input] (fnil conj #{}) view-name)
                                 a))
                             input->views
                             (for [[k v] views i (:input v)] [i k]))
        all-view-names (add-generated-names views input->views)
        input->emitters (reduce (fn [a input]
                                (let [ss (emitters-for-input emitters input)]
                                  (if (empty? ss) a (assoc a (:k input) ss))))
                              {}
                              (concat (map (fn [x] {:k x :type :view}) all-view-names)
                                      (map (fn [x] {:k x :type :model}) (keys models))))
        all-emitter-names (add-generated-names emitters input->emitters)
        default-emitter (or (:default-emitter description)
                           (first all-emitter-names))]
    {:default-emitter default-emitter
     :models (reduce (fn [a [k v]] (assoc a k (:fn v))) {} models)
     :input->output (reduce (fn [a [k]]
                              (if-let [o (get output k)]
                                (assoc a k o)
                                a))
                            {}
                            (merge models views))
     :input->views input->views
     :view->feedback (reduce (fn [a v] (assoc a v (or (get feedback v) default-feedback-fn)))
                             {}
                             all-view-names)
     :input->emitters input->emitters
     :views (add-defaults default-view-fn views all-view-names input->views)
     :emitters (add-defaults default-emitter-fn emitters all-emitter-names input->emitters)}))


;; Run dataflow
;; ================================================================================

(defn- model-or-view [state k]
  (or (get-in state [:models k])
      (get-in state [:views k])))

(defn- old-and-new [ks o n]
  (reduce (fn [a k]
            (assoc a k {:old (model-or-view o k)
                        :new (model-or-view n k)}))
          {}
          ks))

(defmulti process-app-model-message (fn [state flow message] (msg/type message)))

(defmethod process-app-model-message :default [state flow message]
  state)

;; TODO: Come up with a better way to do this.
;; We should not have to force all of the emitters to run.
(defn- refresh-emitters [state flow]
  (reduce (fn [deltas [emitter-name emitter]]
            (let [view-map (old-and-new (:input emitter) state state)
                  emitter-fn (:fn emitter)]
              (into deltas (emitter-fn view-map))))
          []
          (:emitters flow)))

(defmethod process-app-model-message :navigate [state flow message]
  (let [deltas (refresh-emitters state flow)
        paths (get-in state [:named-paths (:name message)])
        old-paths (:subscriptions state)]
    (assoc state :subscriptions paths
           :deltas (into (mapv #(vector :navigate-node-destroy %) old-paths)
                         deltas))))

;; map :set-focus to :navigate message
(defmethod process-app-model-message :set-focus [state flow message]
  (process-app-model-message state flow (assoc message msg/type :navigate)))

(defmethod process-app-model-message :subscribe [state flow message]
  (let [deltas (refresh-emitters state flow)]
    (-> state
        (update-in [:subscriptions] (fnil into []) (:paths message))
        (assoc :deltas deltas))))

(defmethod process-app-model-message :unsubscribe [state flow message]
  (let [paths (set (:paths message))]
    (-> state
        (update-in [:subscriptions] (fn [s] (remove #(contains? paths %) s)))
        (assoc :deltas (mapv #(vector :navigate-node-destroy %) paths)))))

(defmethod process-app-model-message :add-named-paths [state flow message]
  (let [{:keys [paths name]} message]
    (assoc-in state [:named-paths name] paths)))

(defmethod process-app-model-message :remove-named-paths [state flow message]
  (let [{:keys [name]} message]
    (update-in state [:named-paths] dissoc name)))

(defn get-receiver [message]
  (let [to (msg/topic message)]
    (if (keyword? to)
      to
      (or (:node to) (:model to) (:service to)))))

(defn run-model [state flow model-name message]
  (assert (get-in flow [:models model-name])
          (str "Model with name " model-name " does not exist. Message is " message))
  (let [model-fn (get-in flow [:models model-name])]
    (update-in state [:models model-name] model-fn message)))

(defn run-output [state old-state flow input-name message]
  (let [output-fn (get-in flow [:input->output input-name])
        old-model (model-or-view old-state input-name)
        new-model (model-or-view state input-name)
        out (when output-fn (output-fn message old-model new-model))
        out (if (vector? out) {:output out} out)]
    (if out
      (-> state
          (update-in [:output] into (:output out))
          (update-in [:feedback] into (:feedback out)))
      state)))

(defn run-outputs [state old-state flow message modified-inputs]
  (reduce (fn [new-state [input-name]]
            (if (get-in flow [:input->output input-name])
              (run-output new-state old-state flow input-name message)
              new-state))
          state
          modified-inputs))

(defn topo-sort [flow view-names]
  (let [c (fn [a b]
            (let [deps-a (get-in flow [:input->views a])
                  deps-b (get-in flow [:input->views b])]
              (or (contains? deps-a b)
                  (and (not (nil? deps-a)) (nil? deps-b))
                  (= deps-a deps-b))))]
    (sort c view-names)))

(defn- run-all-views-for-input [state old-state flow view-names]
  (reduce (fn [new-state view-name]
            (let [view (get-in flow [:views view-name])
                  old-view (get-in old-state [:views view-name])
                  input-map (old-and-new (:input view) old-state new-state)
                  view-fn (:fn view)
                  new-view (if (= (count input-map) 1)
                             (let [[k v] (first input-map)]
                               (view-fn old-view k (:old v) (:new v)))
                             (view-fn old-view input-map))]
              (assoc-in new-state [:views view-name] new-view)))
          state
          view-names))

(defn run-views [state old-state flow input-name state-key]
  (let [view-names (topo-sort flow (get-in flow [:input->views input-name]))
        old-in-state (get-in old-state [state-key input-name])
        new-in-state (get-in state [state-key input-name])]
    (if (not (or (empty? view-names)
                 (= old-in-state new-in-state)))
      (let [new-state (run-all-views-for-input state
                                               old-state
                                               flow
                                               view-names)]
        (reduce (fn [s view-name]
                  (run-views s state flow view-name :views))
                new-state
                view-names))
      state)))

(defn run-feedback [state old-state flow modified-inputs]
  (reduce (fn [s [view-name {:keys [old new]}]]
            (let [feedback-fn (get-in flow [:view->feedback view-name])
                  feedback (feedback-fn view-name old new)]
              (if feedback
                (update-in s [:feedback] into feedback)
                s)))
          state
          modified-inputs))

(defn run-emitters [state old-state flow modified-inputs]
  (let [affected-emitters (set (apply concat (vals (select-keys (:input->emitters flow)
                                                                (keys modified-inputs)))))]
    (reduce (fn [new-state emitter-key]
              (let [emitter (get-in flow [:emitters emitter-key])
                    view-map (old-and-new (:input emitter) old-state new-state)
                    changed-inputs (set (keys (select-keys modified-inputs (:input emitter))))]
                (if (not (empty? changed-inputs))
                  (let [emitter-fn (:fn emitter)
                        deltas (emitter-fn view-map changed-inputs)]
                    (update-in new-state [:deltas] (fnil into []) deltas))
                  new-state)))
            state
            affected-emitters)))

(defn- find-modified-inputs [type o n]
  (let [n-views (get n type)
        o-views (get o type)]
    (reduce (fn [a [k v]]
              (if (not= v (get o-views k))
                (assoc a k {:old (get o-views k)
                            :new v})
                a))
            {}
            n-views)))

(defn- run-dataflow
  "Starting with the given input message, run the dataflow producing a
  new state."
  [state flow message]
  (let [old-state state
        model-name (get-receiver message)
        new-state (-> state
                      (run-model flow model-name message)
                      (run-views old-state flow model-name :models))
        modified-views (find-modified-inputs :views old-state new-state)
        modified-models (find-modified-inputs :models old-state new-state)
        result (-> new-state
                   (run-feedback old-state flow modified-views)
                   (run-emitters old-state flow (merge modified-views modified-models)))]
    (if (not (empty? (:feedback result)))
      (reduce (fn [s message]
                (run-dataflow (assoc s :feedback []) flow message))
              result
              (:feedback result))
      result)))

(defn- path-starts-with? [path prefix]
  (= (take (count prefix) path)
     prefix))

(def special-ops {:navigate-node-destroy :node-destroy})

(defn filter-deltas [state deltas]
  (let [subscriptions (:subscriptions state)]
    (mapv (fn [[op & xs :as delta]]
            (if (special-ops op) (apply vector (special-ops op) xs) delta))
          (filter (fn [[op path]]
                    (or (special-ops op)
                        (some (fn [s] (path-starts-with? path s)) subscriptions)))
                  (mapcat tree/expand-map deltas)))))

(defn process-message
  "Using the given flow, process the given message producing a new
  state."
  [state flow message]
  (let [old-state state
        new-state (if (= (msg/topic message) msg/app-model)
                    (process-app-model-message state flow message)
                    (run-dataflow state flow message))
        new-deltas (filter-deltas new-state (:deltas new-state))
        modified-views (find-modified-inputs :views old-state new-state)
        modified-models (find-modified-inputs :models old-state new-state)
        result (run-outputs new-state old-state flow message
                            (merge modified-views modified-models))]
    (-> result
        (assoc :emitter-deltas new-deltas)
        (dissoc :deltas))))

(defn pre-tx-state [state]
  (assoc state
    :output []
    :feedback []))

(defn transact-one [state flow message]
  (process-message (assoc (pre-tx-state state) :input message) flow message))

(defn transact-many [state flow messages]
  (reduce (fn [a message]
            (process-message (assoc a :input message) flow message))
          (pre-tx-state state)
          messages))


;; Build and interface with the outside world
;; ================================================================================

(defn- receive-input-message [state flow input-queue]
  (p/take-message input-queue
                  (fn [message]
                    (swap! state transact-one flow message)
                    (receive-input-message state flow input-queue))))

(defn- send-output [app output-queue]
  (add-watch app :output-watcher
             (fn [_ _ _ new-state]
               (let [out (:output new-state)]
                 (doseq [message out]
                   (p/put-message output-queue message))))))

(defn- send-app-model-deltas [app app-model-queue]
  (add-watch app :app-model-delta-watcher
             (fn [_ _ old-state new-state]
               (let [deltas (:emitter-deltas new-state)]
                 (when (not (or (empty? deltas)
                                (= (:emitter-deltas old-state) deltas)))
                   (p/put-message app-model-queue
                                  {msg/topic msg/app-model
                                   msg/type :deltas
                                   :deltas deltas}))))))

;; support new and old description keys
(defn- rekey-description
  [description]
  (let [key-map {:transform :models
                 :combine :views
                 :emit :emitters
                 :treeify :emitters
                 :effect :output
                 :continue :feedback
                 :focus :navigation}
        key-values (vals key-map)]
    (into {} (map (fn [[k v]] [(or (key-map k)
                                   (some #{k} key-values)) v]) description))))

(defn build
  "Given a map which describes the application and a renderer, return
  a new application. The returned application map contains input and
  output queues for sending and receiving messages.

  The description map contains a subset of the keys:
  :models, :output, :views, :feedback, :emitters and :navigation."
  [description]
  (let [description (rekey-description description)
        app-atom (atom {:output [] :feedback []})
        flow (make-flow description)
        input-queue (queue/queue :input)
        output-queue (queue/queue :output)
        app-model-queue (queue/queue :app-model)]
    (receive-input-message app-atom flow input-queue)
    (send-output app-atom output-queue)
    (send-app-model-deltas app-atom app-model-queue)
    {:state app-atom
     :description description
     :flow flow
     :default-emitter (:default-emitter flow)
     :input input-queue
     :output output-queue
     :app-model app-model-queue}))

(defn- create-start-messages [navigation]
  (into (mapv (fn [[name paths]]
                {msg/topic msg/app-model
                 msg/type :add-named-paths
                 :paths paths
                 :name name})
              (remove (fn [[k v]] (= k :default)) navigation))
        (when-let [n (:default navigation)]
          [{msg/topic msg/app-model
            msg/type :navigate
            :name n}])))

(defn begin
  ([app]
     (begin app nil))
  ([app start-messages]
     (let [{:keys [description flow default-emitter]} app
           start-messages (cond start-messages
                                ;; use the user provided start messages
                                start-messages 
                                
                                (:navigation description)
                                ;; create start messages from
                                ;; :navigation description
                                (create-start-messages (:navigation description))
                                
                                :else
                                ;; subscribe to everything
                                ;; this makes simple one-screen apps
                                ;; easire to confgure
                                [{msg/topic msg/app-model msg/type :subscribe :paths [[]]}])]
       (let [init-messages (reduce (fn [a [model-name init-value]]
                                     (conj a {msg/topic model-name
                                              msg/type msg/init
                                              :value init-value}))
                                   []
                                   (map (fn [[k v]] [k (:init v)]) (:models description)))]
         (swap! (:state app) transact-many flow init-messages))
       (swap! (:state app) transact-many flow start-messages))))

(defn run! [app script]
  (assert (or (vector? script) (list? script)) "The passed script must be a vector or list")
  (assert (every? map? script) "Each element of the passed script must be a map")
  (doseq [message script]
    (p/put-message (:input app) message)))


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
