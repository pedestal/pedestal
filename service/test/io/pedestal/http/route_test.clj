; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route-test
  (:use io.pedestal.http.route
        clojure.pprint
        clojure.test
        clojure.repl)
  (:require [clojure.set :as set]
            [clojure.string :as str]
            ring.middleware.resource
            [ring.util.response :as ring-response]
            [io.pedestal.interceptor.helpers :as interceptor
             :refer [defhandler defon-request defbefore definterceptor handler]]
            [io.pedestal.impl.interceptor :as interceptor-impl]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.verbose :as verbose]
            [io.pedestal.http.route.definition :refer [defroutes expand-routes map-routes->vec-routes]]))

(defhandler home-page
  [request]
  "home-page")
(defhandler  list-users
  [request]
  "list-users")
(defhandler view-user
  [request]
  "view-user")
(defhandler add-user
  [request]
  "add-user")
(defhandler update-user
  [request]
  "update-user")
(defhandler logout
  [request]
  "logout")
(defhandler delete-user
  [request]
  "delete-user")
(defhandler search-form
  [request]
  "search-form")
(defhandler search-id
  [request]
  "search-id")
(defhandler search-query
  [request]
  "search-query")
(defhandler trailing-slash
  [request]
  "trailing-slash")

(defhandler request-inspection
  [req] {:request req})

(defon-request interceptor-1
  [req] (assoc req ::interceptor-1 :fired))

(defon-request interceptor-2
  [req] (if (= :fired (::interceptor-1 req))
          (assoc req ::interceptor-1 :clobbered)
          (assoc req ::interceptor-2 :fired-without-1)))

(defn interceptor-3
  ([] (interceptor-3 ::fn-called-implicitly))
  ([value]
     (interceptor/on-request
      (fn [req] (assoc req ::interceptor-3 value)))))

(defn site-demo [site-name]
  (fn [req & more]
    (ring-response/response (str "demo page for " site-name))))

