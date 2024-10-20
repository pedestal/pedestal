; Copyright 2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route-test
  (:require [clojure.test :refer [deftest is use-fixtures are testing]]
            [clojure.set :as set]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [io.pedestal.http.route.sawtooth.impl :as impl]
            [io.pedestal.interceptor :refer [interceptor]]
            io.pedestal.http.route.specs
            [medley.core :as medley]
            [ring.util.response :as ring-response]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.http.route :as route :refer [expand-routes]]
            [io.pedestal.http.route.map-tree :as map-tree]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.definition.verbose :as verbose]
            [io.pedestal.http.route.path :as path]
            [io.pedestal.http.route.linear-search :as linear-search]
            [io.pedestal.http.route.definition.table :refer [table-routes]]
            [io.pedestal.http.route.definition.terse :as terse :refer [map-routes->vec-routes]])
  (:import (clojure.lang ExceptionInfo)))


(comment
  (s/conform :io.pedestal.http.route.definition.specs/terse-route-entry
             ["/foo" ^:interceptors [map] {:get `bar}
              ["/bar" {:post conj}]
              ["/baz" {:delete into}]])
  )

(defn- enable-expound-fixture
  [f]
  (with-redefs [s/explain     expound/expound
                s/explain-str expound/expound-str]
    (try
      (stest/instrument [`verbose/expand-verbose-routes
                         `terse/terse-routes
                         `table-routes])
      (f)
      (finally
        (stest/unstrument)))))

