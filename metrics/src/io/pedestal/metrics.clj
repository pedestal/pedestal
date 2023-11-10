; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics
  {:since "0.7.0"}
  "Metrics based upon the Micrometer library."
  (:require [io.pedestal.metrics.internal :as i])
  (:import (io.micrometer.core.instrument Metrics MeterRegistry)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)))

(def ^SimpleMeterRegistry default-registry (SimpleMeterRegistry.))

(Metrics/addRegistry default-registry)

(defn add-registry
  "Adds a new registry to the global registry."
  [^MeterRegistry registry]
  (Metrics/addRegistry registry))

(defn set-registry
  "Replaces the default SimpleMeterRegistry with the provided registry."
  [^MeterRegistry new-registry]
  (Metrics/addRegistry new-registry)
  (Metrics/removeRegistry default-registry))



