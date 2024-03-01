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
            [io.pedestal.tracing :as tracing]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain]))

(defn- update-span-if-routed
  [context]
  (when-let [route (:route context)]
    (let [{:keys [path route-name]} route
          {::keys [span]} context
          ;; Recompute the method name in case there was verb-smuggling
          method-name (-> context :request :request-method name string/upper-case)
          span-name   (str method-name " " path)]
      (-> span
          (tracing/rename-span span-name)
          (tracing/add-attribute :http.route path)
          (tracing/add-attribute :route.name route-name))))
  context)


(defn- trace-enter
  [context]
  (let [{:keys [request]} context
        {:keys [server-port request-method scheme]} request
        ;; Use a placeholder name; it will be overwritten and further details added
        ;; on leave/error if the request was routed.
        ;; TODO: make this more configurable
        span                 (-> (tracing/create-span "unrouted"
                                                      {:http.request.method (-> request-method name string/upper-case)
                                                       ;; :scheme can be nil when using response-for, in tests
                                                       :scheme              (or scheme "unknown")
                                                       :server.port         server-port})
                                 (tracing/with-kind :server)
                                 tracing/start)
        otel-context         (tracing/make-span-context span)
        otel-context-cleanup (tracing/make-context-current otel-context)
        prior-context        tracing/*context*]
    (-> context
        (assoc ::span span
               ::otel-context otel-context
               ::prior-otel-context prior-context
               ::otel-context-cleanup otel-context-cleanup)
        ;; Bind *context* so that any async code can create spans within the new span
        ;; (Otel uses a thread local to track the current span, and that will not propagate
        ;; to other threads the way a dynamic var will).
        (chain/bind tracing/*context* otel-context))))

(defn- trace-leave
  [context]
  (let [{:keys  [response]
         ::keys [span otel-context-cleanup prior-otel-context]} context
        {:keys [status]} response
        status-code (when status
                      (if (<= status 299) :ok :error))
        context'    (-> context
                        (update-span-if-routed)
                        (dissoc ::span ::otel-context-cleanup ::otel-context ::prior-otel-context)
                        (chain/unbind tracing/*context*))]
    (-> span
        (cond->
          status (tracing/add-attribute :http.response.status_code status)
          status-code (tracing/set-status-code status-code))
        tracing/end-span)
    (otel-context-cleanup)
    ;; This assumes that a nil context represents an unbound value, so on nil, return it to the unbound state.
    (if prior-otel-context
      (chain/bind context' tracing/*context* prior-otel-context)
      (chain/unbind context' tracing/*context*))))

(defn- trace-error
  [context error]
  ;; If an exception is trown inside trace-enter, trace-error will be called with the
  ;; unmodified context, which does not have a context.
  (let [{:keys [::span]} context]
    (if-not span
      (assoc context ::chain/error error)
      (-> context
          (assoc ::span (-> (tracing/record-exception span error)
                            (tracing/set-status-code :error)))
          trace-leave
          ;; The exception is only reported here, not handled, so reattach for later interceptors to deal with.
          ;; Since this interceptor is usually first, it will fall back to stylobate logic to report the error
          ;; to the client, if not previously caught and handled.
          (assoc ::chain/error error)))))

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
