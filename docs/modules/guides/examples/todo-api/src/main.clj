;; tag::ns[]
(ns main
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]
            [io.pedestal.http.route :as route]
            [io.pedestal.connector.test :as test]))

;; end::ns[]

;; tag::response_partials[]
(defn response [status body & {:as headers}]
  {:status status :body body :headers headers})

(def ok (partial response 200))

(def created (partial response 201))

(def accepted (partial response 202))

;; end::response_partials[]

;; tag::repository[]
(defonce *database (atom {}))                               ;; <1>

;; end::repository[]

;; tag::db_interceptor[]
(def db-interceptor
  {:name :db-interceptor
   :enter
   (fn [context]
     (update context :request assoc :database @*database))  ;; <1>
   :leave
   (fn [context]
     (if-let [tx-data (:tx-data context)]                   ;; <2>
       (let [database' (apply swap! *database tx-data)]     ;; <3>
         (assoc-in context [:request :database] database')) ;; <4>
       context))})                                          ;; <5>

;; end::db_interceptor[]

;; tag::list_create_preamble[]
(defn make-list [list-name]
  {:name  list-name
   :items {}})

(defn make-list-item [item-name]
  {:name  item-name
   :done? false})

;; end::list_create_preamble[]

;; tag::list_create[]
(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [list-name (get-in context [:request :query-params :name] "Unnamed List") ;; <1>
           new-list  (make-list list-name)
           db-id     (str (gensym "l"))]                    ;; <2>
       (assoc context :tx-data [assoc db-id new-list])))})  ;; <3>

;; end::list_create[]

;; tag::echo[]
(def echo
  {:name :echo
   :enter
   (fn [context]
     (let [request  (:request context)
           response (ok request)]
       (assoc context :response response)))})

;; end::echo[]

;; tag::routes[]
(def routes
  #{["/todo" :post echo :route-name :list-create]
    ["/todo" :get echo :route-name :list-query-form]
    ["/todo/:list-id" :get echo :route-name :list-view]
    ["/todo/:list-id" :post echo :route-name :list-item-create]
    ["/todo/:list-id/:item-id" :get echo :route-name :list-item-view]
    ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
    ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]})

;; end::routes[]

;; tag::list_create_with_response[]
(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [list-name (get-in context [:request :query-params :name] "Unnamed List")
           new-list  (make-list list-name)
           db-id     (str (gensym "l"))
           url       (route/url-for :list-view :params {:list-id db-id})] ;; <1>
       (assoc context
              :response (created new-list "Location" url)   ;; <2>
              :tx-data [assoc db-id new-list])))})

;; end::list_create_with_response[]

;; tag::routes_with_list_create[]
(def routes
  #{["/todo" :post [db-interceptor
                    list-create]]            ;; <4>
    ["/todo" :get echo :route-name :list-query-form]
    ["/todo/:list-id" :get echo :route-name :list-view]
    ["/todo/:list-id" :post echo :route-name :list-item-create]
    ["/todo/:list-id/:item-id" :get echo :route-name :list-item-view]
    ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
    ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]})

;; end::routes_with_list_create[]

;; tag::list_view[]
(defn find-list-by-id [dbval db-id]
  (get dbval db-id))                                        ;; <1>

(def list-view
  {:name :list-view
   :enter
   (fn [context]
     (let [db-id    (get-in context [:request :path-params :list-id]) ;; <2>
           the-list (when db-id
                      (find-list-by-id                      ;; <3>
                        (get-in context [:request :database])
                        db-id))]
       (cond-> context                                      ;; <4>
         the-list (assoc :result the-list))))})

;; end::list_view[]

;; tag::entity_render[]
(def entity-render                                          ;; <1>
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})

;; end::entity_render[]

;; tag::entity_render_routes[]
(def routes
  #{["/todo" :post [db-interceptor
                    list-create]]
    ["/todo" :get [entity-render db-interceptor list-view]]
    ["/todo/:list-id" :get echo :route-name :list-view]
    ["/todo/:list-id" :post echo :route-name :list-item-create]
    ["/todo/:list-id/:item-id" :get echo :route-name :list-item-view]
    ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
    ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]})

;; end::entity_render_routes[]

;; tag::list_item_create[]
(defn find-list-item-by-ids [dbval list-id item-id]
  (get-in dbval [list-id :items item-id] nil))

(def list-item-view                                         ;; <2>
  {:name :list-item-view
   :leave
   (fn [context]
     (let [list-id (get-in context [:request :path-params :list-id])
           item-id (and list-id
                        (get-in context [:request :path-params :item-id]))
           item    (and item-id
                        (find-list-item-by-ids (get-in context [:request :database]) list-id item-id))]
       (cond-> context
         item (assoc :result item))))})                     ;; <3>

(defn list-item-add
  [dbval list-id item-id new-item]
  (if (contains? dbval list-id)
    (assoc-in dbval [list-id :items item-id] new-item)
    dbval))

(def list-item-create                                       ;; <4>
  {:name :list-item-create
   :enter
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (let [item-name       (get-in context [:request :query-params :name] "Unnamed Item")
             new-item (make-list-item item-name)
             item-id  (str (gensym "i"))]
         (-> context
             (assoc :tx-data [list-item-add list-id item-id new-item])
             (assoc-in [:request :path-params :item-id] item-id))) ;; <5>
       context))})

;; end::list_item_create[]

;; tag::list_item_create_routes[]
(def routes
  #{["/todo" :post [db-interceptor list-create]]
    ["/todo" :get echo :route-name :list-query-form]
    ["/todo/:list-id" :get [entity-render db-interceptor list-view]]
    ["/todo/:list-id" :post [entity-render list-item-view db-interceptor list-item-create]]
    ["/todo/:list-id/:item-id" :get [entity-render db-interceptor list-item-view]]
    ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
    ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]})

;; end::list_item_create_routes[]

;; tag::connector[]
(defn create-connector []
  (-> (conn/default-connector-map 8890)
      (conn/with-default-interceptors)
      (conn/with-routes routes)
      (hk/create-connector nil)))

(defonce *connector (atom nil))                             ;; <1>

(defn start []
  (reset! *connector                                        ;; <2>
          (conn/start! (create-connector))))

(defn stop []
  (conn/stop! @*connector)
  (reset! *connector nil))

(defn restart []                                            ;; <3>
  (stop)
  (start))

;; end::connector[]

;; tag::test_request[]
(defn test-request [verb url]
  (test/response-for @*connector verb url))
;; end::test_request[]
