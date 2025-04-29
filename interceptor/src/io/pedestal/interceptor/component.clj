; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.component
  "Streamline the use of Components as interceptors.

  The [[definterceptor]] macro is used to implement a record that extends
  [[IntoInterceptor]] and so can be used an interceptor."
  {:added "0.8.0"}
  (:require [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.impl :as impl]
            [io.pedestal.interceptor.protocols :as p]))

(defn component->interceptor
  "Converts a component into an interceptor.   The component must implement
  at least one of the [[Handler]], [[OnEnter]], [[OnLeave]], or [[OnError]] protocols.

  A component can implement the [[IntoInterceptor]] protocol and base the
  `-interceptor` method on this function.

  Returns an interceptor record."
  [interceptor-name component]
  (assert (keyword? interceptor-name))
  (let [enter-fn        (or
                          (when (satisfies? p/OnEnter component)
                            (fn [context]
                              (p/enter component context)))
                          (when (satisfies? p/Handler component)
                            (impl/wrap-handler
                              (fn [request]
                                (p/handle component request)))))
        leave-fn        (when (satisfies? p/OnLeave component)
                          (fn [context]
                            (p/leave component context)))
        error-fn        (when (satisfies? p/OnError component)
                          (fn [context exception]
                            (p/error component context exception)))
        interceptor-map (cond-> {:name interceptor-name}
                          enter-fn (assoc :enter enter-fn)
                          leave-fn (assoc :leave leave-fn)
                          error-fn (assoc :error error-fn))]
    (i/interceptor interceptor-map)))

(defmacro definterceptor
  "Defines a interceptor component, as a Clojure record. The interceptor's name will be
  the record's name as a namespace qualified keyword.  The interceptor must extend
  at least one of the [[Handler]], [[OnEnter]], [[OnLeave]], or [[OnError]] protocols.

  The class name will match the record-name (which is typically kebab-cased).
  That is `(definterceptor foo-bar ...)` will generate the same class name as
  `(defrecord foo-bar ...)` even though this is not the normal Pascal Case naming
  convention for most records.

  The record implements the [[IntoInterceptor]] protocol; see [[component->interceptor]].

  The normal `map->record` and `->record` construction functions are generated.
  "
  [record-name fields & opts+specs]
  (assert (simple-symbol? record-name))
  (let [interceptor-name (keyword (-> *ns* ns-name str) (name record-name))]
    `(defrecord ~record-name ~fields

       ~@opts+specs

       i/IntoInterceptor

       (~'-interceptor [~'this]
         (component->interceptor ~interceptor-name ~'this)))))
