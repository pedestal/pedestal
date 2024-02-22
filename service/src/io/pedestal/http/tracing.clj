; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.tracing
  "HTTP request tracing based on Open Telemetry."
  {:added "0.7.0"}
  (:require [clojure.string :as string]
            [io.pedestal.tracing :as tel]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain])
  (:import (io.opentelemetry.context Context)
           (java.lang AutoCloseable)))

(defn- update-span-if-routed
  [context]
  (when-let [route (:route context)]
    (let [{:keys [path route-name]} route
          {::keys [span method-name]} context
          span-name (str method-name " " path)]
      (-> span
          (tel/rename-span span-name)
          (tel/add-attribute :http.route path)
          (tel/add-attribute :route.name route-name))))
  context)


(defn- trace-enter
  [context]
  (let [{:keys [request]} context
        {:keys [server-port request-method scheme]} request
        method-name        (-> request-method name string/upper-case)
        ;; Use a placeholder name; it will be overwritten and further details added
        ;; on leave/error if the request was routed.
        ;; TODO: make this more configurable
        span               (-> (tel/create-span "unrouted"
                                                {:http.request.method  method-name
                                                 ;; :scheme can be nil when using response-for, in tests
                                                 :scheme               (or scheme "unknown")
                                                 :server.port          server-port})
                               (tel/with-kind :server)
                               tel/start)
        otel-context       (-> (Context/current)
                               (.with span))
        otel-context-scope (.makeCurrent otel-context)
        prior-context      tel/*context*]
    (-> context
        (assoc ::span span
               ::method-name method-name
               ::otel-context otel-context
               ::prior-otel-context prior-context
               ::otel-context-scope otel-context-scope)
        ;; Bind *context* so that any async code can create spans within the new span
        ;; (Otel uses a thread local to track the current span, and that will not propagate
        ;; to other threads the way a dynamic var will).
        (chain/bind tel/*context* otel-context))))

(defn- trace-leave
  [context]
  (let [{:keys  [response]
         ::keys [span otel-context-scope prior-otel-context]} context
        {:keys [status]} response
        status-code (when status
                      (if (<= status 299) :ok :error))]
    (let [context' (-> context
                       (update-span-if-routed)
                       (dissoc ::span ::otel-context-scope ::otel-context ::prior-otel-context ::method-name)
                       (chain/unbind tel/*context*))]
      (-> span
          (cond->
            status (tel/add-attribute :http.response.status_code status)
            status-code (tel/set-status-code status-code))
          tel/end-span)
      (.close ^AutoCloseable otel-context-scope)
      ;; This assumes that a nil context represents an unbound value, so on nil, return it to the unbound state.
      (if prior-otel-context
        (chain/bind context' tel/*context* prior-otel-context)
        (chain/unbind context' tel/*context*)))))

(defn- trace-error
  [context error]
  (let [{:keys [::span]} context]
    (-> context
        (assoc ::span (-> (tel/record-exception span error)
                          (tel/set-status-code :error)))
        trace-leave
        ;; The exception is only reported here, not handled, so reattach for later interceptors to deal with.
        ;; Since this interceptor is usually first, it will fall back to stylobate logic to report the error
        ;; to the client, if not previously caught and handled.
        (assoc ::chain/error error))))

(defn request-tracing-interceptor
  "A tracing interceptor traces the execution of the request.  When the request is
  successfully routed, the trace will identify the HTTP route and route name.

  This interceptor should come first (or at least, early) in the incoming pipeline to ensure
  that all execution time is accounted for.  This is less important when the OpenTelemetry Java agent is
  in use, at that captures the overall request processing time, from start to finish, more accurately."
  []
  (interceptor
    {:name  ::tracing
     :enter trace-enter
     :leave trace-leave
     :error trace-error}))
