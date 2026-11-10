; Copyright 2024-2025 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.terminator-test
  "Tests for chain termination logic."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.http.response :as response :refer [respond-with]]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as i :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :refer [go >! chan]]
            [io.pedestal.test-common :refer [<!!?]])
  (:import (clojure.lang ExceptionInfo)))

(defn- execute [& interceptors]
  (let [context (response/terminate-when-response {})]
    (:response
      (chain/execute context (mapv interceptor interceptors)))))

(def tail
  {:name  :tail
   :enter #(respond-with % 401 {"Content-Type" "text/plain"} "TAIL")})

(deftest fall-through-to-last
  (is (match? {:status 401
               :headers {"Content-Type" "text/plain"}
               :body "TAIL"}
              (execute {:name  :do-nothing
                        :enter identity}
                       tail))))

(deftest valid-response-map
  (is (= {:status 303}
         (execute {:name  :valid
                   :enter #(respond-with % 303)}
                  tail))))

(deftest not-a-map
  ;; It tooks some juggling in interceptor.chain to ensure that an exception
  ;; thrown from the termination check fn was associated with the interceptor
  ;; as shown here.
  (when-let [e (is (thrown-with-msg? ExceptionInfo #"Interceptor attached a :response that is not a map"
                                     (execute {:name  :not-a-map
                                               :enter #(assoc % :response 401)})))]
    (is (match?
          {:exception-type :clojure.lang.ExceptionInfo
           :interceptor    :not-a-map
           :response       401
           :stage          :enter}
          (ex-data e)))))

(deftest invalid-status-key
  ;; It tooks some juggling in interceptor.chain to ensure that an exception
  ;; thrown from the termination check fn was associated with the interceptor
  ;; as shown here.
  (when-let [e (is (thrown-with-msg? ExceptionInfo #"Interceptor attached a :response that is not a map"
                                     (execute {:name  :invalid-status
                                               :enter #(assoc % :response :not-found)})))]
    (is (match?
          {:interceptor    :invalid-status
           :response       :not-found}
          (ex-data e)))))

(deftest not-a-map-message
  (is (thrown-with-msg? ExceptionInfo
                        #"\Qin Interceptor :not-a-map - Interceptor attached a :response that is not a map: 401 (a java.lang.Long). A response must be a map with a positive integer :status.\E"
                        (execute {:name  :not-a-map
                                  :enter #(assoc % :response 401)})))
  (is (thrown-with-msg? ExceptionInfo
                        #"\Qthat is not a map: a java.lang.String.\E"
                        (execute {:name  :string-response
                                  :enter #(assoc % :response "OK")}))
      "describes other values by their type only"))

(deftest map-without-status
  (when-let [e (is (thrown-with-msg? ExceptionInfo
                                     #"\QInterceptor attached a :response map without a :status: a map with keys [:body :headers]. A response must\E"
                                     (execute {:name  :no-status
                                               :enter #(assoc % :response {:body "OK" :headers {}})})))]
    (is (match? {:interceptor :no-status
                 :response    {:body "OK"}}
                (ex-data e))))
  (is (thrown-with-msg? ExceptionInfo
                        #"\Qwithout a :status: an empty map.\E"
                        (execute {:name  :empty-map
                                  :enter #(assoc % :response {})}))))

(deftest status-is-not-a-positive-integer
  (doseq [[status expected] [["200" "\"200\""]
                             [0 "0"]
                             [-1 "-1"]
                             [nil "nil"]]]
    (testing (pr-str status)
      (is (thrown-with-msg? ExceptionInfo
                            (re-pattern (str "\\QInterceptor attached a :response map whose :status is not a positive integer: "
                                             expected
                                             ". A response must\\E"))
                            (execute {:name  :bad-status
                                      :enter #(assoc % :response {:status status})}))))))

(defn- add-user
  "Meant to change the request, but used where an interceptor is expected: it is called as a handler."
  [request]
  (assoc request ::user "someone"))

(defn- list-orders
  [_request]
  {:status 200 :body "orders"})

(defn- execute-route
  []
  (let [routes (route/expand-routes #{["/orders" :get [add-user list-orders] :route-name ::orders]})]
    (chain/execute (response/terminate-when-response
                     {:request {:request-method :get
                                :path-info      "/orders"
                                :uri            "/orders"
                                :scheme         "http"
                                :server-name    "localhost"
                                :server-port    8080}})
                   [(route/router routes)])))

(deftest function-in-a-route-names-the-step-and-the-route
  (doseq [[default-handler-names? step] [[true ":io.pedestal.interceptor.terminator-test/add-user"]
                                         [false "#Interceptor{} (function io.pedestal.interceptor.terminator-test/add-user)"]]]
    (testing (str "with *default-handler-names* " default-handler-names?)
      (binding [i/*default-handler-names* default-handler-names?]
        (when-let [e (is (thrown? ExceptionInfo (execute-route)))]
          (let [message (ex-message e)]
            (is (string/includes? message (str " in Interceptor " step " - Interceptor attached a :response map without a :status")))
            (is (string/includes? message "(it looks like the request map), in route :io.pedestal.interceptor.terminator-test/orders."))
            (is (string/includes? message "A function in an interceptor list is called as a handler")))
          (is (match? {:stage      :enter
                       :route-name ::orders
                       :response   {:request-method :get
                                    ::user          "someone"}}
                      (ex-data e))))))))

(deftest a-replaced-terminator-is-used
  ;; common-io replaces this private Var to ignore an invalid :response (as Pedestal 0.6 did), until
  ;; the services that rely on that are fixed; the replacement must keep taking effect.
  (with-redefs [response/terminate-when-response* (fn [{:keys [response]}]
                                                    (and (map? response)
                                                         (pos-int? (:status response))))]
    (is (match? {:status 401
                 :body   "TAIL"}
                (execute {:name  :not-a-map
                          :enter #(assoc % :response :not-a-response)}
                         tail)))))

(deftest async-termination
  (let [ch (chan)]
    (execute
      {:name  :capture
       :leave (fn [context]
                (go
                  (>! ch (:response context))
                  context))}
      {:name  :valid-async
       :enter (fn [context]
                (go
                  (respond-with context 303 "ASYNC")))}
      tail)
    (is (match?
          {:status 303
           :body   "ASYNC"}
          (<!!? ch)))))

(deftest async-termination-invalid-response
  (let [response-ch (chan 1)
        error-ch    (chan 1)]
    (execute
      {:name  :capture
       :leave (fn [context]
                (go
                  (>! response-ch (:response context))
                  context))}
      {:name  :capture-error
       :error (fn [context err]
                (go
                  (>! error-ch err)
                  (respond-with context 599)))}
      {:name  :invalid-async
       :enter (fn [context]
                (go
                  (assoc context :response :invalid)))}
      tail)

    (is (match? {:status 599}
                (<!!? response-ch)))

    (let [e (<!!? error-ch)]
      (is (match? {:interceptor :invalid-async
                   :response    :invalid}
                  (ex-data e))))))
