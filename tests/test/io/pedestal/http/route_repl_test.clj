; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route-repl-test
  "Tests for dev-mode related macros in io.pedestal.http.route."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.http.route.internal :as internal]
            [io.pedestal.test-common :as tc]
            [io.pedestal.http.route :as route :as route]
            [io.pedestal.http.route.internal :as i]
            [io.pedestal.environment :refer [dev-mode?]]))

(use-fixtures :once tc/no-ansi-fixture
              (fn [f]
                (with-redefs [dev-mode? true]
                  (f))))

(deftest dev-mode-enabled
  ;; Sanity check that dev-mode is enabled when running tests or a REPL.
  (is (true? dev-mode?)))

(defn hello-handler
  [_request]
  {:status  200
   :headers {}
   :body    "HELLO"})

(defn bye-handler
  [_]
  {:status  200
   :headers {}
   :body    "LATER"})

(def sample-routes
  #{["/hello" :get [#'hello-handler] :route-name ::hello]})

(defn- exercise
  [expanded-routes]
  ;; These two keys do not compare well.
  (mapv #(dissoc % :path-re :interceptors) expanded-routes))

(defmacro routes-from
  [expr]
  (internal/routes-from-expr expr &env `route/expand-routes))

(deftest symbol-points-to-var
  (let [f          (routes-from sample-routes)
        alt-routes #{["/bye" :get #'bye-handler :route-name ::bye]}]
    (is (fn? f))

    (is (= (exercise (route/expand-routes sample-routes))
           (exercise (f))))

    ;; Test that the function de-refs the Var, rather than capturing the value
    ;; at macro expansion time.
    (with-redefs [sample-routes alt-routes]
      (is (= (exercise (route/expand-routes alt-routes))
             (exercise (f)))))))

(deftest local-symbol-is-simply-wrapped-as-function
  (let [local-routes #{["/hi" :get #'hello-handler :route-name ::hi]}
        f            (routes-from local-routes)]
    (is (= (exercise (route/expand-routes local-routes))
           (exercise (f))))))

(deftest production-mode
  (let [output (with-redefs [dev-mode? false]
                 (eval `(route/routes-from sample-routes)))]
    (is (identical? sample-routes output))))

(deftest outputs-extra-columns-when-different
  (let [routes (concat
                 (route/expand-routes #{{:app-name :main
                                         :host     "main"
                                         :scheme   :https
                                         :port     8080}
                                        ["/" :get identity :route-name :root-page]})
                 (route/expand-routes #{{:app-name :admin
                                         :host     "internal"
                                         :scheme   :http
                                         :port     9090}
                                        ["/status" :get identity :route-name :status]
                                        ["/reset" :post identity :route-name :reset]}))

        out-str (with-out-str
                  (println)
                  (i/print-routing-table routes))]
    ;; Note: sorted by path
    (is (= "
┏━━━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━┳━━━━━━┳━━━━━━━━┳━━━━━━━━━┳━━━━━━━━━━━━┓
┃ App name ┃ Scheme ┃     Host ┃ Port ┃ Method ┃    Path ┃ Name       ┃
┣━━━━━━━━━━╋━━━━━━━━╋━━━━━━━━━━╋━━━━━━╋━━━━━━━━╋━━━━━━━━━╋━━━━━━━━━━━━┫
┃    :main ┃ :https ┃     main ┃ 8080 ┃   :get ┃       / ┃ :root-page ┃
┃   :admin ┃  :http ┃ internal ┃ 9090 ┃  :post ┃  /reset ┃ :reset     ┃
┃   :admin ┃  :http ┃ internal ┃ 9090 ┃   :get ┃ /status ┃ :status    ┃
┗━━━━━━━━━━┻━━━━━━━━┻━━━━━━━━━━┻━━━━━━┻━━━━━━━━┻━━━━━━━━━┻━━━━━━━━━━━━┛
" out-str))))

(deftest static-routes-printed-once
  (let [expanded-routes (route/expand-routes sample-routes)
        out-str         (with-out-str
                          (println)
                          (is (= expanded-routes
                                 (internal/wrap-routing-table expanded-routes))))]
    (is (= "
Routing table:
┏━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Method ┃   Path ┃ Name                                    ┃
┣━━━━━━━━╋━━━━━━━━╋━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃   :get ┃ /hello ┃ :io.pedestal.http.route-repl-test/hello ┃
┗━━━━━━━━┻━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
" out-str))))

(deftest dynamic-routes-printed-at-startup-and-when-changed
  (let [expanded-routes (route/expand-routes sample-routes)
        *routes         (atom expanded-routes)
        *wrapped        (atom nil)
        f               (fn []
                          @*routes)
        out-str         (with-out-str
                          (println)
                          (reset! *wrapped
                                  (internal/wrap-routing-table f)))
        wrapped         @*wrapped
        _               (is (= "
Routing table:
┏━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Method ┃   Path ┃ Name                                    ┃
┣━━━━━━━━╋━━━━━━━━╋━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃   :get ┃ /hello ┃ :io.pedestal.http.route-repl-test/hello ┃
┗━━━━━━━━┻━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
" out-str))
        out-str         (with-out-str
                          (is (= expanded-routes (wrapped))))
        ;; No change, no output
        _               (is (= out-str ""))
        new-routes      (route/expand-routes #{["/login" :post hello-handler :route-name ::login]})
        _               (reset! *routes new-routes)
        out-str         (with-out-str
                          (println)
                          (is (= new-routes (wrapped))))]
    (is (= out-str "
Routing table:
┏━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Method ┃   Path ┃ Name                                    ┃
┣━━━━━━━━╋━━━━━━━━╋━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃  :post ┃ /login ┃ :io.pedestal.http.route-repl-test/login ┃
┗━━━━━━━━┻━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
"))))


