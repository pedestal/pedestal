(ns ion-provider.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.ions :as provider]
            [ion-provider.datomic]
            [ring.util.response :as ring-resp]
            [datomic.client.api :as d]
            [datomic.ion.cast :as cast]))

(def get-client
  "This function will return a local implementation of the client
  interface when run on a Datomic compute node. If you want to call
  locally, fill in the correct values in the map."
  (memoize #(d/client {:server-type :ion
                       :region      "us-east-2"
                       :system      "ions-pedestal"
                       :query-group "ions-pedestal"
                       :endpoint    "http://entry.ions-pedestal.us-east-2.datomic.net:8182/"
                       :proxy-port  8182})))

(defn about
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about))))

(defn home
  [request]
  (ring-resp/response "Hello World!"))

(defn- get-connection
  "Returns a datomic connection.
  Ensures the db is created and schema is loaded."
  []
  (let [client (get-client)
        db-name "pet-store"]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (ion-provider.datomic/load-dataset conn)
      conn)))

(def datomic-interceptor
  (interceptor/interceptor
   {:name ::datomic-interceptor
    :enter (fn [ctx]
             (let [conn (get-connection)
                   m    {::conn conn
                         ::db   (d/db conn)}]
               (-> ctx
                   (merge m)
                   (update-in [:request] merge m))))}))

(defn pets
  [request]
  (let [db (::db request)]
    (ring-resp/response
     (map (comp #(dissoc % :db/id) first)
          (d/q '[:find (pull ?e [*])
                 :where [?e :pet-store.pet/id]]
               db)))))

(def pet-interceptor
  (interceptor/interceptor
   {:name ::pet-interceptor
    :enter (fn [ctx]
             (let [db (::db ctx)
                   id (long (Integer/valueOf (or (get-in ctx [:request :path-params :id])
                                                 (get-in ctx [:request :json-params :id]))))
                   e  (d/pull db '[*] [:pet-store.pet/id id])]
               (assoc-in ctx [:request ::pet] (dissoc e :db/id))))}))

(defn get-pet
  [request]
  (let [pet (::pet request)]
    (when (seq pet)
      (ring-resp/response pet))))

(defn add-pet
  [request]
  (let [conn                  (::conn request)
        pet                   (::pet request)
        {:keys [id name tag]} (:json-params request)]
    (if (seq pet)
      (ring-resp/status (ring-resp/response (format "Pet with id %d exists." id)) 500)
      (do
        (d/transact conn {:tx-data [{:db/id              "new-pet"
                                     :pet-store.pet/id   (long id)
                                     :pet-store.pet/name name
                                     :pet-store.pet/tag  tag}]})
        (ring-resp/status (ring-resp/response "Created") 201)))))

(defn update-pet
  [request]
  (let [conn               (::conn request)
        pet                (::pet request)
        id                 (Long/valueOf (get-in request [:path-params :id]))
        {:keys [name tag]} (:json-params request)]
    (when (seq pet)
      (let [{:keys [db-after]} (d/transact conn {:tx-data [{:db/id              [:pet-store.pet/id id]
                                                            :pet-store.pet/id   id
                                                            :pet-store.pet/name name
                                                            :pet-store.pet/tag  tag}]})]
        (ring-resp/response (dissoc (d/pull db-after '[*] [:pet-store.pet/id id]) :db/id))))))

(defn remove-pet
  [request]
  (let [conn (::conn request)
        pet  (::pet request)]
    (when (seq pet)
      (d/transact conn {:tx-data [[:db/retractEntity [:pet-store.pet/id (:pet-store.pet/id pet)]]]})
      (ring-resp/status (ring-resp/response "No Content.") 204))))

(def common-interceptors [datomic-interceptor (body-params/body-params) http/json-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home)]
              ["/about" :get (conj common-interceptors `about)]
              ["/pets" :get (conj common-interceptors `pets)]
              ["/pets" :post (into common-interceptors [pet-interceptor `add-pet])]
              ["/pet/:id" :get (into common-interceptors [pet-interceptor `get-pet])]
              ["/pet/:id" :put (into common-interceptors [pet-interceptor `update-pet])]
              ["/pet/:id" :delete (into common-interceptors [pet-interceptor `remove-pet])]})

;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes routes
              ::http/resource-path "/public"
              ::http/chain-provider provider/ion-provider})
