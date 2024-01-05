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
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http.route :as route :refer [routes-from]]
            [io.pedestal.environment :refer [dev-mode?]]))

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

(defn- simplify
  [expanded-routes]
  ;; These two keys do not compare well.
  (mapv #(dissoc % :path-re :interceptors) expanded-routes))

(deftest symbol-points-to-var
  (let [f          (routes-from sample-routes)
        z          ::foo
        alt-routes #{["/bye" :get #'bye-handler :route-name ::bye]}]
    (is (fn? f))

    (let [out-str (with-out-str
                    (is (= (simplify (route/expand-routes sample-routes))
                           (simplify (f)))))]
      (is (= "Routing table:
┏━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Method ┃ Path   ┃ Name                                    ┃
┣━━━━━━━━╋━━━━━━━━╋━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃ :get   ┃ /hello ┃ :io.pedestal.http.route-repl-test/hello ┃
┗━━━━━━━━┻━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
" out-str))
      )

    (let [out-str (with-out-str (f))]
      ;; Unchanged routing table, no output.
      (is (= "" out-str)))

    ;; Test that the function de-refs the Var, rather than capturing the value
    ;; at macro expansion time.
    (with-redefs [sample-routes alt-routes]
      (let [out-str (with-out-str
                      (is (= (simplify (route/expand-routes alt-routes))
                             (simplify (f)))))]
        (is (= "Routing table:
┏━━━━━━━━┳━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Method ┃ Path ┃ Name                                  ┃
┣━━━━━━━━╋━━━━━━╋━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃ :get   ┃ /bye ┃ :io.pedestal.http.route-repl-test/bye ┃
┗━━━━━━━━┻━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
" out-str))
        ))))

(deftest local-symbol-is-simply-wrapped-as-function
  (let [local-routes #{["/hi" :get #'hello-handler :route-name ::hi]}
        f            (routes-from local-routes)]
    (let [out-str (with-out-str
                    (is (= (simplify (route/expand-routes local-routes))
                           (simplify (f)))))]
      (is (= "Routing table:
┏━━━━━━━━┳━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Method ┃ Path ┃ Name                                 ┃
┣━━━━━━━━╋━━━━━━╋━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃ :get   ┃ /hi  ┃ :io.pedestal.http.route-repl-test/hi ┃
┗━━━━━━━━┻━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
" out-str)))))

(deftest production-mode
  (let [output (with-redefs [dev-mode? false]
                 (eval `(routes-from sample-routes)))]
    (is (identical? sample-routes output))))
