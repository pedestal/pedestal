;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.render.test.push
  "Test rendering code with an artificial DOM."
  (:require [io.pedestal.app.render.test.dom :as d]
            [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg])
  (:use io.pedestal.app.render.push
        clojure.test))

;; Handlers
;; ================================================================================

(deftest test-add-handler
  (is (= (add-handler {} :* [:a :b :c] :x)
         {:* {:children
              {:a {:children
                   {:b {:children
                        {:c {:handler :x}}}}}}}}))
  (is (= (-> {}
             (add-handler :node-create [] :a)
             (add-handler :node-create [:**] :b))
         {:node-create {:handler :a
                       :children {:** {:handler :b}}}})))

(deftest test-add-handlers
  (is (= (add-handlers [[:* [:a :b :c] :x]])
         {:* {:children
              {:a {:children
                   {:b {:children
                        {:c {:handler :x}}}}}}}}))
  (is (= (add-handlers [[:node-create [] :a]
                        [:node-create [:**] :b]])
         {:node-create {:handler :a
                       :children {:** {:handler :b}}}})))

(deftest test-find-handler
  (let [handlers
        (-> {}
            (add-handler :node-create      []                :_node-enter-root)
            (add-handler :node-create      [:**]             :_node-enter-any)
            (add-handler :node-create      [:a :b :*]        :_node-enter-a-b-any)
            (add-handler :transform-enable [:a :b :*]        :_transform-enter-a-b-any)
            (add-handler :transform-*          [:a :b :*]    :_transform-any-a-b-any)
            (add-handler :transform-enable [:a :b :* :d :e]  :_transform-enter-a-b-any-d-e)
            (add-handler :transform-enable [:* :* :* :d :*]  :_transform-enter-any-any-any-d-any)
            (add-handler :transform-enable [:* :* :* :f :**] :_transform-enter-any-any-any-f-any)
            (add-handler :transform-enable [:a :b :* :d :e]  :_transform-enter-a-b-any-d-e))]
    (is (= (find-handler handlers :node-create [])
           :_node-enter-root))
    (is (= (find-handler handlers :node-create [:a :b :c :d])
           :_node-enter-any))
    (is (= (find-handler handlers :transform-enable [:a :b :c])
           :_transform-enter-a-b-any))
    (is (= (find-handler handlers :transform-enable [:a :b :g])
           :_transform-enter-a-b-any))
    (is (= (find-handler handlers :transform-disable [:a :b :g])
           :_transform-any-a-b-any))
    (is (= (find-handler handlers :transform-enable [:a :b :c :d :e])
           :_transform-enter-a-b-any-d-e))
    (is (= (find-handler handlers :transform-enable [:a :b :c :d :e])
           :_transform-enter-a-b-any-d-e))
    (is (= (find-handler handlers :transform-enable [:z :b :c :d :e])
           :_transform-enter-any-any-any-d-any))
    (is (= (find-handler handlers :transform-enable [:a :b :c :f :e])
           :_transform-enter-any-any-any-f-any))
    (is (= (find-handler handlers :node-create [:a :b :c])
           :_node-enter-a-b-any))))

;; Rendering
;; ================================================================================

