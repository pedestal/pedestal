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
    (when (d/create-database client {:db-name db-name})
      (cast/event {:msg (format "Created database %s." db-name)}))
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

(def common-interceptors [datomic-interceptor (body-params/body-params) http/json-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home)]
              ["/about" :get (conj common-interceptors `about)]
              ["/pets" :get (conj common-interceptors `pets)]})

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
