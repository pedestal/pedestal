; Copyright 2024-2025 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.saw
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.sawtooth-test
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.http.route :as route]
            [io.pedestal.test-common :as tc]
            [io.pedestal.http.route.definition.table :as table]
            [com.walmartlabs.test-reporting :refer [reporting]]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.sawtooth.impl :as impl]
            [matcher-combinators.matchers :as m]))

(use-fixtures :once tc/no-ansi-fixture)

;; Placeholders for handlers in the routing table

(defn- get-users [])
(defn- get-user [])
(defn- user-search [])
(defn- create-user [])
(defn- get-user-collection [])
(defn- stats [])
(defn- get-resource [])
(defn- head-resource [])
(defn- shutdown [])
(defn- internal [])
(defn- monitor [])
(defn- root [])
(defn- list-pages [])
(defn- get-page [])
(defn- search-pages [])
(defn- page-content [])

(def dynamic-routing-table
  (:routes
    (route/expand-routes
      (table/table-routes
        {:host "example.com" :scheme :https}
        [["/user" :get `get-users]
         ["/user/:user-id" :get `get-user :constraints {:user-id #"[0-9]+"}]
         ["/user/:user-id" :post `create-user :constraints {:user-id #"[0-9]+"}]
         ["/user/:user-id/collection" :get `get-user-collection :constraints {:user-id #"[0-9]+"}]])
      (table/table-routes
        [["/api/stats" :get `stats]
         ["/api/shutdown" :post `shutdown]
         ["/resources/*path" :get `get-resource]
         ["/resources/*path" :head `head-resource]])
      (table/table-routes
        {:port 9999}
        [["/" :get `root]
         ["/internal" :get `internal]
         ["/internal/monitor" :any `monitor]]))))

(deftest dynamic-table-as-expected
  (is (= [{:host       "example.com"
           :method     :post
           :path       "/user/:user-id"
           :route-name :io.pedestal.http.sawtooth-test/create-user
           :scheme     :https}
          {:method     :get
           :path       "/resources/*path"
           :route-name :io.pedestal.http.sawtooth-test/get-resource}
          {:host       "example.com"
           :method     :get
           :path       "/user/:user-id"
           :route-name :io.pedestal.http.sawtooth-test/get-user
           :scheme     :https}
          {:host       "example.com"
           :method     :get
           :path       "/user/:user-id/collection"
           :route-name :io.pedestal.http.sawtooth-test/get-user-collection
           :scheme     :https}
          {:host       "example.com"
           :method     :get
           :path       "/user"
           :route-name :io.pedestal.http.sawtooth-test/get-users
           :scheme     :https}
          {:method     :head
           :path       "/resources/*path"
           :route-name :io.pedestal.http.sawtooth-test/head-resource}
          {:method     :get
           :path       "/internal"
           :port       9999
           :route-name :io.pedestal.http.sawtooth-test/internal}
          {:method     :any
           :path       "/internal/monitor"
           :port       9999
           :route-name :io.pedestal.http.sawtooth-test/monitor}
          {:method     :get
           :path       "/"
           :port       9999
           :route-name :io.pedestal.http.sawtooth-test/root}
          {:method     :post
           :path       "/api/shutdown"
           :route-name :io.pedestal.http.sawtooth-test/shutdown}
          {:method     :get
           :path       "/api/stats"
           :route-name :io.pedestal.http.sawtooth-test/stats}]
         (->> dynamic-routing-table
              (mapv #(select-keys % [:host :method :path :scheme :port :route-name]))
              (sort-by :route-name)
              vec))))

(defn- request
  [method path & {:as kvs}]
  (merge {:request-method method
          :scheme         :http
          :path-info      path
          :server-name    "not-specified"
          :server-port    8080}
         (set/rename-keys kvs {:host :server-name
                               :port :server-port})))

(def dynamic-requests
  [(request :get "/user/9999" :host "example.com") nil      ; wrong scheme
   (request :get "/user/9999" :host "example.com" :scheme :https) [::get-user {:user-id "9999"}]
   (request :head "/user/9999" :host "example.com" :scheme :https) nil ; wrong method
   (request :get "/user/fred" :host "example.com" :scheme :https) nil ; violates path constraint
   (request :get "/resources") nil                          ; incomplete path
   (request :get "/resources/") nil
   (request :get "/") nil                                   ; wrong port
   (request :get "/" :port 9999) [::root {}]
   (request :get "/resources/assets/style.css") [::get-resource {:path "assets/style.css"}]
   (request :head "/resources/assets/site.js") [::head-resource {:path "assets/site.js"}]
   (request :get "/api/stats") [::stats {}]
   (request :get "/api/stats" :host "other.org" :scheme :https :port 9997) [::stats {}] ; Agnostic to all that
   (request :get "/internal") nil                           ; wrong port
   (request :get "/internal" :port 9999) [::internal {}]    ; correct port
   (request :get "/internal/other" :port 9999) nil
   (request :patch "/internal/monitor" :port 9999) [::monitor {}]
   (request ::anything "/internal/monitor" :port 9999) [::monitor {}]])

(defn- attempt-request
  [router-fn request]
  (when-let [[route path-params] (router-fn request)]
    [(:route-name route) (or path-params {})]))

(deftest sawtooth-queries
  (let [sawtooth (sawtooth/router dynamic-routing-table)]
    (doseq [[request expected] (partition 2 dynamic-requests)
            :let [result (attempt-request sawtooth request)]]
      (reporting [request result]
                 (is (= expected result))))))

(deftest sawtooth-matches-prefix-tree
  (let [sawtooth    (sawtooth/router dynamic-routing-table)
        prefix-tree (prefix-tree/router dynamic-routing-table)]
    (doseq [[request _] (partition 2 dynamic-requests)]
      (reporting request
                 (is (= (attempt-request prefix-tree request)
                        (attempt-request sawtooth request)))))))

(defn- get-product-parts [])
(defn- get-product-orders [])

(deftest path-with-param-distinguish-by-last-term

  (let [routes  (table/table-routes
                  [["/api/product/:id/parts" :get get-product-parts :route-name ::parts]
                   ["/api/product/:id/orders" :get get-product-orders :route-name ::orders]
                   ["/api/users" :get get-users :route-name ::users]])
        router  (-> routes
                    route/expand-routes
                    sawtooth/router)
        attempt (fn [& args]
                  (router (apply request args)))]
    (is (match? [{:route-name ::parts} {:id "23"}]
                (attempt :get "/api/product/23/parts")))
    (is (match? [{:route-name ::orders} {:id "267"}]
                (attempt :get "/api/product/267/orders")))))

(deftest many-possible-matchers
  ;; Force the code path where a reduce is used to find the matcher.

  (let [routes  (table/table-routes
                  [["/product/:id/gnip" :get identity :route-name ::gnip]
                   ["/product/:id/gnop" :get identity :route-name ::gnop]
                   ["/product/:id/biff" :get identity :route-name ::biff]
                   ["/product/:id/bazz" :get identity :route-name ::bazz]])
        router  (-> routes
                    route/expand-routes
                    sawtooth/router)
        attempt (fn [suffix expected]
                  (is (match? [{:route-name expected} {}]
                              (router (request :get (str "/product/123/" suffix))))))]
    (attempt "gnip" ::gnip)
    (attempt "gnop" ::gnop)
    (attempt "biff" ::biff)
    (attempt "bazz" ::bazz)))

(comment
  (def sawtooth-router (sawtooth/router dynamic-routing-table))
  (def prefix-router (prefix-tree/router dynamic-routing-table))

  (attempt-request sawtooth-router (request :get "/internal" :port 9999))

  (attempt-request sawtooth-router (request :get "/" :port 9999))
  )

(defn- route
  [route-name method path & {:as kvs}]
  (merge {:route-name route-name
          :path       path
          :method     method} kvs))

(defn- conflicts
  [& routes]
  (let [[_ conflicts] (impl/create-matcher-from-routes routes)]
    conflicts))

(deftest literal-paths-no-conflict
  (is (= nil
         (conflicts
           (route :get-users :get "/users")
           (route :get-pages :get "/pages")
           ;; Not a conflict, not same method
           (route :new-user :post "/users")))))

(deftest literal-path-conflicts
  (is (= {:get-users #{:get-pages}}
         (conflicts
           (route :get-users :get "/users")
           (route :get-pages :get "/users")
           ;; Not a conflict, not same method
           (route :new-user :post "/users")))))

(deftest param-path-vs-literal-path-no-conflict
  (is (= {:get-user #{:search-users}}
         (conflicts
           (route :get-users :get "/users")
           (route :get-user :get "/users/:id")
           (route :get-user-stats :get "/users/stats")
           (route :get-page :get "/pages/:id")
           (route :search-users :get "/users/:search")
           (route :get-page-stats :get "/pages/stats")
           ; Not conflicts, different methods:
           (route :new-user :post "/users")
           (route :update-user :post "/users/:id")))))

(deftest report-simple-conflict
  (let [s (tc/with-err-str
            (sawtooth/router
              (:routes
                (route/expand-routes
                  (table/table-routes
                    [["/user" :get `get-users]
                     ["/user/:user-id" :get `get-user]
                     ["/user/:user-id" :post `create-user]
                     ;; A conflict:
                     ["/user/:search" :get `user-search]])))))]
    (is (match?
          (m/via string/split-lines
                 ["Conflicting routes were identified:"
                  ":io.pedestal.http.sawtooth-test/get-user (GET /user/:user-id) conflicts with route:"
                  " - :io.pedestal.http.sawtooth-test/user-search (GET /user/:search)"])
          s))))

(deftest multiple-conflicts
  (let [s (tc/with-err-str
            (sawtooth/router
              (:routes
                (route/expand-routes
                  (table/table-routes
                    [["/pages" :get `list-pages]
                     ["/pages/:id" :get `get-page]
                     ["/pages/:search" :get `search-pages]
                     ["/pages/:file" :any `page-content]])))))]
    (is (match?
          (m/via string/split-lines
                 ["Conflicting routes were identified:"
                  ":io.pedestal.http.sawtooth-test/get-page (GET /pages/:id) conflicts with route:"
                  " - :io.pedestal.http.sawtooth-test/search-pages (GET /pages/:search)"
                  ":io.pedestal.http.sawtooth-test/page-content (ANY /pages/:file) conflicts with 2 routes:"
                  " - :io.pedestal.http.sawtooth-test/get-page (GET /pages/:id)"
                  " - :io.pedestal.http.sawtooth-test/search-pages (GET /pages/:search)"])
          s))))

(deftest param-suffix-with-insufficient-terms-to-match
  ;; See https://github.com/pedestal/pedestal/issues/924
  (let [routes                    #{["/contacts/:id" :get (constantly {:status 200}) :route-name :get-contact]
                                    ["/repos/:org/:project" :get (constantly {:status 200}) :route-name :get-repo]}
        router-fn (sawtooth/router (route/expand-routes routes))]
    (is (nil?
          (router-fn (request :get "/contacts"))))

    (is (nil?
          (router-fn (request :get "/repos"))))

    (is (nil?
          (router-fn (request :ge "/repos/pedestal"))))))