(use-fixtures :once enable-expound-fixture
              (fn [f]
                (binding [impl/*squash-conflicts-report* true]
                  (f))))

(defn handler
  [name request-fn]
  (interceptor
    {:name  name
     :enter #(assoc % :response (-> % :request request-fn))}))

(defmacro defhandler [sym params & body]
  `(def ~sym (let [f# (fn ~params ~@body)]
               (interceptor
                 {:name  ~(keyword "io.pedestal.http.route-test" (name sym))
                  :enter (fn [context#]
                           (assoc context# :response
                                  (f# (:request context#))))}))))

(defhandler home-page
  [_request]
  "home-page")

(defhandler list-users
  [_request]
  "list-users")
(defhandler view-user
  [_request]
  "view-user")
(defhandler add-user
  [_request]
  "add-user")
(defhandler update-user
  [_request]
  "update-user")
(defhandler logout
  [_request]
  "logout")
(defhandler delete-user
  [_request]
  "delete-user")
(defhandler search-form
  [_request]
  "search-form")
(defhandler search-id
  [_request]
  "search-id")
(defhandler search-query
  [_request]
  "search-query")
(defhandler trailing-slash
  [_request]
  "trailing-slash")

(defhandler request-inspection
  [req] {:request req})

(defmacro defon-request [sym params & body]
  `(def ~sym
     (let [f# (fn ~params ~@body)]
       (interceptor
         {:enter #(update % :request f#)}))))

(defon-request interceptor-1
  [req] (assoc req ::interceptor-1 :fired))

(defon-request interceptor-2
  [req] (if (= :fired (::interceptor-1 req))
          (assoc req ::interceptor-1 :clobbered)
          (assoc req ::interceptor-2 :fired-without-1)))

(defn interceptor-3
  ([] (interceptor-3 ::fn-called-implicitly))
  ([value]
   (interceptor
     {:enter (fn [context]
               (assoc-in context [:request ::interceptor-3] value))})))

(defn site-demo [site-name]
  (fn [& _]
    (ring-response/response (str "demo page for " site-name))))

(deftest specs-are-enforced
  ;; Sanity check that specs are enforced from within test functions
  ;; Clojure 1.10 includes the #' in the message, Clojure 1.11 does not.
  (when-let [e (is (thrown-with-msg? ExceptionInfo #"\QCall to \E(#')?\Qio.pedestal.http.route.definition.table/table-routes did not conform to spec.\E"
                                     (table-routes [{:path "not leading slash"}])))]
    (is (match?
          {::s/args '([{:path "not leading slash"}])}
          (ex-data e)))))

;; schemes, hosts, path, verb and maybe query string
(def verbose-routes                                         ;; the verbose hierarchical data structure
  (expand-routes
    (verbose/expand-verbose-routes
      `[{:app-name :public
         :host     "example.com"
         ;;    :interceptors []
         :children [{:path     "/"
                     ;; :interceptors []
                     ;;                :verbs {:get {:handler home-page :interceptors []}
                     :verbs    {:get home-page}
                     :children [{:path  "/child-path"
                                 :verbs {:get trailing-slash}}]}
                    {:path     "/user"
                     :verbs    {:get  list-users
                                :post add-user}
                     :children [{:path        "/:user-id"
                                 :constraints {:user-id #"[0-9]+"}
                                 :verbs       {:put update-user}
                                 :children    [{:constraints {:view #"long|short"}
                                                :verbs       {:get view-user}}]}]}]}
        {:app-name :admin
         :scheme   :https
         :host     "admin.example.com"
         :port     9999
         :children [{:path  "/demo/site-one/*site-path"
                     ;; :verbs {:get {:name :site-one-demo :handler (site-demo "one") :interceptors []}}
                     :verbs {:get {:route-name :site-one-demo
                                   :handler    (site-demo "one")}}}
                    {:path  "/demo/site-two/*site-path"
                     :verbs {:get {:route-name :site-two-demo
                                   :handler    (site-demo "two")}}}
                    {:path  "/user/:user-id/delete"
                     :verbs {:delete delete-user}}]}
        {:children [{:path  "/logout"
                     :verbs {:any logout}}
                    {:path     "/search"
                     :verbs    {:get search-form}
                     ;; This is a conflict for any router except linear-matcher
                     :children [{:constraints {:id #"[0-9]+"}
                                 :verbs       {:get search-id}}
                                {:constraints {:q #".+"}
                                 :verbs       {:get search-query}}]}
                    {:path         "/intercepted"
                     :verbs        {:get {:route-name :intercepted
                                          :handler    request-inspection}}
                     :interceptors [interceptor-1 interceptor-2]}
                    {:path         "/intercepted-by-fn-symbol"
                     :verbs        {:get {:route-name :intercepted-by-fn-symbol
                                          :handler    request-inspection}}
                     :interceptors [interceptor-3]}
                    {:path         "/intercepted-by-fn-list"
                     :verbs        {:get {:route-name :intercepted-by-fn-list
                                          :handler    request-inspection}}
                     :interceptors [(interceptor-3 ::fn-called-explicitly)]}
                    {:path     "/trailing-slash/"
                     :children [{:path  "/child-path"
                                 :verbs {:get {:route-name :admin-trailing-slash
                                               :handler    trailing-slash}}}]}
                    {:path         "/hierarchical"
                     :interceptors [interceptor-1]
                     :children     [{:path         "/intercepted"
                                     :interceptors [interceptor-2]
                                     :verbs        {:get {:route-name :hierarchical-intercepted
                                                          :handler    request-inspection}}}]}
                    {:path  "/terminal/intercepted"
                     :verbs {:get {:route-name   :terminal-intercepted
                                   :handler      request-inspection
                                   :interceptors [interceptor-1 interceptor-2]}}}]}])))

(def terse-routes                                           ;; the terse hierarchical data structure
  (expand-routes
    `[[:public "example.com"
       ["/" {:get home-page}
        ["/child-path" {:get trailing-slash}]]
       ["/user" {:get  list-users
                 :post add-user}
        ["/:user-id"
         ^:constraints {:user-id #"[0-9]+"}
         {:put update-user}
         [^:constraints {:view #"long|short"}
          {:get view-user}]]]]
      [:admin :https "admin.example.com" 9999
       ["/demo/site-one/*site-path" {:get [:site-one-demo (site-demo "one")]}]
       ["/demo/site-two/*site-path" {:get [:site-two-demo (site-demo "two")]}]
       ["/user/:user-id/delete" {:delete delete-user}]]
      [["/logout" {:any logout}]
       ["/search" {:get search-form}
        [^:constraints {:id #"[0-9]+"} {:get search-id}]
        [^:constraints {:q #".+"} {:get search-query}]]
       ["/intercepted" {:get [:intercepted request-inspection]}
        ^:interceptors [interceptor-1 interceptor-2]]
       ["/intercepted-by-fn-symbol" {:get [:intercepted-by-fn-symbol request-inspection]}
        ^:interceptors [interceptor-3]]
       ["/intercepted-by-fn-list" {:get [:intercepted-by-fn-list request-inspection]}
        ^:interceptors [(interceptor-3 ::fn-called-explicitly)]]
       ["/trailing-slash/"
        ["/child-path" {:get [:admin-trailing-slash trailing-slash]}]]
       ["/hierarchical" ^:interceptors [interceptor-1]
        ["/intercepted" ^:interceptors [interceptor-2]
         {:get [:hierarchical-intercepted request-inspection]}]]
       ["/terminal/intercepted"
        {:get [:terminal-intercepted ^:interceptors [interceptor-1 interceptor-2] request-inspection]}]]]))

(def map-routes
  ;; One limitation is you can't control hostname or protocol
  (expand-routes
    `{"/" {:get          home-page
           "/child-path" {:get trailing-slash}
           "/user"       {:get        list-users
                          :post       add-user
                          "/:user-id" {:constraints {:user-id #"[0-9]+"}
                                       :put         update-user
                                       ;; Note another limitation of map-routes is the inability to do per-verb constraints
                                       :get         view-user}}}}))

(def data-map-routes
  (expand-routes
    [[(map-routes->vec-routes
        {"/" {:get          home-page
              "/child-path" {:get trailing-slash}
              "/user"       {:get        list-users
                             :post       add-user
                             "/:user-id" {:constraints {:user-id #"[0-9]+"}
                                          :put         update-user
                                          ;; Note another limitation of map-routes is the inability to do per-verb constraints
                                          :get         view-user}}}})]]))

(def data-routes
  (expand-routes
    [[:public "example.com"
      ["/" {:get home-page}
       ["/child-path" {:get trailing-slash}]]
      ["/user" {:get  list-users
                :post add-user}
       ["/:user-id"
        ^:constraints {:user-id #"[0-9]+"}
        {:put update-user}
        [^:constraints {:view #"long|short"}
         {:get view-user}]]]]
     [:admin :https "admin.example.com" 9999
      ["/demo/site-one/*site-path" {:get [:site-one-demo (handler :site-one (site-demo "one"))]}]
      ["/demo/site-two/*site-path" {:get [:site-two-demo (handler :site-two (site-demo "two"))]}]
      ["/user/:user-id/delete" {:delete delete-user}]]
     [["/logout" {:any logout}]
      ["/search" {:get search-form}
       [^:constraints {:id #"[0-9]+"} {:get search-id}]
       [^:constraints {:q #".+"} {:get search-query}]]
      ["/intercepted" {:get [:intercepted request-inspection]}
       ^:interceptors [interceptor-1 interceptor-2]]
      ["/intercepted-by-fn-symbol" {:get [:intercepted-by-fn-symbol request-inspection]}
       ^:interceptors [(interceptor-3)]]
      ["/intercepted-by-fn-list" {:get [:intercepted-by-fn-list request-inspection]}
       ^:interceptors [(interceptor-3 ::fn-called-explicitly)]]
      ["/trailing-slash/"
       ["/child-path" {:get [:admin-trailing-slash trailing-slash]}]]
      ["/hierarchical" ^:interceptors [interceptor-1]
       ["/intercepted" ^:interceptors [interceptor-2]
        {:get [:hierarchical-intercepted request-inspection]}]]
      ["/terminal/intercepted"
       {:get [:terminal-intercepted ^:interceptors [interceptor-1 interceptor-2] request-inspection]}]]]))

(def syntax-quote-data-routes
  (expand-routes
    (let [one "one"
          two "two"]
      `[[:public "example.com"
         ["/" {:get home-page}
          ["/child-path" {:get trailing-slash}]]
         ["/user" {:get  list-users
                   :post add-user}
          ["/:user-id"
           ^:constraints {:user-id #"[0-9]+"}
           {:put update-user}
           [^:constraints {:view #"long|short"}
            {:get view-user}]]]]
        [:admin :https "admin.example.com" 9999
         ["/demo/site-one/*site-path" {:get [:site-one-demo (site-demo ~one)]}]
         ["/demo/site-two/*site-path" {:get [:site-two-demo (site-demo ~two)]}]
         ["/user/:user-id/delete" {:delete delete-user}]]
        [["/logout" {:any logout}]
         ["/search" {:get search-form}
          [^:constraints {:id #"[0-9]+"} {:get search-id}]
          [^:constraints {:q #".+"} {:get search-query}]]
         ["/intercepted" {:get [:intercepted request-inspection]}
          ^:interceptors [interceptor-1 interceptor-2]]
         ["/intercepted-by-fn-symbol" {:get [:intercepted-by-fn-symbol request-inspection]}
          ^:interceptors [interceptor-3]]
         ["/intercepted-by-fn-list" {:get [:intercepted-by-fn-list request-inspection]}
          ^:interceptors [(interceptor-3 ::fn-called-explicitly)]]
         ["/trailing-slash/"
          ["/child-path" {:get [:admin-trailing-slash trailing-slash]}]]
         ["/hierarchical" ^:interceptors [interceptor-1]
          ["/intercepted" ^:interceptors [interceptor-2]
           {:get [:hierarchical-intercepted request-inspection]}]]
         ["/terminal/intercepted"
          {:get [:terminal-intercepted ^:interceptors [interceptor-1 interceptor-2] request-inspection]}]]])))

(def id #"[0-9]+")

(def tabular-routes
  (expand-routes
    #{{:app-name :public :host "example.com"}
      ["/" :get [home-page]]
      ["/child-path" :get trailing-slash]
      ["/user" :get list-users]
      ["/user" :post add-user]
      ["/user/:user-id" :get view-user :constraints {:user-id id :view #"long|short"}]
      ["/user/:user-id" :put update-user :constraints {:user-id id}]}

    #{{:app-name :admin :scheme :https :host "admin.example.com" :port 9999}
      ["/demo/site-one/*site-path" :get (site-demo "one") :route-name :site-one-demo]
      ["/demo/site-two/*site-path" :get (site-demo "two") :route-name :site-two-demo]
      ["/user/:user-id/delete" :delete delete-user]}

    #{["/logout" :any logout]
      ["/search" :get search-id :constraints {:id id}]
      ["/search" :get search-query :constraints {:q #".+"}]
      ["/search" :get search-form]
      ["/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :intercepted]
      ["/intercepted-by-fn-symbol" :get [(interceptor-3) request-inspection] :route-name :intercepted-by-fn-symbol]
      ["/intercepted-by-fn-list" :get [(interceptor-3 ::fn-called-explicitly) request-inspection] :route-name :intercepted-by-fn-list]
      ["/trailing-slash/child-path" :get trailing-slash :route-name :admin-trailing-slash]
      ["/hierarchical/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :hierarchical-intercepted]
      ["/terminal/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :terminal-intercepted]}))

(def quoted-tabular-routes
  (expand-routes
    `#{{:app-name :public :host "example.com"}
       ["/" :get [home-page]]
       ["/child-path" :get trailing-slash]
       ["/user" :get list-users]
       ["/user" :post add-user]
       ["/user/:user-id" :get view-user :constraints {:user-id #"[0-9]+" :view #"long|short"}]
       ["/user/:user-id" :put update-user :constraints {:user-id #"[0-9]+"}]}
    `#{{:app-name :admin :scheme :https :host "admin.example.com" :port 9999}
       ["/demo/site-one/*site-path" :get (site-demo "one") :route-name :site-one-demo]
       ["/demo/site-two/*site-path" :get (site-demo "two") :route-name :site-two-demo]
       ["/user/:user-id/delete" :delete delete-user]}
    `#{["/logout" :any logout]
       ["/search" :get search-id :constraints {:id #"[0-9]+"}]
       ["/search" :get search-query :constraints {:q #".+"}]
       ["/search" :get search-form]
       ["/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :intercepted]
       ["/intercepted-by-fn-symbol" :get [(interceptor-3) request-inspection] :route-name :intercepted-by-fn-symbol]
       ["/intercepted-by-fn-list" :get [(interceptor-3 ::fn-called-explicitly) request-inspection] :route-name :intercepted-by-fn-list]
       ["/trailing-slash/child-path" :get trailing-slash :route-name :admin-trailing-slash]
       ["/hierarchical/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :hierarchical-intercepted]
       ["/terminal/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :terminal-intercepted]}))

(def static-quoted-tabular-routes
  (expand-routes
    `#{["/logout" :any logout]
       ["/search" :get search-query :constraints {:q #".+"}]
       ["/search" :post search-form]
       ["/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :intercepted]
       ["/intercepted-by-fn-symbol" :get [(interceptor-3) request-inspection] :route-name :intercepted-by-fn-symbol]
       ["/intercepted-by-fn-list" :get [(interceptor-3 ::fn-called-explicitly) request-inspection] :route-name :intercepted-by-fn-list]
       ["/trailing-slash/child-path" :get trailing-slash :route-name :admin-trailing-slash]
       ["/hierarchical/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :hierarchical-intercepted]
       ["/terminal/intercepted" :get [interceptor-1 interceptor-2 request-inspection] :route-name :terminal-intercepted]}))

;; HTTP verb-smuggling in query string is disabled here:
(defn make-linker
  [routes]
  (route/url-for-routes routes :method-param nil))
;; but enabled here:
(defn make-action
  [routes]
  (route/form-action-for-routes routes))
;; and here:
(def app-router route/router)
;(defn app-router
;  ([routes]
;   (router routes))
;  ([routes router-impl-key]
;   (router routes router-impl-key))) ;; switch to routes-2, routes-3

(def print-context
  {:enter (fn [context]
            (pprint context)
            context)})

(defn test-match
  [routes router-impl method uri & args]
  (let [{:keys [scheme host port query]
         :or   {scheme "do-not-match-scheme"
                host   "do-not-match-host"
                port   -1}}
        (apply hash-map args)
        {:keys [route request]}
        (-> {:request {:request-method method
                       :scheme         scheme
                       :server-name    host
                       :server-port    port
                       :path-info      uri
                       :query-string   query}}
            (interceptor.chain/enqueue [route/query-params
                                        (route/method-param)
                                        (app-router routes router-impl)])
            interceptor.chain/execute)]
    (when route
      (merge
        {:route-name  (:route-name route)
         :path-params (:path-params request)}
        (when-let [query-params (:query-params request)]
          {:query-params query-params})))))

(defn test-query-execute
  [table router-impl query]
  (-> query
      (interceptor.chain/enqueue [route/query-params
                                  (route/method-param)
                                  (app-router table router-impl)])
      interceptor.chain/execute))

(defn test-query-match [table router-impl uri params]
  (:route-name (test-match table router-impl :get uri :query params)))


(defn test-fire-interceptors [router-impl-key]
  (are [routes] (= :clobbered
                   (-> (test-query-execute routes
                                           router-impl-key
                                           {:request {:request-method :get
                                                      :scheme         "do-not-match-scheme"
                                                      :server-name    "do-not-match-host"
                                                      :path-info      "/intercepted"
                                                      :query-params   {}}})
                       :response
                       :request
                       ::interceptor-1))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest fire-interceptors-prefix-tree
  (test-fire-interceptors :prefix-tree))

(deftest fire-interceptor-sawtooth
  (test-fire-interceptors :sawtooth))

(deftest fire-interceptors-map-tree
  (test-fire-interceptors :map-tree))                       ;; This should fallback to prefix-tree

(deftest fire-interceptors-linear-search
  (test-fire-interceptors :linear-search))

(defn test-fire-hierarchical-interceptors [router-impl-key]
  (are [routes] (= :clobbered
                   (-> (test-query-execute routes
                                           router-impl-key
                                           {:request {:request-method :get
                                                      :scheme         "do-not-match-scheme"
                                                      :server-name    "do-not-match-host"
                                                      :path-info      "/hierarchical/intercepted"
                                                      :query-params   {}}})
                       :response
                       :request
                       ::interceptor-1))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest fire-hierarchical-interceptors
  (test-fire-hierarchical-interceptors :prefix-tree)
  (test-fire-hierarchical-interceptors :sawtooth)
  (test-fire-hierarchical-interceptors :prefix-tree)        ;; This should fallback to PrefixTree
  (test-fire-hierarchical-interceptors :linear-search))

(defn test-fire-terminal-interceptors [router-impl-key]
  (are [routes] (= :clobbered
                   (-> (test-query-execute routes
                                           router-impl-key
                                           {:request {:request-method :get
                                                      :scheme         "do-not-match-scheme"
                                                      :server-name    "do-not-match-host"
                                                      :path-info      "/terminal/intercepted"
                                                      :query-params   {}}})
                       :response
                       :request
                       ::interceptor-1))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest fire-terminal-interceptors-prefix-tree
  (test-fire-terminal-interceptors :prefix-tree))

(deftest fire-terminal-interceptors-sawtooth
  (test-fire-terminal-interceptors :sawtooth))

(deftest fire-terminal-interceptors-linear-search
  (test-fire-terminal-interceptors :linear-search))

;; TODO: This is no longer supported - *ALL* symbols that resolve to fns are treated like handlers
;;       *ALL* lists (fn call of an Interceptor Fn), get eval'd, returning the interceptor
;(deftest fire-interceptor-fn-symbol
;  (are [routes] (= ::fn-called-implicitly
;                   (-> (test-query-execute routes {:request {:request-method :get
;                                                             :scheme "do-not-match-scheme"
;                                                             :server-name "do-not-match-host"
;                                                             :path-info "/intercepted-by-fn-symbol"
;                                                             :query-params {}}})
;                       :response
;                       :request
;                       ::interceptor-3))
;       verbose-routes
;       terse-routes
;       data-routes
;       syntax-quote-data-routes))

(defn test-fire-interceptor-fn-list [router-impl-key]
  (are [routes] (= ::fn-called-explicitly
                   (-> (test-query-execute routes
                                           router-impl-key
                                           {:request {:request-method :get
                                                      :scheme         "do-not-match-scheme"
                                                      :server-name    "do-not-match-host"
                                                      :path-info      "/intercepted-by-fn-list"
                                                      :query-params   {}}})
                       :response
                       :request
                       ::interceptor-3))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest fire-interceptor-fn-list-prefix-tree
  (test-fire-interceptor-fn-list :prefix-tree))

(deftest fire-interceptor-fn-list-sawtooth
  (test-fire-interceptor-fn-list :sawtooth))

(deftest fire-interceptor-fn-list-linear-search
  (test-fire-interceptor-fn-list :linear-search))

(defn test-match-root [router-impl-key]
  (are [routes] (= {:route-name ::home-page :path-params {}}
                   (test-match routes router-impl-key :get "/" :host "example.com"))
    verbose-routes
    terse-routes))

(deftest match-root-prefix-tree
  (test-match-root :prefix-tree))

(deftest match-root-sawtooth
  (test-match-root :sawtooth))

(deftest match-root-linear-search
  (test-match-root :linear-search))

(defn test-match-update-user [router-impl-key]
  (are [routes] (= {:route-name  ::update-user
                    :path-params {:user-id "123"}}
                   (test-match routes router-impl-key :put "/user/123" :host "example.com"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest match-update-user-prefix-tree
  (test-match-update-user :prefix-tree))

(deftest match-update-user-sawtooth
  (test-match-update-user :sawtooth))

(deftest match-update-user-linear-search
  (test-match-update-user :linear-search))

(defn test-match-logout [router-impl-key]
  (are [routes] (= {:route-name ::logout :path-params {}}
                   (test-match routes router-impl-key :post "/logout"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest match-logout-prefix-tree
  (test-match-logout :prefix-tree))

(deftest match-logout-sawtooth
  (test-match-logout :sawtooth))

(deftest match-logout-linear-search
  (test-match-logout :linear-search))

(defn test-match-non-root-trailing-slash [router-impl-key]
  (are [routes] (= {:route-name :admin-trailing-slash :path "/trailing-slash/child-path"}
                   (-> routes

                       (test-query-execute
                         router-impl-key
                         {:request {:request-method :get
                                    :path-info      "/trailing-slash/child-path"}})
                       :route
                       (select-keys [:route-name :path])))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest match-non-root-trailing-slash-prefix-tree
  (test-match-non-root-trailing-slash :prefix-tree))

(deftest match-non-root-trailing-slash-sawtooth
  (test-match-non-root-trailing-slash :sawtooth))

(deftest match-non-root-trailing-slash-linear-search
  (test-match-non-root-trailing-slash :linear-search))

(defn test-match-root-trailing-slash [router-impl-key]
  (are [routes] (= {:route-name ::trailing-slash :path "/child-path"}
                   (-> routes
                       (test-query-execute
                         router-impl-key
                         {:request {:request-method :get
                                    :server-name    "example.com"
                                    :path-info      "/child-path"}})
                       :route
                       (select-keys [:route-name :path])))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest match-root-trailing-slash-prefix-tree
  (test-match-root-trailing-slash :prefix-tree))

(deftest match-root-trailing-slash-sawtooth
  (test-match-root-trailing-slash :sawtooth))

(deftest match-root-trailing-slash-linear
  (test-match-root-trailing-slash :linear-search))

(defn test-check-host [router-impl-key]
  (are [routes] (nil? (test-match
                        routes router-impl-key :put "/user/123" :host "admin.example.com"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest check-host-prefix-tree
  (test-check-host :prefix-tree))


(deftest check-host-sawtooth
  (test-check-host :sawtooth))

(deftest check-host-linear-search
  (test-check-host :linear-search))


(defn test-match-demo-one [router-impl-key]
  (are [routes] (= {:route-name  :site-one-demo
                    :path-params {:site-path "foo/bar/baz"}}
                   (test-match routes router-impl-key :get "/demo/site-one/foo/bar/baz"
                               :scheme :https
                               :host "admin.example.com"
                               :port 9999))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes))

(deftest match-demo-one-prefix-tree
  (test-match-demo-one :prefix-tree))

(deftest match-demo-one-sawtooth
  (test-match-demo-one :sawtooth))

(deftest match-demo-one-linear-search
  (test-match-demo-one :linear-search))

(defn test-match-user-constraints [router-impl-key]
  (are [routes] (= {:path-params {:user-id "123"} :route-name ::update-user}
                   (test-match routes router-impl-key :put "/user/123" :host "example.com"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes)
  (are [routes] (= nil
                   (test-match routes router-impl-key :put "/user/abc" :host "example.com"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes)
  (are [routes] (= {:path-params {:user-id "123"} :query-params {:view "long"} :route-name ::view-user}
                   (test-match routes router-impl-key :get "/user/123" :scheme :http :host "example.com" :query "view=long"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes)
  (are [routes] (= nil
                   (test-match routes router-impl-key :get "/user/123" :scheme :http :host "example.com" :query "view=none"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes)
  (are [routes] (= nil
                   (test-match routes router-impl-key :get "/user/abc" :scheme :http :host "example.com" :query "view=long"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest match-user-constraints-prefix-tree
  (test-match-user-constraints :prefix-tree))

(deftest match-user-constraints-sawtooth
  (test-match-user-constraints :sawtooth))

(deftest match-user-constraints-linear-search
  (test-match-user-constraints :linear-search))

(comment
  (test-query-match data-routes :linear-search "/search" "id=123")

  )

(deftest match-query
  (are [routes] (= ::search-id
                   ;; Routing on constraints is considered bad practice, and isn't supported by the prefix-tree router
                   ;(test-query-match routes :prefix-tree "/search" "id=123")
                   (test-query-match routes :linear-search "/search" "id=123"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    tabular-routes
    quoted-tabular-routes)
  (are [routes] (= ::search-query
                   ;; Routing on constraints is considered bad practice, and isn't supported by the prefix-tree router
                   ;(test-query-match routes :prefix-tree "/search" "q=foo")
                   (test-query-match routes :linear-search "/search" "q=foo"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes)
  (are [routes] (= ::search-form
                   ;; Routing on constraints is considered bad practice, and isn't supported by the prefix-tree router
                   (test-query-match routes :linear-search "/search" nil)
                   (test-query-match routes :prefix-tree "/search" nil))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes)
  (are [routes] (= ::search-form
                   ;; Routing on constraints is considered bad practice, and isn't supported by the prefix-tree router
                   (test-query-match routes :linear-search "/search" "id=not-a-number")
                   (test-query-match routes :prefix-tree "/search" "id=not-a-number"))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest trailing-slash-link
  (are [routes] (= "/child-path" ((make-linker routes)
                                  ::trailing-slash
                                  :app-name :public
                                  :request {:server-name "example.com"}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest logout-link
  (are [routes] (= "/logout" ((make-linker routes) ::logout :app-name :public))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest view-user-link
  (are [routes] (= "//example.com/user/456"
                   ((make-linker routes) ::view-user :app-name :public :params {:user-id 456}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest view-user-link-on-non-standard-port
  (are [routes] (= "http://example.com:8080/user/456"
                   ((make-linker routes) ::view-user
                    :app-name :public
                    :params {:user-id 456}
                    :absolute? true
                    :request {:scheme :http :server-name "example.com" :server-port 8080}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-link
  (are [routes] (= "https://admin.example.com:9999/user/456/delete"
                   ((make-linker routes) ::delete-user :app-name :admin :params {:user-id 456}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-action
  (are [routes] (= {:action "https://admin.example.com:9999/user/456/delete?_method=delete"
                    :method "post"}
                   ((make-action routes) ::delete-user :app-name :admin :params {:user-id 456}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-action-without-verb-smuggling
  (are [routes] (= {:action "https://admin.example.com:9999/user/456/delete"
                    :method "delete"}
                   ((make-action routes) ::delete-user
                    :method-param nil
                    :app-name :admin
                    :params {:user-id 456}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-action-with-alternate-verb-param
  (are [routes] (= {:action "https://admin.example.com:9999/user/456/delete?verb=delete"
                    :method "post"}
                   ((make-action routes) ::delete-user
                    :method-param "verb"
                    :app-name :admin
                    :params {:user-id 456}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-link-with-scheme
  (are [routes] (= "//admin.example.com:9999/user/456/delete"
                   ((make-linker routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :request {:scheme :https}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-link-with-host
  (are [routes] (= "/user/456/delete"
                   ((make-linker routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :request {:scheme :https :server-name "admin.example.com" :server-port 9999}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-link-with-overrides
  (are [routes] (= "http://admin-staging.example.com:8080/user/456/delete"
                   ((make-linker routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :scheme :http
                    :host "admin-staging.example.com"
                    :port 8080
                    :request {:scheme :https :server-name "admin.example.com" :server-port 9999}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-link-absolute
  (are [routes] (= "https://admin.example.com:9999/user/456/delete"
                   ((make-linker routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :request {:scheme :https :server-name "admin.example.com" :server-port 9999}
                    :absolute? true))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest delete-user-action-with-host
  (are [routes] (= {:action "/user/456/delete?_method=delete"
                    :method "post"}
                   ((make-action routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :request {:scheme :https :server-name "admin.example.com" :server-port 9999}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest search-id-link
  (are [routes] (= "/search?id=456"
                   ((make-linker routes) ::search-id :params {:id 456}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest search-id-with-host
  (are [routes] (= "/search?id=456"
                   ((make-linker routes) ::search-id :params {:id 456}
                    :request {:server-name "foo.com" :scheme :https}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest search-id-link-with-extra-params
  (are [routes] (let [s ((make-linker routes) ::search-id :params {:id 456 :limit 100})]
                  (is (#{"/search?id=456&limit=100"
                         "/search?limit=100&id=456"} s)))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))                                        ; order is undefined

(deftest search-id-link-with-extra-params-and-fragment
  (are [routes] (let [s ((make-linker routes) ::search-id :params {:id 456 :limit 100} :fragment "foo")]
                  (is (#{"/search#foo?id=456&limit=100"
                         "/search#foo?limit=100&id=456"} s)))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest search-query-link
  (are [routes] (= "/search?q=Hello%2C+World%21"
                   ((make-linker routes) ::search-query :params {:q "Hello, World!"}))
    verbose-routes
    terse-routes
    data-routes
    syntax-quote-data-routes
    quoted-tabular-routes
    tabular-routes))

(deftest query-encoding
  (are [s] (= s (route/decode-query-part (route/encode-query-part s)))
    "♠♥♦♣"                                                  ; outside the basic multilingual plane
    "䷂䷖䷬䷴"                                                  ; three-byte UTF-8 characters
    "\"Houston, we have a problem!\""
    "/?:@-._~!$'()* ,;=="))

(deftest t-query-params
  (are [s m] (= m (route/parse-query-string s))
    "a=1&b=2"
    {:a "1" :b "2"}
    "a=&b=2"
    {:a "" :b "2"}
    "message=%22Houston%2C+we+have+a+problem!%22"
    {:message "\"Houston, we have a problem!\""}
    "hexagrams=%E4%B7%82%E4%B7%96%E4%B7%AC%E4%B7%B4"
    {:hexagrams "䷂䷖䷬䷴"}                                     ; three-byte UTF-8 characters
    "suits=%E2%99%A0%E2%99%A5%E2%99%A6%E2%99%A3"
    {:suits "♠♥♦♣"}                                         ; outside the basic multilingual plane
    "Hello%2C%20World!=Hello%2C%20World!"
    {(keyword "Hello, World!") "Hello, World!"}
    "/?:@-._~!$'()*+,;=/?:@-._~!$'()*+,;=="
    {(keyword "/?:@-._~!$'()* ,;") "/?:@-._~!$'()* ,;=="}))

(deftest t-path-params
  (are [r m] (= m (route/parse-param-map r))
    {:message "Hello+World"
     :name    "John+Doe"}
    {:message "Hello World"
     :name    "John Doe"}
    {:suits "%E2%99%A0%E2%99%A5%E2%99%A6%E2%99%A3"}
    {:suits "♠♥♦♣"}
    {:message "%22Houston%2C+we+have+a+problem!%22"}
    {:message "\"Houston, we have a problem!\""}
    {:message "?:@-._~!$'()* ,;=="}
    {:message "?:@-._~!$'()* ,;=="}
    {:delimiters ":#[]@"}
    {:delimiters ":#[]@"}
    {:sub-delimiters "!$&'()*,;="}
    {:sub-delimiters "!$&'()*,;="}
    {:unreserved "abcdefghiklmnopqrstuvwxyz0123456789-._~"}
    {:unreserved "abcdefghiklmnopqrstuvwxyz0123456789-._~"}))

(defn ring-style
  "A ring style request handler."
  [_request]
  {:status  200
   :body    "Oppa Ring Style!"
   :headers {}})

(defhandler ring-adapted
  [request] (ring-style request))

(defn make-ring-adapted
  "An interceptor fn which returns ring-adapted when called."
  []
  ;; This new ring-adapted needs a unique name when it is added into the routes
  [::another-ring-adapted ring-adapted])

(def ring-adaptation-routes                                 ;; When the handler for a verb is a ring style middleware, automagically treat it as an interceptor
  (expand-routes
    `[[:ring-adaptation "ring-adapt.pedestal"
       ["/adapted" {:get ring-style}]
       ["/verbatim" {:get ring-adapted}]
       ["/returned" {:get (make-ring-adapted)}]]]))

(defn test-ring-adapting [router-impl-key]
  (are [path] (= "Oppa Ring Style!" (-> ring-adaptation-routes
                                        (test-query-execute router-impl-key
                                                            {:request {:request-method :get
                                                                       :scheme         "do-not-match-scheme"
                                                                       :server-name    "ring-adapt.pedestal"
                                                                       :path-info      path
                                                                       :query-params   {}}})
                                        :response
                                        :body))
    "/adapted"
    "/verbatim"
    "/returned"))

(deftest ring-adapting-prefix-tree
  (test-ring-adapting :prefix-tree))

(deftest ring-adapting-sawtooth
  (test-ring-adapting :sawtooth))

(deftest ring-adapting-linear-search
  (test-ring-adapting :linear-search))

(defn overridden-handler
  "A handler which will be overridden."
  [_request]
  {:status  200
   :body    "Overridden"
   :headers {}})

(defn overriding-handler
  "A handler which will override."
  [_request]
  {:status  200
   :body    "Overriding"
   :headers {}})

(def overridden-routes
  (expand-routes
    `[[:overridden-routes "overridden.pedestal"
       ["/resource" {:get overridden-handler}]]]))

(def overriding-routes
  (expand-routes
    `[[:overridden-routes "overridden.pedestal"
       ["/resource" {:get overriding-handler}]]]))

(defn test-overriding-routes [router-impl-key]
  (let [router (app-router #(deref #'overridden-routes) router-impl-key)
        query  {:request {:request-method :get
                          :scheme         "do-not-match-scheme"
                          :server-name    "overridden.pedestal"
                          :path-info      "/resource"
                          :query-params   {}}}]
    (is (= "Overridden" (-> (interceptor.chain/enqueue* query
                                                        route/query-params
                                                        (route/method-param)
                                                        router)
                            interceptor.chain/execute
                            :response
                            :body))
        "When the overridden-routes have their base binding, routing dispatches to the base binding")
    (is (= "Overriding" (with-redefs [overridden-routes overriding-routes]
                          (-> (interceptor.chain/enqueue* query
                                                          route/query-params
                                                          (route/method-param)
                                                          router)
                              interceptor.chain/execute
                              :response
                              :body)))
        "When the overridden-routes have their binding overridden, routing dispatches to the overridden binding")))

(deftest overriding-routes-test-prefix-tree
  (test-overriding-routes :prefix-tree))

(deftest overriding-routes-test-sawtooth
  (test-overriding-routes :sawtooth))

(deftest overriding-routes-test-linear-search
  (test-overriding-routes :linear-search))

(deftest route-names-match-test
  (let [verbose-route-names           (set (map :route-name verbose-routes))
        terse-route-names             (set (map :route-name terse-routes))
        data-route-names              (set (map :route-name data-routes))
        syntax-quote-data-route-names (set (map :route-name syntax-quote-data-routes))
        tabular-route-names           (set (map :route-name tabular-routes))]
    (is (and (empty? (set/difference verbose-route-names terse-route-names))
             (empty? (set/difference terse-route-names data-route-names))
             (empty? (set/difference data-route-names syntax-quote-data-route-names))
             (empty? (set/difference syntax-quote-data-route-names tabular-route-names)))
        "Route names for all routing syntaxes match")))

(deftest url-for-without-*url-for*-should-error-properly
  (is (thrown-with-msg? ExceptionInfo #"\*url-for\* not bound"
                        (route/url-for :my-route))))

(deftest method-param-test
  (let [context {:request {:request-method :get}}]
    (is (= {:request {:request-method :delete}}
           ((:enter (route/method-param))
            (assoc-in context [:request :query-params :_method] "delete")))
        "extracts method from :query-params :_method by default")
    (is (= {:request {:request-method :delete}}
           ((:enter (route/method-param :_method))
            (assoc-in context [:request :query-params :_method] "delete")))
        "is configurable to extract method from param in :query-params (old interface)")
    (is (= {:request {:request-method :put}}
           ((:enter (route/method-param [:body-params "_method"]))
            (assoc-in context [:request :body-params "_method"] "put")))
        "is configurable to extract method from any path in request")))

;; Issue 314 - url-for-routes doesn't let you specify a default
;; app-name option value
(deftest respect-app-name-in-url-for-routes
  (let [url-for-admin  (route/url-for-routes terse-routes :app-name :admin)
        url-for-public (route/url-for-routes terse-routes :app-name :public)]
    (is (= "https://admin.example.com:9999/user/alice/delete" (url-for-admin ::delete-user :path-params {:user-id "alice"})))
    (is (= "//example.com/" (url-for-public ::home-page)))))

(deftest required-path-params-in-url-for-routes
  (let [url-for-admin  (route/url-for-routes terse-routes :app-name :admin)
        url-for-public (route/url-for-routes terse-routes :app-name :public)]
    (testing "strict path params doesn't affect behavior when all params are present"
      (is (= "https://admin.example.com:9999/user/alice/delete"
             (url-for-admin ::delete-user
                            :strict-path-params? true
                            :path-params {:user-id "alice"})
             (url-for-admin ::delete-user
                            :strict-path-params? false
                            :path-params {:user-id "alice"})))
      (is (= "//example.com/"
             (url-for-public ::home-page
                             :strict-path-params? true)
             (url-for-public ::home-page
                             :strict-path-params? false))))
    (testing "missing path params works when :strict-path-params isn't set, or set to false"
      (is (= "https://admin.example.com:9999/user/:user-id/delete"
             (url-for-admin ::delete-user)))
      (is (= "https://admin.example.com:9999/user/:user-id/delete"
             (url-for-admin ::delete-user
                            :stric-path-params? false))))
    (testing "missing path params throws an exception when :strict-path-params is true"
      (is (= [:user-id]
             (get-in (ex-data (try (url-for-admin ::delete-user
                                                  :strict-path-params? true
                                                  :path-params {:user-id nil})
                                   (catch ExceptionInfo e
                                     e)))
                     [:route :path-params]))
          "Should throw when nil.")
      (is (= [:user-id]
             (get-in (ex-data (try (url-for-admin ::delete-user
                                                  :strict-path-params? true)
                                   (catch ExceptionInfo e
                                     e)))
                     [:route :path-params]))
          "Should throw when missing."))))

;; Map-route support

(deftest map-routes->vec-routes-string-key
  (let [routes-under-test {"/" {:get :no}}]
    (is (= ["/" {:get :no}]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-nested-routes
  (let [routes-under-test {"/" {"/foo" {:get :nest}}}]
    (is (= ["/" ["/foo" {:get :nest}]]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-routes-with-interceptor
  (let [routes-under-test {"/" {:interceptors [interceptor-1]
                                "/foo"        {:get :int}}}]
    (is (= ["/" ^:interceptors [interceptor-1] ["/foo" {:get :int}]]
           (map-routes->vec-routes routes-under-test)))))

(defn- constraints-meta [o]
  (when-let [m (some-> o meta (select-keys [:constraints]))]
    (if (empty? m) nil m)))

(deftest map-routes->vec-routes-with-constraints
  (let [regex-constraint  #"[0-9]+"
        routes-under-test {"/:user-id" {:constraints {:user-id regex-constraint}
                                        "/foo"       {:get :int}}}
        expected-routes   ["/:user-id" ^:constraints {:user-id regex-constraint} ["/foo" {:get :int}]]
        expected-meta     (map meta expected-routes)]
    (is (= expected-routes (map-routes->vec-routes routes-under-test)))
    (is (= expected-meta (map constraints-meta (map-routes->vec-routes routes-under-test))))))

(deftest map-routes->vec-routes-advanced2
  (let [routes-under-test {"/" {:get          :advanced
                                :interceptors [interceptor-1]}}]
    (is (= ["/" {:get :advanced} ^:interceptors [interceptor-1]]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-routes-advanced
  (let [routes-under-test {"/" {:get          :advanced
                                :interceptors [interceptor-1]
                                "/redirect"   {"/google"    {:get :advanced}
                                               "/somewhere" {:get :advanced}}}}]
    (is (= ["/" {:get :advanced}
            ^:interceptors [interceptor-1]
            ["/redirect"
             ["/google" {:get :advanced}]
             ["/somewhere" {:get :advanced}]]]
           (map-routes->vec-routes routes-under-test)))))

(defn test-match-root-trailing-slash-map [router-impl-key]
  (are [routes] (= {:route-name ::trailing-slash :path "/child-path"}
                   (-> routes
                       (test-query-execute
                         router-impl-key
                         {:request {:request-method :get
                                    :path-info      "/child-path"}})
                       :route
                       (select-keys [:route-name :path])))
    map-routes
    data-map-routes))

(deftest match-root-trailing-slash-map-prefix-tree
  (test-match-root-trailing-slash-map :prefix-tree))

(deftest match-root-trailing-slash-map-sawtooth
  (test-match-root-trailing-slash-map :sawtooth))

(deftest match-root-trailing-slash-map-linear-searc
  (test-match-root-trailing-slash-map :linear-search))


(deftest match-update-map
  (are [routes] (= {:route-name  ::update-user
                    :path-params {:user-id "123"}}
                   (test-match routes :prefix-tree :put "/user/123"))
    map-routes
    data-map-routes))

;; Issue #340 - correctly 404 non-matching routes
;; https://github.com/pedestal/pedestal/issues/340
(defn echo-request
  [request]
  {:status  200
   :body    (pr-str request)
   :headers {}})

(def routes-with-params
  (expand-routes
    `[[["/a/:a/b/:b" {:any echo-request}]]]))

(deftest non-matching-routes-should-404
  (are [path]
    (-> (test-query-execute routes-with-params
                            :prefix-tree
                            {:request
                             {:request-method :get
                              :path-info      path}})
        :route
        nil?)
    "/a"
    "/a/"
    "/a/a"
    "/a/a/b"
    "/a/a/b/"
    "/a/a/b/b/"
    "/a/a/b/b/c"))

(deftest nested-path-params
  (let [routes (fn [spec] (-> spec expand-routes :routes first))
        terse-with-root `[[["/base/:resource/:thing" {:get add-user}]]]
        terse-sans-root `[[["/:resource/:thing" {:get add-user}]]]
        table-with-root #{["/base/:resource/:thing" :get add-user]}
        table-sans-root #{["/:resource/:thing" :get add-user]}]
    (testing "path parts extracted with root"
      (is (= ["base" :resource :thing]
             (:path-parts (routes terse-with-root))
             (:path-parts (routes table-with-root))
             (:path-parts (routes table-with-root)))))

    (testing "path parts extracted without root"
      (is (= [:resource :thing]
             (:path-parts (routes terse-sans-root))
             (:path-parts (routes table-sans-root)))))

    (testing "path params extracted"
      (is (= [:resource :thing]
             (:path-params (routes terse-with-root))
             (:path-params (routes table-with-root))
             (:path-params (routes terse-sans-root))
             (:path-params (routes table-sans-root))
             (:path-params (routes table-sans-root)))))))

;; Static routes with the Map-Tree
(deftest static-map-route-rules
  (are [req]
    (let [full-req {:request
                    (merge {:request-method :get} req)}]
      (= (:route-name (test-query-execute static-quoted-tabular-routes :map-tree full-req))
         (:route-name (test-query-execute static-quoted-tabular-routes :prefix-tree full-req))
         (:route-name (test-query-execute static-quoted-tabular-routes :linear-search full-req))
         (:route-name (test-query-execute static-quoted-tabular-routes :sawtooth full-req))))
    {:path-info "/search" :query-string nil}
    {:path-info "/search" :query-string "q=1234"}
    {:request-method :put :path-info "/search" :query-string "q=1234"}
    {:request-method :put :path-info "/search"}
    {:path-info "/logout"}
    {:request-method :post :path-info "/logout"}))

(deftest url-param-trailing-slash
  (let [routes (expand-routes `[[["/api/" {:get list-users}]
                                 ["/things/:date"
                                  ["/" {:get home-page}]]]])
        rts-fn (route/url-for-routes routes)]
    (is (= "/things/2017-01-23/"
           (rts-fn ::home-page :params {:date "2017-01-23"})))
    (is (= "/api/"
           (rts-fn ::list-users)))))

;; Verb neutral routing
;; -------------------------
(deftest verb-neutral-routing
  (let [test-routes       [{:path "/app" :method :quux}]
        test-request      {:path-info "/app" :request-method :quux}
        test-bad-request  {:path-info "/app" :request-method :foo}
        expand-route-path (fn [route] (->> (:path route)
                                           path/parse-path
                                           (merge route)
                                           path/merge-path-regex))
        test-routers      [(map-tree/router test-routes)
                           (prefix-tree/router test-routes)
                           (linear-search/router (mapv expand-route-path test-routes))]]
    (is (every? some? (map #(% test-request) test-routers)))
    (is (every? nil? (map #(% test-bad-request) test-routers)))))

(deftest verb-neutral-table-routes
  (let [test-routes      (expand-routes
                           (table-routes {:verbs #{:walk :open :read :clunk :stat :wstat :version}}
                                         #{["/hello" :walk `home-page]}))
        test-request     {:path-info "/hello" :request-method :walk}
        test-bad-request {:path-info "/hello" :request-method :clunk}
        test-routers     [(map-tree/router test-routes)
                          (prefix-tree/router test-routes)]]
    (is (every? some? (map #(% test-request) test-routers)))
    (is (every? nil? (map #(% test-bad-request) test-routers)))
    (is (try
          ;; `thrown` won't work with Compiler/AssertionErrors
          (table-routes {:verbs #{:walk :open :read :clunk :stat :wstat :version}}
                        (expand-routes #{["/hello" :remove `home-page]}))
          false
          (catch AssertionError _
            true)))))

(deftest url-for-concatenates-context-path-and-route-path-test
  ;; Also tests a fix for Pedestal Issue 610 where routes whose first segment is a path parameter
  ;; throw an exception when a context path is supplied.
  ;; see https://github.com/pedestal/pedestal/issues/610

  (let [routes (route/expand-routes #{["/:a-parameter" :get `home-page]})]
    (is (= "/some/context/a-value"
           ((route/url-for-routes routes) ::home-page :params {:a-parameter "a-value"} :context "some/context")))))


(defn- attempt-route
  [routes router-type verb path]
  (route/try-routing-for (expand-routes routes)
                         router-type
                         path
                         verb))

(deftest wildcard-trumps-static-under-prefix-tree
  (let [routes #{["/users/:id" :get [`view-user] :route-name ::view-user :constraints {:id #"\d+"}]
                 ["/users/logout" :post [`logout] :route-name ::logout]}]
    (is (= nil
           (attempt-route routes :prefix-tree :get "/users/abc")))

    (is (match? {:route-name  ::view-user
                 :path-params {:id "123"}}
                (attempt-route routes :prefix-tree :get "/users/123")))

    ;; This is the cause of pain, as one would think that a constraint failure on the wildcard match would
    ;; drop down to match the static path, or that routing would take :request-method into account.
    (is (= nil
           (attempt-route routes :prefix-tree :post "/users/logout")))

    ;; Have to use :linear-search to get the desired behavior:

    (is (match? {:route-name  ::view-user
                 :path-params {:id "123"}}
                (attempt-route routes :linear-search :get "/users/123")))

    (is (= nil
           (attempt-route routes :linear-search :get "/users/abc")))

    (is (match? {:route-name ::logout}
                (attempt-route routes :linear-search :post "/users/logout")))))

(deftest routes-check-for-unexpanded-routes
  ;; Ensure that all the built-in routers can tell the difference between a fragment and a fully expanded
  ;; routing table.
  (let [fragment (table-routes `[["/users" :get list-users]])]
    (doseq [router-fn [linear-search/router
                       map-tree/router
                       prefix-tree/router
                       sawtooth/router]]
      (is (thrown-with-msg? ExceptionInfo
                            #"\QMust pass route fragment through io.pedestal.http.route/expand-routes\E"
                            (router-fn fragment))))))

(deftest must-pass-fragments-to-expand-routes
  (is (thrown-with-msg? ExceptionInfo
                        #"\QValue does not satisfy io.pedestal.http.route/ExpandableRoutes\E"
                        (expand-routes false))))

(def i-1
  (interceptor {:name  :i-1
                :enter identity}))

(def i-2
  (interceptor {:name  :i-2
                :enter identity}))

(def i-3
  (interceptor {:name  :i-3
                :enter identity}))

(def handler
  (interceptor {:name  :handler
                :enter identity}))

(deftest table-routes-interceptor-opt-is-prefix

  (let [routes  (table/table-routes {:interceptors [i-1 i-2]}
                                    #{["/root/one" :get handler :route-name :one]
                                      ["/root/two" :get [i-3 handler] :route-name :two]
                                      ["/root/three" :get [handler] :route-name :three]})
        by-name (medley/index-by :route-name (:routes routes))]
    (is (match?
          {:one   {:interceptors [i-1 i-2 handler]}
           :two   {:interceptors [i-1 i-2 i-3 handler]}
           :three {:interceptors [i-1 i-2 handler]}}
          by-name))))
