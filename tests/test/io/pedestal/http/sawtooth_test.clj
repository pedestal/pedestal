(ns io.pedestal.http.sawtooth-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http.route.definition.verbose :as verbose]
            [io.pedestal.http.route.router :as router]
            [io.pedestal.http.route.map-tree :as map-tree]
            [io.pedestal.http.route.sawtooth :as sawtooth]))

;; Placeholders for handlers in the routing table

(defn home-page [])
(defn list-users [])
(defn trailing-slash [])
(defn update-user [])
(defn view-user [])
(defn site-demo [])
(defn delete-user [])
(defn logout [])
(defn search-form [])
(defn request-inspection [])
(defn add-user [])

;; Adapted from route-tests
(def routing-table
  (verbose/expand-verbose-routes
    [{:app-name :public                                     ;; :app-name is documentation, not used for any kind of routing
      :host     "example.com"
      :children [{:path     "/"
                  :verbs    {:get `home-page}
                  ;; NOTE: This route will have :path-parts as ["" "child-path"] which may be a bug
                  ;; but we kind of have to live with it.
                  :children [{:path  "/child-path"
                              :verbs {:get `trailing-slash}}]}
                 {:path     "/user"
                  :verbs    {:get  `list-users
                             :post `add-user}
                  :children [{:path     "/:user-id"
                              :verbs    {:put `update-user}
                              :children [{:verbs {:get `view-user}}]}]}]}
     {:app-name :admin
      :scheme   :https
      :host     "admin.example.com"
      :port     9999
      :children [{:path  "/demo/site/*site-path"
                  :verbs {:get {:route-name ::site-demo
                                :handler    site-demo}}}
                 {:path  "/user/:user-id/delete"
                  :verbs {:delete `delete-user}}]}
     {:children [{:path  "/logout"
                  :verbs {:any `logout}}
                 {:path  "/search"
                  :verbs {:get `search-form}}
                 {:path  "/intercepted"
                  :verbs {:get {:route-name :intercepted
                                :handler    request-inspection}}}
                 {:path     "/trailing-slash/"
                  :children [{:path  "/child-path"
                              :verbs {:get {:route-name :admin-trailing-slash
                                            :handler    trailing-slash}}}]}
                 {:path     "/hierarchical"
                  :children [{:path  "/intercepted"
                              :verbs {:get {:route-name :hierarchical-intercepted
                                            :handler    request-inspection}}}]}
                 {:path  "/terminal/intercepted"
                  :verbs {:get {:route-name :terminal-intercepted
                                :handler    request-inspection}}}]}]))

(deftest routing-table-as-expected
  (is (= [{:path        "/"
           :method      :get
           :app-name    :public
           :path-parts  [""]
           :host        "example.com"
           :route-name  ::home-page
           :path-params []}
          {:path        "/child-path"
           :method      :get
           :app-name    :public
           :path-parts  ["" "child-path"]                   ;; See above note
           :host        "example.com"
           :route-name  ::trailing-slash
           :path-params []}
          {:path        "/user"
           :method      :get
           :app-name    :public
           :path-parts  ["user"]
           :host        "example.com"
           :route-name  ::list-users
           :path-params []}
          {:path        "/user"
           :method      :post
           :app-name    :public
           :path-parts  ["user"]
           :host        "example.com"
           :route-name  ::add-user
           :path-params []}
          {:path             "/user/:user-id"
           :method           :put
           :path-constraints {:user-id "([^/]+)"}
           :app-name         :public
           :path-parts       ["user" :user-id]
           :host             "example.com"
           :route-name       ::update-user
           :path-params      [:user-id]}
          {:path             "/user/:user-id"
           :method           :get
           :path-constraints {:user-id "([^/]+)"}
           :app-name         :public
           :path-parts       ["user" :user-id]
           :host             "example.com"
           :route-name       ::view-user
           :path-params      [:user-id]}
          {:path             "/demo/site/*site-path"
           :method           :get
           :path-constraints {:site-path "(.*)"}
           :app-name         :admin
           :path-parts       ["demo" "site" :site-path]
           :port             9999
           :host             "admin.example.com"
           :route-name       ::site-demo
           :path-params      [:site-path]
           :scheme           :https}
          {:path             "/user/:user-id/delete"
           :method           :delete
           :path-constraints {:user-id "([^/]+)"}
           :app-name         :admin
           :path-parts       ["user" :user-id "delete"]
           :port             9999
           :host             "admin.example.com"
           :route-name       ::delete-user
           :path-params      [:user-id]
           :scheme           :https}
          {:path-parts  ["logout"]
           :path-params []
           :path        "/logout"
           :method      :any
           :route-name  ::logout}
          {:path-parts  ["search"]
           :path-params []
           :path        "/search"
           :method      :get
           :route-name  ::search-form}
          {:path-parts  ["intercepted"]
           :path-params []
           :path        "/intercepted"
           :method      :get
           :route-name  :intercepted}
          {:path-parts  ["trailing-slash" "child-path"]
           :path-params []
           :path        "/trailing-slash/child-path"
           :method      :get
           :route-name  :admin-trailing-slash}
          {:path-parts  ["hierarchical" "intercepted"]
           :path-params []
           :path        "/hierarchical/intercepted"
           :method      :get
           :route-name  :hierarchical-intercepted}
          {:path-parts  ["terminal" "intercepted"]
           :path-params []
           :path        "/terminal/intercepted"
           :method      :get
           :route-name  :terminal-intercepted}]
         (mapv #(dissoc % :interceptors :path-re) routing-table))))

(defn- request
  [method path & {:as kvs}]
  (merge {:request-method method
          :path-info      path}
         (set/rename-keys kvs {:host :server-name
                               :port :server-port})))

(comment
  (request :get "/foo" :host "example.com")
  )

(def requests
  [(request :get "/user/9999" :host "example.com")])

(deftest sawtooth-matches-map-tree
  (let [map-tree (map-tree/router routing-table)
        sawtooth (sawtooth/router routing-table)]
    (doseq [request requests]
      (prn request "...")
      (is (= (router/find-route map-tree request)
             (router/find-route sawtooth request))))))

