;; tag::ns[]
(ns main
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as test]))
;; end::ns[]

;; tag::response_partials[]
(defn response [status body & {:as headers}]
  {:status status :body body :headers headers})

(def ok (partial response 200))
(def created (partial response 201))
(def accepted (partial response 202))
;; end::response_partials[]

;; tag::repository[]
(defonce database (atom {}))                                ;; <1>
;; end::repository[]

;; tag::db_interceptor[]
(def db-interceptor
  {:name :database-interceptor
   :enter
   (fn [context]
     (update context :request assoc :database @database))   ;; <1>
   :leave
   (fn [context]
     (if-let [[op & args] (:tx-data context)]               ;; <2>
       (do
         (apply swap! database op args)                     ;; <3>
         (assoc-in context [:request :database] @database)) ;; <4>
       context))})                                          ;; <5>
;; end::db_interceptor[]

;; tag::list_create[]
(defn make-list [nm]
  {:name nm
   :items {}})

(defn make-list-item [nm]
  {:name nm
   :done? false})

(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [nm (get-in context [:request :query-params :name] "Unnamed List") ;; <1>
           new-list (make-list nm)
           db-id (str (gensym "l"))]                        ;; <2>
       (assoc context :tx-data [assoc db-id new-list])))})  ;; <3>
;; end::list_create[]

;; tag::routes[]
(def echo
  {:name :echo
   :enter
   (fn [context]
     (let [request (:request context)
           response (ok context)]
       (assoc context :response response)))})

(def routes
  (route/expand-routes
    #{["/todo" :post echo :route-name :list-create]
      ["/todo" :get echo :route-name :list-query-form]
      ["/todo/:list-id" :get echo :route-name :list-view]
      ["/todo/:list-id" :post echo :route-name :list-item-create]
      ["/todo/:list-id/:item-id" :get echo :route-name :list-item-view]
      ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
      ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]}))
;; end::routes[]

;; tag::list_create_with_response[]
(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [nm (get-in context [:request :query-params :name] "Unnamed List")
           new-list (make-list nm)
           db-id (str (gensym "l"))
           url (route/url-for :list-view :params {:list-id db-id})] ;; <1>
       (assoc context
              :response (created new-list "Location" url)
              :tx-data [assoc db-id new-list])))})
;; end::list_create_with_response[]

;; tag::routes_with_list_create[]
(def routes
  (route/expand-routes
    #{["/todo" :post [db-interceptor list-create]]          ;; <4>
      ["/todo" :get echo :route-name :list-query-form]
      ["/todo/:list-id" :get echo :route-name :list-view]
      ["/todo/:list-id" :post echo :route-name :list-item-create]
      ["/todo/:list-id/:item-id" :get echo :route-name :list-item-view]
      ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
      ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]}))
;; end::routes_with_list_create[]

;; tag::list_view[]
(defn find-list-by-id [dbval db-id]
  (get dbval db-id))                                        ;; <1>

(def list-view
  {:name :list-view
   :enter
   (fn [context]
     (let [db-id (get-in context [:request :path-params :list-id]) ;; <2>
           the-list (when db-id
                      (find-list-by-id                      ;; <3>
                        (get-in context [:request :database])
                        db-id))]
       (cond-> context                                      ;; <4>
         the-list (assoc :result the-list))))})
;; end::list_view[]


;; tag::list_item_create[]
(defn find-list-item-by-ids [dbval list-id item-id]
  (get-in dbval [list-id :items item-id] nil))

(def entity-render                                          ;; <1>
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})

(def list-item-view                                         ;; <2>
  {:name :list-item-view
   :leave
   (fn [context]
     (let [list-id (get-in context [:request :path-params :list-id])
           item-id (and list-id
                        (get-in context [:request :path-params :item-id]))
           item (and item-id
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
       (let [nm (get-in context [:request :query-params :name] "Unnamed Item")
             new-item (make-list-item nm)
             item-id (str (gensym "i"))]
         (-> context
             (assoc :tx-data [list-item-add list-id item-id new-item])
             (assoc-in [:request :path-params :item-id] item-id))) ;; <5>
       context))})

(def routes
  (route/expand-routes
    #{["/todo" :post [db-interceptor list-create]]
      ["/todo" :get echo :route-name :list-query-form]
      ["/todo/:list-id" :get [entity-render db-interceptor list-view]]
      ["/todo/:list-id" :post [entity-render list-item-view db-interceptor list-item-create]]
      ["/todo/:list-id/:item-id" :get [entity-render list-item-view db-interceptor]]
      ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
      ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]}))
;; end::list_item_create[]

;; tag::server[]
(def service-map
  {::http/routes routes
   ::http/type :jetty
   ::http/port 8890})

(defn start []
  (http/start (http/create-server service-map)))

;; For interactive development
(defonce server (atom nil))                                 ;; <1>

(defn start-dev []
  (reset! server                                            ;; <2>
          (http/start (http/create-server
                        (assoc service-map
                               ::http/join? false)))))      ;; <3>

(defn stop-dev []
  (http/stop @server))

(defn restart []                                            ;; <4>
  (stop-dev)
  (start-dev))
;; end::server[]

;; tag::test_request[]
(defn test-request [verb url]
  (io.pedestal.test/response-for (::http/service-fn @server) verb url))
;; end::test_request[]
