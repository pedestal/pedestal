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

(defn- value-str
  [v]
  (cond
    (string? v)
    v

    (keyword? v)
    (-> v str (subs 1))

    :else
    (str v)))

(defn- trace-enter [context]
  (if-let [route (:route context)]
    (let [{:keys [route-name path method scheme port]} route
          method-name (-> method name string/upper-case)
          span-name   (str method-name " " path)
          span        (-> (tel/create-span span-name
                                           {:http.route          path
                                            :http.request.method method-name
                                            :schema              scheme
                                            :server.port         port
                                            :route.name          (value-str route-name)})
                          tel/start)
          otel-context (-> (Context/current)
                           (.with span))
          otel-context-scope (.makeCurrent otel-context)
          prior-context (get-in context [:bindings #'tel/*context*])]
      (-> context
          (assoc ::span span
                 ::otel-context otel-context
                 ::prior-otel-context prior-context
                 ::otel-context-scope otel-context-scope)
          ;; Bind *context* so that any async code can create spans within this span
          (chain/bind tel/*context* otel-context-scope)))
    ;; No route, no span
    context))

(defn- trace-leave [context]
  (if-let [span (::span context)]
    (let [{:keys  [response]
           ::keys [otel-context-scope prior-otel-context]} context
          {:keys [status]} response]
      (-> span
          (cond-> status (tel/add-attribute :http.response.status_code status))
          tel/end-span)
      (.close ^AutoCloseable otel-context-scope)
      (let [context' (-> context
                         (dissoc ::span ::otel-context-scope ::otel-context)
                         (chain/unbind tel/*context*))]
        ;; Restore the prior context if not nil, otherwise unbind it.
        (if prior-otel-context
          (chain/bind context' tel/*context* prior-otel-context)
          (chain/unbind context' tel/*context*))))
    context))

(defn- trace-error [context error]
  (let [{:keys [::span]} context]
    (-> context
        (cond-> span (assoc ::span (.recordException span error)))
        trace-leave
        ;; The exception is only reported here, not handled so reattach for later interceptors to deal with.
        (assoc ::chain/error error))))

(defn request-tracing-interceptor
  "A tracing interceptor comes after the routing interceptor and uses the routing data
  in the context (if routing was successful) to time the execution of the route (which is to say,
  all interceptors in the route selected by the router)."
  []
  (interceptor
    {:name  ::tracing
     :enter trace-enter
     :leave trace-leave
     :error trace-error}))


;; TODO: Propagate the ::otel-context when going async
;; Perhaps a fn and/or macro to setup ::otel-context as current() and close it after.
