; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.component.internal
  (:require [io.pedestal.component.protocols :as p]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.impl :as impl]))

(defn to-interceptor
  [interceptor-name component]
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
