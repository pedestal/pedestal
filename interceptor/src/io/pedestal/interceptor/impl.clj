; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.interceptor.impl
  "Internal namespace subject to change at any time."
  {:added "0.8.0"}
  (:require clojure.core.async.impl.protocols
            [clojure.core.async :refer [go <!]])
  (:import (clojure.core.async.impl.protocols ReadPort)))

(defn channel?
  [c]
  (instance? ReadPort c))

(defn wrap-handler
  [handler-fn]
  (fn [context]
    (let [response (-> context :request handler-fn)]
      (if (channel? response)
        (go (assoc context :response (<! response)))
        (assoc context :response response)))))
