; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.quoted-routes-shim-test
  "Pedestal 1.0 no longer supports quoted route specifications: neither the IntoInterceptor nor the
  ExpandableVerbAction protocol is extended to symbols or lists. It still lets symbols and lists reach
  these protocols, so that a compatibility shim can extend them to these types. With such a shim, quoted
  specifications give exactly the routing tables of Pedestal 0.8.3; without it, they fail."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.quoted-routes-shim :as shim]))

(defn list-orders [_request] {:status 200 :body "orders"})
(defn get-order [_request] {:status 200 :body "order"})
(defn create-order [_request] {:status 201})

(def audit (interceptor/interceptor {:name ::audit :enter identity}))

;; The quoted specifications are the ones of the hook check run against Pedestal 0.8.3.

;; Syntax-quoted: qualified symbols, and expressions that are Cons (not lists).
(def terse-spec
  `[[["/orders" {:get  list-orders
                 :post [:create-order create-order]}
      ^:interceptors [(body-params/body-params) audit]
      ["/:id" {:get [get-order]}]]]])

(def table-spec
  `#{["/t/orders" :get list-orders]
     ["/t/orders" :post [(body-params/body-params) create-order] :route-name :t-create]
     ["/t/orders/:id" :get [audit get-order]]})

;; Plain-quoted: unqualified symbols (resolved in *ns*), and an expression that is a list.
(def plain-spec
  '[[["/p" {:get list-orders}
      ["/:id" {:put (io.pedestal.interceptor/interceptor {:name :p/put :enter clojure.core/identity})}]]]])

;; The documented replacement: Vars instead of symbols, and the expressions called when the routes are defined.