;; schemes, hosts, path, verb and maybe query string
(verbose/defroutes verbose-routes ;; the verbose hierarchical data structure
  [{:app-name :public
    :host "example.com"
    ;;    :interceptors []
    :children [{:path "/"
                ;; :interceptors []
                ;;                :verbs {:get {:handler home-page :interceptors []}
                :verbs {:get home-page}
                :children [{:path "/child-path"
                            :verbs {:get trailing-slash}}]}
               {:path "/user"
                :verbs {:get list-users
                        :post add-user}
                :children [{:path "/:user-id"
                            :constraints {:user-id #"[0-9]+"}
                            :verbs {:put update-user}
                            :children [{:constraints {:view #"long|short"}
                                        :verbs {:get view-user}}]}]}]}
   {:app-name :admin
    :scheme :https
    :host "admin.example.com"
    :port 9999
    :children [{:path "/demo/site-one/*site-path"
                ;; :verbs {:get {:name :site-one-demo :handler (site-demo "one") :interceptors []}}
                :verbs {:get {:route-name :site-one-demo
                              :handler (site-demo "one")}}}
               {:path "/demo/site-two/*site-path"
                :verbs {:get {:route-name :site-two-demo
                              :handler (site-demo "two")}}}
               {:path "/user/:user-id/delete"
                :verbs {:delete delete-user}}]}
   {:children [{:path "/logout"
                :verbs {:any logout}}
               {:path "/search"
                :verbs {:get search-form}
                :children [{:constraints {:id #"[0-9]+"}
                            :verbs {:get search-id}}
                           {:constraints {:q #".+"}
                            :verbs {:get search-query}}]}
               {:path "/intercepted"
                :verbs {:get {:route-name :intercepted
                              :handler request-inspection}}
                :interceptors [interceptor-1 interceptor-2]}
               {:path "/intercepted-by-fn-symbol"
                :verbs {:get {:route-name :intercepted-by-fn-symbol
                              :handler request-inspection}}
                :interceptors [interceptor-3]}
               {:path "/intercepted-by-fn-list"
                :verbs {:get {:route-name :intercepted-by-fn-list
                              :handler request-inspection}}
                :interceptors [(interceptor-3 ::fn-called-explicitly)]}
               {:path "/trailing-slash/"
                :children [{:path "/child-path"
                            :verbs {:get {:route-name :admin-trailing-slash
                                          :handler trailing-slash}}}]}
               {:path "/hierarchical"
                :interceptors [interceptor-1]
                :children [{:path "/intercepted"
                            :interceptors [interceptor-2]
                            :verbs {:get {:route-name :hierarchical-intercepted
                                          :handler request-inspection}}}]}
               {:path "/terminal/intercepted"
                :verbs {:get {:route-name :terminal-intercepted
                              :handler request-inspection
                              :interceptors [interceptor-1 interceptor-2]}}}]}])

(defroutes terse-routes ;; the terse hierarchical data structure
  [[:public "example.com"
    ["/" {:get home-page}
     ["/child-path" {:get trailing-slash}]]
    ["/user" {:get list-users
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
     {:get [:terminal-intercepted ^:interceptors [interceptor-1 interceptor-2] request-inspection]}]]])

(defroutes map-routes
  ;; One limitation is you can't control hostname or protocol
  {"/" {:get home-page
        "/child-path" {:get trailing-slash}
        "/user" {:get list-users
                 :post add-user
                 "/:user-id" {:constraints {:user-id #"[0-9]+"}
                              :put update-user
                              ;; Note another limitation of map-routes is the inability to do per-verb constraints
                              :get view-user}}}})

(def data-map-routes
  (expand-routes
    [[(map-routes->vec-routes
        {"/" {:get home-page
              "/child-path" {:get trailing-slash}
              "/user" {:get list-users
                       :post add-user
                       "/:user-id" {:constraints {:user-id #"[0-9]+"}
                                    :put update-user
                                    ;; Note another limitation of map-routes is the inability to do per-verb constraints
                                    :get view-user}}}})]]))

(def data-routes
  (expand-routes
   [[:public "example.com"
      ["/" {:get home-page}
       ["/child-path" {:get trailing-slash}]]
      ["/user" {:get list-users
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
        ["/user" {:get list-users
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

;; HTTP verb-smuggling in query string is disabled here:
(defn make-linker
  [routes]
  (url-for-routes routes :method-param nil))
;; but enabled here:
(defn make-action
  [routes]
  (form-action-for-routes routes))
;; and here:
(defn app-router
  [routes]
  (router routes)) ;; switch to routes-2, routes-3

(defbefore print-context
  [context] (pprint context) context)

#_(defn test-match
  ([table method uri]
     (test-match table "do-not-match-scheme" "do-not-match-host" method uri nil))
  ([table host method uri]
     (test-match table "do-not-match-scheme" host method uri nil))
  ([table scheme host method uri]
     (test-match table scheme host method uri nil))
  ([table scheme host method uri qs]
     (let [{:keys [route request]}
           (-> {:request {:request-method method
                          :scheme scheme
                          :server-name host
                          :path-info uri
                          :query-string qs}}
               (interceptor-impl/enqueue query-params
                                         (method-param)
                                         (app-router table))
               interceptor-impl/execute)]
       (when route
         (merge
          {:route-name (:route-name route)
           :path-params (:path-params request)}
          (when-let [query-params (:query-params request)]
            {:query-params query-params}))))))

(defn test-match
  [route-table method uri & args]
  (let [{:keys [scheme host port query]
         :or {scheme "do-not-match-scheme"
              host "do-not-match-host"
              port -1}}
        (apply hash-map args)
        {:keys [route request]}
        (-> {:request {:request-method method
                       :scheme scheme
                       :server-name host
                       :server-port port
                       :path-info uri
                       :query-string query}}
            (interceptor-impl/enqueue query-params
                                      (method-param)
                                      (app-router route-table))
            interceptor-impl/execute)]
    (when route
      (merge
       {:route-name (:route-name route)
        :path-params (:path-params request)}
       (when-let [query-params (:query-params request)]
         {:query-params query-params})))))

(defn test-query-execute
  [table query]
  (-> query
      (interceptor-impl/enqueue query-params
                           (method-param)
                           (app-router table))
      interceptor-impl/execute))

(defn test-query-match [table uri params]
  (-> (test-query-execute table {:request {:request-method :get
                                           :scheme "do-not-match-scheme"
                                           :server-name "do-not-match-host"
                                           :path-info uri
                                           :query-string params}})
      :route
      :route-name))

(deftest fire-interceptors
  (are [routes] (= :clobbered
                   (-> (test-query-execute routes {:request {:request-method :get
                                                             :scheme "do-not-match-scheme"
                                                             :server-name "do-not-match-host"
                                                             :path-info "/intercepted"
                                                             :query-params {}}})
                       :response
                       :request
                       ::interceptor-1))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest fire-hierarchical-interceptors
  (are [routes] (= :clobbered
                   (-> (test-query-execute routes {:request {:request-method :get
                                                             :scheme "do-not-match-scheme"
                                                             :server-name "do-not-match-host"
                                                             :path-info "/hierarchical/intercepted"
                                                             :query-params {}}})
                       :response
                       :request
                       ::interceptor-1))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest fire-terminal-interceptors
  (are [routes] (= :clobbered
                   (-> (test-query-execute routes {:request {:request-method :get
                                                             :scheme "do-not-match-scheme"
                                                             :server-name "do-not-match-host"
                                                             :path-info "/terminal/intercepted"
                                                             :query-params {}}})
                       :response
                       :request
                       ::interceptor-1))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

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

(deftest fire-interceptor-fn-list
  (are [routes] (= ::fn-called-explicitly
                   (-> (test-query-execute routes {:request {:request-method :get
                                                             :scheme "do-not-match-scheme"
                                                             :server-name "do-not-match-host"
                                                             :path-info "/intercepted-by-fn-list"
                                                             :query-params {}}})
                       :response
                       :request
                       ::interceptor-3))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-root
  (are [routes] (= {:route-name ::home-page :path-params {}}
                   (test-match routes :get "/" :host "example.com"))
       verbose-routes
       terse-routes))

(deftest match-update-user
  (are [routes] (= {:route-name ::update-user
                    :path-params {:user-id "123"}}
                   (test-match routes :put "/user/123" :host "example.com"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-logout
  (are [routes] (= {:route-name ::logout :path-params {}}
                   (test-match routes :post "/logout"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-non-root-trailing-slash
  (are [routes] (= {:route-name :admin-trailing-slash :path "/trailing-slash/child-path"}
                   (-> routes
                       (test-query-execute
                        {:request {:request-method :get
                                   :path-info "/trailing-slash/child-path"}})
                       :route
                       (select-keys [:route-name :path])))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-root-trailing-slash
  (are [routes] (= {:route-name ::trailing-slash :path "/child-path"}
                   (-> routes
                       (test-query-execute
                        {:request {:request-method :get
                                   :server-name "example.com"
                                   :path-info "/child-path"}})
                       :route
                       (select-keys [:route-name :path])))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest check-host
  (are [routes] (nil? (test-match
                       routes :put "/user/123" :host "admin.example.com"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-demo-one
  (are [routes] (= {:route-name :site-one-demo
                    :path-params {:site-path "foo/bar/baz"}}
                   (test-match routes :get "/demo/site-one/foo/bar/baz"
                               :scheme :https
                               :host "admin.example.com"
                               :port 9999))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-user-constraints
  (are [routes] (= {:path-params {:user-id "123"} :route-name ::update-user}
                   (test-match routes :put "/user/123" :host "example.com"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= nil
                   (test-match routes :put "/user/abc" :host "example.com"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= {:path-params {:user-id "123"} :query-params {:view "long"} :route-name ::view-user}
                   (test-match routes :get "/user/123" :scheme :http :host "example.com" :query "view=long"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= nil
                   (test-match routes :get "/user/123" :scheme :http :host "example.com" :query "view=none"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= nil
                   (test-match routes :get "/user/abc" :scheme :http :host "example.com" :query "view=long"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest match-query
  (are [routes] (= ::search-id
                   (test-query-match routes "/search" "id=123"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= ::search-query
                   (test-query-match routes "/search" "q=foo"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= ::search-form
                   (test-query-match routes "/search" nil))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)
  (are [routes] (= ::search-form
                   (test-query-match routes "/search" "id=not-a-number"))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest trailing-slash-link
  (are [routes] (= "/child-path" ((make-linker routes)
                                  ::trailing-slash
                                  :app-name :public
                                  :request {:server-name "example.com"}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest logout-link
  (are [routes] (= "/logout" ((make-linker routes) ::logout :app-name :public))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest view-user-link
  (are [routes] (= "//example.com/user/456"
                   ((make-linker routes) ::view-user :app-name :public :params {:user-id 456}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

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
       syntax-quote-data-routes))

(deftest delete-user-link
  (are [routes] (= "https://admin.example.com:9999/user/456/delete"
                   ((make-linker routes) ::delete-user :app-name :admin :params {:user-id 456}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest delete-user-action
  (are [routes] (= {:action "https://admin.example.com:9999/user/456/delete?_method=delete"
                    :method "post"}
                   ((make-action routes) ::delete-user :app-name :admin :params {:user-id 456}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

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
       syntax-quote-data-routes))

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
       syntax-quote-data-routes))

(deftest delete-user-link-with-scheme
  (are [routes] (= "//admin.example.com:9999/user/456/delete"
                   ((make-linker routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :request {:scheme :https}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest delete-user-link-with-host
  (are [routes] (= "/user/456/delete"
                   ((make-linker routes) ::delete-user
                    :app-name :admin
                    :params {:user-id 456}
                    :request {:scheme :https :server-name "admin.example.com" :server-port 9999}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

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
       syntax-quote-data-routes))

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
       syntax-quote-data-routes))

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
       syntax-quote-data-routes))

(deftest search-id-link
  (are [routes] (= "/search?id=456"
                   ((make-linker routes) ::search-id :params {:id 456}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest search-id-with-host
  (are [routes] (= "/search?id=456"
                   ((make-linker routes) ::search-id :params {:id 456}
                    :request {:server-name "foo.com" :scheme :https}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest search-id-link-with-extra-params
  (are [routes] (let [s ((make-linker routes) ::search-id :params {:id 456 :limit 100})]
                  (is (#{"/search?id=456&limit=100"
                         "/search?limit=100&id=456"} s)))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes)) ; order is undefined

(deftest search-id-link-with-extra-params-and-fragment
  (are [routes] (let [s ((make-linker routes) ::search-id :params {:id 456 :limit 100} :fragment "foo")]
                  (is (#{"/search#foo?id=456&limit=100"
                         "/search#foo?limit=100&id=456"} s)))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest search-query-link
  (are [routes] (= "/search?q=Hello%2C+World%21"
                   ((make-linker routes) ::search-query :params {:q "Hello, World!"}))
       verbose-routes
       terse-routes
       data-routes
       syntax-quote-data-routes))

(deftest query-encoding
  (are [s] (= s (decode-query-part (encode-query-part s)))
       "♠♥♦♣"  ; outside the basic multilingual plane
       "䷂䷖䷬䷴"  ; three-byte UTF-8 characters
       "\"Houston, we have a problem!\""
       "/?:@-._~!$'()* ,;=="))

(deftest t-query-params
  (are [s m] (= m (parse-query-string s))
       "a=1&b=2"
       {:a "1" :b "2"}
       "a=&b=2"
       {:a "" :b "2"}
       "message=%22Houston%2C+we+have+a+problem!%22"
       {:message "\"Houston, we have a problem!\""}
       "hexagrams=%E4%B7%82%E4%B7%96%E4%B7%AC%E4%B7%B4"
       {:hexagrams "䷂䷖䷬䷴"}  ; three-byte UTF-8 characters
       "suits=%E2%99%A0%E2%99%A5%E2%99%A6%E2%99%A3"
       {:suits "♠♥♦♣"}  ; outside the basic multilingual plane
       "Hello%2C%20World!=Hello%2C%20World!"
       {(keyword "Hello, World!") "Hello, World!"}
       "/?:@-._~!$'()*+,;=/?:@-._~!$'()*+,;=="
       {(keyword "/?:@-._~!$'()* ,;") "/?:@-._~!$'()* ,;=="}))

(defn ring-style
  "A ring style request handler."
  [req]
  {:status 200
   :body "Oppa Ring Style!"
   :headers {}})

(defhandler ring-adapted
  "An interceptor created by adapting ring-style to the interceptor
  model. Should not be adapted."
  ring-style)

(defn make-ring-adapted
  "An interceptor fn which returns ring-adapted when called."
  []
  ;; This new ring-adpated needs a unique name when it is added into the routes
  [::another-ring-adapted ring-adapted])

(defroutes ring-adaptation-routes ;; When the handler for a verb is a ring style middleware, automagically treat it as an interceptor
  [[:ring-adaptation "ring-adapt.pedestal"
    ["/adapted" {:get ring-style}]
    ["/verbatim" {:get ring-adapted}]
    ["/returned" {:get (make-ring-adapted)}]]])

(deftest ring-adapting
  (are [path] (= "Oppa Ring Style!" (-> ring-adaptation-routes
                                        (test-query-execute {:request {:request-method :get
                                                                       :scheme "do-not-match-scheme"
                                                                       :server-name "ring-adapt.pedestal"
                                                                       :path-info path
                                                                       :query-params {}}})
                                        :response
                                        :body))
       "/adapted"
       "/verbatim"
       "/returned"))

(defn overridden-handler
  "A handler which will be overridden."
  [req]
  {:status 200
   :body "Overridden"
   :headers {}})

(defn overriding-handler
  "A handler which will override."
  [req]
  {:status 200
   :body "Overriding"
   :headers {}})

(defroutes overridden-routes
  [[:overridden-routes "overridden.pedestal"
    ["/resource" {:get overridden-handler}]]])

(defroutes overriding-routes
  [[:overridden-routes "overridden.pedestal"
    ["/resource" {:get overriding-handler}]]])

(deftest overriding-routes-test
  (let [router (app-router #(deref #'overridden-routes))
        query {:request {:request-method :get
                         :scheme "do-not-match-scheme"
                         :server-name "overridden.pedestal"
                         :path-info "/resource"
                         :query-params {}}}]
    (is (= "Overridden" (-> (interceptor-impl/enqueue query
                                                      query-params
                                                      (method-param)
                                                      router)
                            interceptor-impl/execute
                            :response
                            :body))
        "When the overridden-routes have their base binding, routing dispatches to the base binding")
    (is (= "Overriding" (with-redefs [overridden-routes overriding-routes]
                          (-> (interceptor-impl/enqueue query
                                                        query-params
                                                        (method-param)
                                                        router)
                              interceptor-impl/execute
                              :response
                              :body)))
        "When the overridden-routes have their binding overridden, routing dispatches to the overridden binding")))

(deftest route-names-match-test
  (let [verbose-route-names (set (map :route-name verbose-routes))
        terse-route-names (set (map :route-name terse-routes))
        data-route-names (set (map :route-name data-routes))
        syntax-quote-data-route-names (set (map :route-name syntax-quote-data-routes))]
    (is (and (empty? (set/difference verbose-route-names terse-route-names))
             (empty? (set/difference terse-route-names data-route-names))
             (empty? (set/difference data-route-names syntax-quote-data-route-names)))
        "Route names for all routing syntaxes match")))

(deftest url-for-without-*url-for*-should-error-properly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\*url-for\* not bound"
                        (url-for :my-route))))

(deftest method-param-test
  (let [context {:request {:request-method :get}}]
    (is (= {:request {:request-method :delete}}
           ((:enter (method-param))
            (assoc-in context [:request :query-params :_method] "delete")))
        "extracts method from :query-params :_method by default")
    (is (= {:request {:request-method :delete}}
           ((:enter (method-param :_method))
            (assoc-in context [:request :query-params :_method] "delete")))
        "is configurable to extract method from param in :query-params (old interface)")
    (is (= {:request {:request-method :put}}
           ((:enter (method-param [:body-params "_method"]))
            (assoc-in context [:request :body-params "_method"] "put")))
        "is configurable to extract method from any path in request")))

;; Map-route support

(deftest map-routes->vec-routes-string-key
  (let [routes-under-test {"/" {:get :no}}]
    (is (= [ "/" {:get :no} ]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-nested-routes
  (let [routes-under-test {"/" {"/foo" {:get :nest}} }]
    (is (= ["/" ["/foo" {:get :nest}]]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-routes-with-interceptor
  (let [routes-under-test {"/" {:interceptors [interceptor-1]
                                "/foo" {:get :int}} }]
    (is (= ["/" ^:interceptors [interceptor-1] ["/foo" {:get :int}]]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-routes-with-constraints
  (let [regex-constraint #"[0-9]+"
        routes-under-test {"/:user-id" {:constraints {:user-id regex-constraint}
                                        "/foo" {:get :int}}}]
    (is (= ["/:user-id" ^:constraints {:user-id regex-constraint}
            ["/foo" {:get :int}]]
           (map-routes->vec-routes routes-under-test)))
    (is (= (map meta ["/:user-id" ^:constraints {:user-id regex-constraint}
            ["/foo" {:get :int}]])
           (map meta (map-routes->vec-routes routes-under-test))))))

(deftest map-routes->vec-routes-advanced2
  (let [routes-under-test {"/" {:get :advanced
                                :interceptors [interceptor-1]}}]
    (is (= ["/" {:get :advanced} ^:interceptors [interceptor-1]]
           (map-routes->vec-routes routes-under-test)))))

(deftest map-routes->vec-routes-advanced
    (let [routes-under-test {"/" {:get :advanced
                                  :interceptors [interceptor-1]
                                  "/redirect" {"/google" {:get :advanced}
                                               "/somewhere" {:get :advanced}}}}]
      (is (= ["/" {:get :advanced}
              ^:interceptors [interceptor-1]
              ["/redirect"
               ["/google" {:get :advanced}]
               ["/somewhere" {:get :advanced}]]]
             (map-routes->vec-routes routes-under-test)))))

(deftest match-root-trailing-slash-map
  (are [routes] (= {:route-name ::trailing-slash :path "/child-path"}
                   (-> routes
                       (test-query-execute
                        {:request {:request-method :get
                                   :path-info "/child-path"}})
                       :route
                       (select-keys [:route-name :path])))
       map-routes
       data-map-routes))

(deftest match-update-map
  (are [routes] (= {:route-name ::update-user
                    :path-params {:user-id "123"}}
                   (test-match routes :put "/user/123"))
       map-routes
       data-map-routes))

