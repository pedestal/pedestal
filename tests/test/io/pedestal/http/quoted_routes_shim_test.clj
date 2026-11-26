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

;; Quoted specifications in the styles that Pedestal 0.8 supported, for each kind of route.

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

;; What Pedestal 0.8.3 produced for these specifications, by kind of route and whether default handler
;; names are enabled: [path method route-name interceptor-names] per route.
;;
;; One intended difference: in table routes without default handler names, the handler interceptors
;; had no name (nil) in 0.8.3. Pedestal 1.0 gives every unnamed interceptor a name derived from its
;; function, here the handler's (::list-orders, ::create-order, ::get-order). The route names, paths,
;; and methods are those of 0.8.3: route names never come from a derived name.
(def expected-tables
  {[:plain false] [["/p" :get ::list-orders [::list-orders]]
                   ["/p/:id" :put :p/put [:p/put]]]
   [:plain true]  [["/p" :get ::list-orders [::list-orders]]
                   ["/p/:id" :put :p/put [:p/put]]]
   [:table false] [["/t/orders" :get ::list-orders [::list-orders]]                         ; 0.8.3: [nil]
                   ["/t/orders" :post :t-create [::body-params/body-params ::create-order]] ; 0.8.3: [... nil]
                   ["/t/orders/:id" :get ::get-order [::audit ::get-order]]]                ; 0.8.3: [... nil]
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

(defn ^:private table-of
  [spec]
  ;; The plain-quoted specification has unqualified symbols, resolved (and evaluated) in *ns*.
  (binding [*ns* (the-ns 'io.pedestal.http.quoted-routes-shim-test)]
    (->> (route/expand-routes spec)
         :routes
         (map (fn [r]
                [(:path r) (:method r) (:route-name r) (mapv :name (:interceptors r))]))
         sort
         vec)))

(defn ^:private root-cause
  [t]
  (if-let [c (ex-cause t)]
    (recur c)
    t))

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

(deftest the-shim-is-removed-afterwards
  ;; Other tests check that quoted specifications fail (see io.pedestal.removals-test):
  ;; the shim must not stay installed, even when the body throws.
  (is (thrown-with-msg? Exception #"inside the shim"
                        (shim/with-shim
                          (binding [interceptor/*default-handler-names* true]
                            (is (= (expected-tables [:terse true]) (table-of terse-spec))))
                          (throw (ex-info "inside the shim" {})))))
  (is (= no-extensions (shim/current-impls)))
  (is (not (satisfies? interceptor/IntoInterceptor 'clojure.core/identity))
      "satisfies? doesn't see a stale extension")
  (when-let [e (is (thrown? IllegalArgumentException (table-of terse-spec)))]
    (is (string/starts-with? (ex-message (root-cause e))
                             "No implementation of method: :expand-verb-action of protocol:"))))