(def terse-var-spec
  [[["/orders" {:get  #'list-orders
                :post [:create-order #'create-order]}
     ^:interceptors [(body-params/body-params) #'audit]
     ["/:id" {:get [#'get-order]}]]]])

(def table-var-spec
  #{["/t/orders" :get #'list-orders]
    ["/t/orders" :post [(body-params/body-params) #'create-order] :route-name :t-create]
    ["/t/orders/:id" :get [#'audit #'get-order]]})

(def plain-var-spec
  [[["/p" {:get #'list-orders}
     ["/:id" {:put (interceptor/interceptor {:name :p/put :enter identity})}]]]])

;; What Pedestal 0.8.3 produced for the quoted specifications (out-baseline.edn of the hook check,
;; with its namespace replaced by this one): [path method route-name interceptor-names] per route.
(def expected-tables
  {[:plain false] [["/p" :get ::list-orders [::list-orders]]
                   ["/p/:id" :put :p/put [:p/put]]]
   [:plain true]  [["/p" :get ::list-orders [::list-orders]]
                   ["/p/:id" :put :p/put [:p/put]]]
   [:table false] [["/t/orders" :get ::list-orders [nil]]
                   ["/t/orders" :post :t-create [::body-params/body-params nil]]
                   ["/t/orders/:id" :get ::get-order [::audit nil]]]
   [:table true]  [["/t/orders" :get ::list-orders [::list-orders]]
                   ["/t/orders" :post :t-create [::body-params/body-params ::create-order]]
                   ["/t/orders/:id" :get ::get-order [::audit ::get-order]]]
   [:terse false] [["/orders" :get ::list-orders [::body-params/body-params ::audit ::list-orders]]
                   ["/orders" :post :create-order [::body-params/body-params ::audit :create-order]]
                   ["/orders/:id" :get ::get-order [::body-params/body-params ::audit ::get-order]]]
   [:terse true]  [["/orders" :get ::list-orders [::body-params/body-params ::audit ::list-orders]]
                   ["/orders" :post :create-order [::body-params/body-params ::audit ::create-order]]
                   ["/orders/:id" :get ::get-order [::body-params/body-params ::audit ::get-order]]]})

(def quoted-specs
  {:terse terse-spec
   :table table-spec
   :plain plain-spec})

(def var-specs
  {:terse terse-var-spec
   :table table-var-spec
   :plain plain-var-spec})

(defn- table-of
  [spec]
  ;; The plain-quoted specification has unqualified symbols, resolved (and evaluated) in *ns*.
  (binding [*ns* (the-ns 'io.pedestal.http.quoted-routes-shim-test)]
    (->> (route/expand-routes spec)
         :routes
         (map (fn [r]
                [(:path r) (:method r) (:route-name r) (mapv :name (:interceptors r))]))
         sort
         vec)))

(defn- root-cause
  [t]
  (if-let [c (ex-cause t)]
    (recur c)
    t))

(def ^:private rejections
  "Without the shim, how each quoted specification fails: the type of the exception, and the start of
  its message. Terse routes fail at the first symbol given as a verb action (no ExpandableVerbAction
  extension for symbols); table routes check that each handler satisfies IntoInterceptor."
  {:terse [IllegalArgumentException
           (str "No implementation of method: :expand-verb-action of protocol:"
                " #'io.pedestal.http.route.definition.terse/ExpandableVerbAction found for class: clojure.lang.Symbol")]
   :plain [IllegalArgumentException
           (str "No implementation of method: :expand-verb-action of protocol:"
                " #'io.pedestal.http.route.definition.terse/ExpandableVerbAction found for class: clojure.lang.Symbol")]
   :table [AssertionError
           "Assert failed: In row 1, the handler should have been an interceptor but was io.pedestal.http.quoted-routes-shim-test/"]})

(defn- assert-rejected
  [label spec]
  (testing label
    (let [[exception-class message-start] (rejections label)]
      (when-let [e (is (thrown? Throwable (table-of spec)))]
        (let [root (root-cause e)]
          (is (instance? exception-class root))
          (is (string/starts-with? (ex-message root) message-start)))))))

(deftest quoted-specs-fail-without-the-shim
  (doseq [names? [true false]
          [label spec] quoted-specs]
    (binding [interceptor/*default-handler-names* names?]
      (testing names?
        (assert-rejected label spec)))))

(deftest quoted-specs-with-the-shim-give-the-0-8-routing-tables
  (shim/with-shim
    (doseq [names? [true false]
            [label spec] quoted-specs]
      (testing [label names?]
        (binding [interceptor/*default-handler-names* names?]
          (is (= (get expected-tables [label names?])
                 (table-of spec))))))))

(deftest vars-and-called-expressions-give-the-same-routing-tables
  (doseq [names? [true false]
          [label spec] var-specs]
    (testing [label names?]
      (binding [interceptor/*default-handler-names* names?]
        (is (= (get expected-tables [label names?])
               (table-of spec)))))))

(def ^:private no-extensions
  "Pedestal 1.0 extends neither protocol to symbols or lists."
  {:into-interceptor {}
   :verb-action      {}})

(deftest quoted-specs-fail-again-after-the-shim-is-removed
  (is (= no-extensions (shim/current-impls)) "no extension before the shim")
  (shim/with-shim
    (is (= (set shim/into-interceptor-types) (-> (shim/current-impls) :into-interceptor keys set))
        "the shim is installed")
    (is (= (set shim/verb-action-types) (-> (shim/current-impls) :verb-action keys set)))
    (is (= (expected-tables [:terse true]) (table-of terse-spec))))
  (is (= no-extensions (shim/current-impls)) "the shim's extensions are removed")
  (doseq [[label spec] quoted-specs]
    (assert-rejected label spec))
  (is (not (satisfies? interceptor/IntoInterceptor 'clojure.core/identity))
      "satisfies? doesn't see a stale extension")

  (testing "the shim's extensions are removed when the body throws"
    (is (thrown-with-msg? Exception #"inside the shim"
                          (shim/with-shim
                            (throw (ex-info "inside the shim" {})))))
    (is (= no-extensions (shim/current-impls)))
    (assert-rejected :terse terse-spec))

  (testing "an extension that existed before the shim is put back"
    (let [marker (fn [_] {:name ::marker :enter identity})]
      (extend clojure.lang.Symbol interceptor/IntoInterceptor {:-interceptor marker})
      (try
        (shim/with-shim
          (is (not= marker (get-in (shim/current-impls) [:into-interceptor clojure.lang.Symbol :-interceptor]))))
        (is (= {:into-interceptor {clojure.lang.Symbol {:-interceptor marker}}
                :verb-action      {}}
               (shim/current-impls)))
        (finally
          (-reset-methods (alter-var-root #'interceptor/IntoInterceptor update :impls dissoc clojure.lang.Symbol))))
      (is (= no-extensions (shim/current-impls))))))