(deftest test-render-add
  
  (binding [d/*dom* (d/test-dom)]
    
    (let [;; create handlers
          r-enter (fn [r _ _] (d/set-attrs! :root {:class "active"}))
          a-enter (fn [r [o p _ v] _] (d/append! :root {:content v :attrs {:id :a}}))
          ;; create listeners
          ls (-> {}
                 (add-handler :node-create [:a] r-enter)
                 (add-handler :value [:a] a-enter))
          ;; create renderer
          r (renderer :root ls)]
      
      (r [[:node-create [:a] :map]
          [:value [:a] nil 1]]
         nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root :class "active"}
              :children [{:content 1
                          :attrs {:id :a}}]})))))

(deftest test-working-with-ids
  (let [r (->DomRenderer (atom {:id :root}))]
    (is (nil? (get-parent-id r [])))
    (is (= :root (get-parent-id r [:a])))
    (let [root-id (new-id! r [])]
      (is (= root-id (get-parent-id r [:a])))
      (let [a-id (new-id! r [:a])
            b-id (new-id! r [:b] :b)
            c-id (new-id! r [:b :c])]
        (is (= :b b-id))
        (is (= a-id (get-id r [:a])))
        (is (= :b (get-id r [:b])))
        (is (= c-id (get-id r [:b :c])))
        (is (= :b (get-id r [:b]) (get-parent-id r [:b :c])))

        (delete-id! r [:a])
        
        (is (nil? (get-id r [:a])))
        (is (= c-id (get-id r [:b :c])))
        
        (delete-id! r [:b])
        (is (nil? (get-id r [:a])))
        (is (nil? (get-id r [:b :c])))))))

(deftest test-render-build-up-tear-down

  (binding [d/*dom* (d/test-dom)]
    
    (let [;; create handlers
          a-enter (fn [r [_ path] _]
                    (let [parent (get-parent-id r path)
                          id (new-id! r path :a)]
                      (d/append! parent {:content nil :attrs {:id id}})))
          
          b-enter (fn [r [_ path] _]
                    (let [parent (get-parent-id r path)
                          id (new-id! r path :b)
                          list-id (new-id! r (conj path :c) :attr-list)]
                      (d/append! parent {:content "Attributes List"
                                         :attrs {:id id}})
                      (d/append! id {:content nil
                                     :attrs {:id list-id :class "list"}
                                     :children []})))
          
          c-update (fn [r [_ path _ v] _]
                     (let [id (get-id r path)]
                       (d/destroy-children! id)
                       (doseq [x (sort v)]
                         (d/append! id {:content x}))))
          
          c-exit (fn [r [_ path] _]
                   (d/destroy-children! (get-id r path)))
          ;; create listeners
          ls (-> {}
                 (add-handler :node-create [:a] a-enter)
                 (add-handler :node-create [:a :b] b-enter)
                 (add-handler :value [:a :b :c] c-update)
                 (add-handler :node-destroy [:a :b :c] c-exit)
                 (add-handler :node-destroy [:**] d/default-exit))
          ;; create renderer
          r (renderer :root ls)]
      
      (r [[:node-create [:a] :map]] nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil :attrs {:id :a}}]}))

      (r [[:node-create [:a :b] :map]] nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children
              [{:content nil
                :attrs {:id :a}
                :children
                [{:content "Attributes List"
                  :attrs {:id :b}
                  :children
                  [{:content nil
                    :attrs {:id :attr-list :class "list"}
                    :children []}]}]}]}))
      
      (r [[:node-create [:a :b :c] :map]
          [:value [:a :b :c] nil [:x :y]]]
         nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children
              [{:content nil
                :attrs {:id :a}
                :children
                [{:content "Attributes List"
                  :attrs {:id :b}
                  :children
                  [{:content nil
                    :attrs {:id :attr-list :class "list"}
                    :children [{:content :x}
                               {:content :y}]}]}]}]}))
      
      (r [[:value [:a :b :c] nil [:z :x :y]]]
         nil)

      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children
              [{:content nil
                :attrs {:id :a}
                :children
                [{:content "Attributes List"
                  :attrs {:id :b}
                  :children
                  [{:content nil
                    :attrs {:id :attr-list :class "list"}
                    :children [{:content :x}
                               {:content :y}
                               {:content :z}]}]}]}]}))
      
      (r [[:node-destroy [:a :b :c]]] nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children
              [{:content nil
                :attrs {:id :a}
                :children
                [{:content "Attributes List"
                  :attrs {:id :b}
                  :children
                  [{:content nil
                    :attrs {:id :attr-list :class "list"}
                    :children []}]}]}]}))

      (r [[:node-destroy [:a :b]]] nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil
                          :attrs {:id :a}
                          :children []}]}))
      
      (r [[:node-destroy [:a]]] nil)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children []})))))

(defrecord TestQueue [action]
  p/PutMessage
  (put-message [this message]
    (reset! action message)))

(defn event-messages [events transform-name env]
  (assert (contains? events transform-name) (str "There is no event named " transform-name))
  (map (partial msg/add-message-type transform-name) (transform-name events)))

(deftest test-render-timeline
  
  (binding [d/*dom* (d/test-dom)]
    
    (let [ ;; create handlers
          t-enter (fn [r [_ path] _]
                    (let [parent (get-parent-id r path)
                          id (new-id! r path :timeline)]
                      (d/append! parent {:content nil :attrs {:id id}})))
          
          chart-enter (fn [r [_ path] d]
                        (let [parent (get-parent-id r path)
                              id (new-id! r path :chart)]
                          (d/append! parent {:content "Timeline Chart" :attrs {:id id}})))
          
          chart-event-enter (fn [r [_ path transform-name msgs] d]
                              (let [id (get-id r path)]
                                (d/listen! id
                                           :click
                                           (fn [e]
                                             (p/put-message d (event-messages {transform-name msgs}
                                                                              :group-selected
                                                                              {}))))
                                (on-destroy! r path #(d/unlisten! id :click))))
          
          data-enter (fn [r [_ path] _]
                       (let [parent (get-parent-id r path)
                             id (new-id! r path :chart-content)]
                         (d/append! parent {:content nil :attrs {:id id}})))
          
          add-chart-data-node (fn [r [_ path] _]
                                (let [parent (get-parent-id r path)
                                      id (new-id! r path (keyword (str "chart-data-" (last path))))]
                                  (d/append! parent {:content nil :attrs {:id id}})))
          
          data-update (fn [r [_ path _ v] _]
                        (let [parent (get-parent-id r path)
                              id (d/nth-child-id parent (last path))]
                          (d/set-content! id v)))
          
          bb-enter (fn [r delta d]
                     (let [[op path] delta
                           parent (get-parent-id r path)
                           id (get-id r (conj path :back-button))
                           id (or id (new-id! r path :back-button))]
                       (condp = op
                         :node-create (d/append! parent {:attrs {:id id :class :button}})
                         :value (d/set-content! id (last delta))
                         :transform-enable
                         (let [[_ _ transform-name msgs] delta]
                           (d/listen! id
                                      :click
                                      (fn [e]
                                        (p/put-message d (event-messages {transform-name msgs} :nav {}))))
                           (on-destroy! r path #(d/unlisten! id :click))))))
          
          ;; create listeners
          ls (-> {}
                 (add-handler :node-create [:t] t-enter)
                 (add-handler :node-create [:t :chart] chart-enter)
                 (add-handler :node-create [:t :chart :data] data-enter)
                 (add-handler :node-create [:t :chart :data :*] add-chart-data-node)
                 (add-handler :value [:t :chart :data :*] data-update)
                 (add-handler :transform-enable [:t :chart] chart-event-enter)
                 (add-handler :* [:t :chart :back-button] bb-enter)
                 (add-handler :node-destroy [:**] d/default-exit))
          ;; create a mock input-queue
          last-user-action (atom nil)
          input-queue (->TestQueue last-user-action)
          ;; create renderer
          r (renderer :root ls)]

      ;; render the page
      (r [[:node-create [:t] :map]] input-queue)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil :attrs {:id :timeline}}]}))

      ;; check that no events are available
      (d/click! :back-button)
      
      (is (= @last-user-action nil))

      ;; render the chart
      (r [[:node-create [:t :chart] :map]
          [:transform-enable [:t :chart] :group-selected [{msg/topic :timeline (msg/param :group-id) {}}]]
          [:node-create [:t :chart :data] :vector]
          [:node-create [:t :chart :back-button] :map]
          [:value [:t :chart :back-button] nil "Back to Index"]
          [:transform-enable [:t :chart :back-button] :nav [{:page :attributes}]]]
         input-queue)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil
                          :attrs {:id :timeline}
                          :children [{:content "Timeline Chart"
                                      :attrs {:id :chart}
                                      :children [{:content nil
                                                  :attrs {:id :chart-content}}
                                                 {:content "Back to Index"
                                                  :attrs {:id :back-button :class :button}}]}]}]}))

      ;; check that events are hooked up
      (d/click! :back-button)
      
      (is (= @last-user-action [{msg/type :nav :page :attributes}]))
      
      (d/click! :chart)
      
      (is (= @last-user-action [{msg/type :group-selected msg/topic :timeline (msg/param :group-id) {}}]))

      ;; add a group to the chart
      (r [[:node-create [:t :chart :data 0] :map]
          [:value [:t :chart :data 0] nil {:group-id 0 :tx-count 1}]]
         input-queue)

      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil
                          :attrs {:id :timeline}
                          :children [{:content "Timeline Chart"
                                      :attrs {:id :chart}
                                      :children [{:content nil
                                                  :attrs {:id :chart-content}
                                                  :children [{:attrs {:id :chart-data-0}
                                                              :content {:group-id 0 :tx-count 1}}]}
                                                 {:content "Back to Index"
                                                  :attrs {:id :back-button :class :button}}]}]}]}))

      ;; update a group
      (r [[:value [:t :chart :data 0] nil {:group-id 0 :tx-count 2}]]
         input-queue)

      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil
                          :attrs {:id :timeline}
                          :children [{:content "Timeline Chart"
                                      :attrs {:id :chart}
                                      :children [{:content nil
                                                  :attrs {:id :chart-content}
                                                  :children [{:attrs {:id :chart-data-0}
                                                              :content {:group-id 0 :tx-count 2}}]}
                                                 {:content "Back to Index"
                                                  :attrs {:id :back-button :class :button}}]}]}]}))

      ;; add another group
      (r [[:node-create [:t :chart :data 1] :map]
          [:value [:t :chart :data 1] nil {:group-id 1 :tx-count 3}]]
         input-queue)

      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil
                          :attrs {:id :timeline}
                          :children [{:content "Timeline Chart"
                                      :attrs {:id :chart}
                                      :children [{:content nil
                                                  :attrs {:id :chart-content}
                                                  :children [{:attrs {:id :chart-data-0}
                                                              :content {:group-id 0 :tx-count 2}}
                                                             {:attrs {:id :chart-data-1}
                                                              :content {:group-id 1 :tx-count 3}}]}
                                                 {:content "Back to Index"
                                                  :attrs {:id :back-button :class :button}}]}]}]}))

      ;; remove a group
      (r [[:node-destroy [:t :chart :data 0]]]
         input-queue)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil
                          :attrs {:id :timeline}
                          :children [{:content "Timeline Chart"
                                      :attrs {:id :chart}
                                      :children [{:content nil
                                                  :attrs {:id :chart-content}
                                                  :children [{:attrs {:id :chart-data-1}
                                                              :content {:group-id 1 :tx-count 3}}]}
                                                 {:content "Back to Index"
                                                  :attrs {:id :back-button :class :button}}]}]}]}))

      ;; remove the timeline chart
      (r [[:node-destroy [:t :chart]]]
         input-queue)

      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children [{:content nil :attrs {:id :timeline} :children []}]}))
      
      ;; check that all events have been removed
      (p/put-message input-queue nil)
      (is (nil? @last-user-action))
      (d/click! :back-button)
      (is (nil? @last-user-action))
      (d/click! :chart)
      (is (nil? @last-user-action))

      ;; remove the page
      (r [[:node-destroy [:t]]]
         input-queue)
      
      (is (= (:root @d/*dom*)
             {:content nil
              :attrs {:id :root}
              :children []})))))
