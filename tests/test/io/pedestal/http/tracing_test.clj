; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.tracing-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [mockfn.macros :refer [verifying]]
            [mockfn.matchers :refer [exactly]]
            [io.pedestal.tracing :as t]
            [io.pedestal.http.tracing :refer [request-tracing-interceptor]]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as chain]))

;; TODO:
;; Handles error in :error
;; Reports routing properly w/ smuggled verb

(defn- execute
  [context & interceptors]
  (chain/execute context (mapv i/interceptor interceptors)))

(def request-tracing (request-tracing-interceptor))

(def not-found
  {:enter (fn [context]
            (assoc context :response {:status 404}))})

(def routed
  {:enter (fn [context]
            (assoc context
                   :route {:path       "/status"
                           :route-name ::status}
                   :response {:status 200}))})

(def base-request {:server-port    9999
                   :request-method :get
                   :scheme         nil})
(def base-context {:request base-request})

(def span ::mock-span)
(def span-builder ::mock-span-builder)
(def once (exactly 1))

;; Couldn't get verifying to handle checking that cleanup was invoked just once,
;; so cobbled this together.
(def *cleanup-invoked (atom 0))

(defn context-cleanup []
  (swap! *cleanup-invoked inc)
  nil)

(use-fixtures :each (fn [f]
                      (reset! *cleanup-invoked 0)
                      (f)))

(deftest unrouted-request
  (verifying [(t/create-span "unrouted" {:http.request.method "GET"
                                         :scheme              "unknown"
                                         :server.port         9999}) span-builder once
              (t/with-kind span-builder :server) span-builder once
              (t/start span-builder) span once
              (t/make-span-context span) ::context once
              (t/make-context-current ::context) context-cleanup once
              (t/add-attribute span :http.response.status_code 404) span once
              (t/set-status-code span :error) span once
              (t/end-span span) nil once]
    (execute base-context
             request-tracing
             not-found)

    (is (= 1 @*cleanup-invoked))))

(deftest routed-request
  (verifying [(t/create-span "unrouted" {:http.request.method "GET"
                                         :scheme              "unknown"
                                         :server.port         9999}) span-builder once
              (t/with-kind span-builder :server) span-builder once
              (t/start span-builder) span once
              (t/make-span-context span) ::context once
              (t/make-context-current ::context) context-cleanup once
              (t/rename-span span "GET /status") span once
              (t/add-attribute span :http.route "/status") span once
              (t/add-attribute span :route.name ::status) span once
              (t/add-attribute span :http.response.status_code 200) span once
              (t/set-status-code span :ok) span once
              (t/end-span span) nil once]
    (execute base-context
             request-tracing
             routed)

    (is (= 1 @*cleanup-invoked))))






