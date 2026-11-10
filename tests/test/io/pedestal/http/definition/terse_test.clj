; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.definition.terse-test
  "Vars and functions as handlers in terse routes."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.http.route :as route]
            io.pedestal.http.route.specs
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.internal :as i]
            [io.pedestal.test-common :as tc])
  (:import (clojure.lang ExceptionInfo)))

(defn list-orders [_] {:status 200 :body "list-orders"})
(defn create-order [_] {:status 201 :body "create-order"})
(defn get-order [_] {:status 200 :body "get-order"})
(defn update-order [_] {:status 200 :body "update-order"})

(def audit
  (interceptor/interceptor {:name ::audit :enter identity}))

(def delete-order
  (interceptor/interceptor {:name  ::delete-interceptor
                            :enter #(assoc % :response {:status 200 :body "delete-order"})}))

(def archive-order
  (interceptor/interceptor {:name  ::archive-interceptor
                            :enter #(assoc % :response {:status 200 :body "archive-order"})}))

(def named-handler
  (with-meta (fn [_] {:status 200 :body "named-handler"}) {:name ::custom-name}))

(def anonymous-handler
  (fn [_] {:status 200 :body "anonymous-handler"}))

(def quoted-spec
  "The deprecated style: the specification is syntax-quoted, so the handlers are symbols."
  `[[["/api" ^:interceptors [audit]
      ["/orders" {:get  list-orders
                  :post [::create create-order]}
       ["/:id" {:get    [get-order]
                :put    [^:interceptors [audit] update-order]
                :delete delete-order
                :patch  [archive-order]}]]
      ["/named" {:get named-handler}
       ["/vector" {:get [named-handler]}]]
      ["/anonymous" {:get anonymous-handler}]]]])

(def var-spec
  "The same routes as plain data, with vars as the handlers."
  [[["/api" ^:interceptors [audit]
     ["/orders" {:get  #'list-orders
                 :post [::create #'create-order]}
      ["/:id" {:get    [#'get-order]
               :put    [^:interceptors [audit] #'update-order]
               :delete #'delete-order
               :patch  [#'archive-order]}]]
     ["/named" {:get #'named-handler}
      ["/vector" {:get [#'named-handler]}]]
     ["/anonymous" {:get #'anonymous-handler}]]]])

(defn- routing-table
  "What each route provides to an application: path, method, route name, and interceptor names."
  [route-spec]
  (->> (route/expand-routes route-spec)
       :routes
       (map (fn [{:keys [path method route-name interceptors]}]
              [path method route-name (mapv :name interceptors)]))
       (sort-by str)))

(defn- response-for
  [routing-table method path]
  (-> {:request {:request-method method
                 :path-info      path
                 :uri            path
                 :scheme         "http"
                 :server-name    "localhost"
                 :server-port    8080}}
      (chain/execute [(route/router routing-table)])
      :response))

(deftest vars-give-the-same-routing-table-as-quoted-symbols
  (doseq [default-handler-names? [true false]]
    (testing (str "with *default-handler-names* " default-handler-names?)
      (binding [interceptor/*default-handler-names* default-handler-names?]
        (let [expected (i/with-deprecations-suppressed
                         (routing-table quoted-spec))]
          (is (= 9 (count expected)))
          (is (= expected (routing-table var-spec))))))))

(deftest route-name-of-a-var-is-its-namespace-and-name
  (doseq [default-handler-names? [true false]]
    (binding [interceptor/*default-handler-names* default-handler-names?]
      (is (= [["/orders" :get ::list-orders [::list-orders]]]
             (routing-table [[["/orders" {:get #'list-orders}]]])))
      (is (= [["/orders" :delete ::delete-order [::delete-interceptor]]]
             (routing-table [[["/orders" {:delete #'delete-order}]]]))
          "an interceptor in a var keeps its own name"))))

(deftest functions-as-handlers
  (testing "the route name is the name of the interceptor created from the function"
    (is (= [["/orders" :get ::list-orders [::list-orders]]]
           (routing-table [[["/orders" {:get list-orders}]]])))
    (is (= [["/named" :get ::custom-name [::custom-name]]]
           (routing-table [[["/named" {:get named-handler}]]]))))

  (testing "an explicit route name"
    (is (= [["/orders" :get ::orders [::list-orders]]]
           (routing-table [[["/orders" {:get [::orders list-orders]}]]])))
    (is (= [["/orders" :get ::orders [::audit ::list-orders]]]
           (routing-table [[["/orders" {:get [::orders ^:interceptors [audit] list-orders]}]]]))))

  (testing "with *default-handler-names* false"
    (binding [interceptor/*default-handler-names* false]
      (is (= [["/orders" :get ::orders [::orders]]]
             (routing-table [[["/orders" {:get [::orders list-orders]}]]])))
      (is (thrown-with-msg? ExceptionInfo
                            #"\QHandler was not a symbol, a var, or an interceptor with a name, and no route name was provided\E"
                            (routing-table [[["/orders" {:get list-orders}]]]))))))

(deftest map-routes-accept-vars-and-functions
  (is (= [["/orders" :get ::list-orders [::list-orders]]
          ["/orders" :post ::create-order [::create-order]]]
         (routing-table {"/orders" {:get  #'list-orders
                                    :post create-order}}))))

(deftest var-and-function-handlers-are-invoked
  (let [table (route/expand-routes [[["/api"
                                      ["/orders" {:get  #'list-orders
                                                  :post [::create #'create-order]}
                                       ["/:id" {:get    [^:interceptors [audit] get-order]
                                                :delete #'delete-order}]]
                                      ["/anonymous" {:get anonymous-handler}]]]])]
    (is (match? {:status 200 :body "list-orders"} (response-for table :get "/api/orders")))
    (is (match? {:status 201 :body "create-order"} (response-for table :post "/api/orders")))
    (is (match? {:status 200 :body "get-order"} (response-for table :get "/api/orders/1")))
    (is (match? {:status 200 :body "delete-order"} (response-for table :delete "/api/orders/1")))
    (is (match? {:status 200 :body "anonymous-handler"} (response-for table :get "/api/anonymous")))))

(deftest only-quoted-symbols-are-deprecated
  (binding [i/*suppress?* false]
    (i/reset-deprecations)
    (is (not (string/includes? (tc/with-err-str (routing-table var-spec)) "quoted symbol")))
    (is (string/includes? (tc/with-err-str (routing-table quoted-spec)) "quoted symbol"))))

(deftest vars-and-functions-conform-to-the-terse-spec
  ;; The spec requires a route name in the vector form (for symbols too), so every vector here has one.
  (is (s/valid? :io.pedestal.http.route.specs/terse-routes
                [[["/orders" ^:interceptors [audit]
                   {:get    #'list-orders
                    :post   [::create #'create-order]
                    :put    [::update ^:interceptors [audit] update-order]
                    :delete delete-order}]]])))

(deftest invalid-handler-names-what-is-accepted
  (is (thrown-with-msg? AssertionError
                        #"\Qfound a class java.lang.Long as a handler. It must be an interceptor, a function, or a var\E"
                        (routing-table [[["/orders" {:get [::orders 42]}]]]))))
