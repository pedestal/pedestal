; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software

(ns io.pedestal.json
  "Abstraction around JSON parsing and printing."
  {:added "0.8.2"}
  (:require [io.pedestal.internal :as i]
            [io.pedestal.json.protocols :as p])
  (:import (java.io Reader)))

(def ^:dynamic *json-processor*
  "The default JSON processor, used when reading or outputting JSON."
  (let [v (i/read-config "io.pedestal.json-processor"
                         "PEDESTAL_JSON_PROCESSOR"
                         :default-value "io.pedestal.json.charred/processor")]
    (try
      (v)
      (catch Exception e
        (throw (RuntimeException. (format "Error invoking function %s (to create JSON Processor" (str v))
                                  e))))))

(defn read-json
  "Read JSON from a java.io.Reader, with options (as defined by the protocol)."
  ([^Reader reader options]
   (read-json *json-processor* reader options))
  ([processor ^Reader reader options]
   (p/read-json processor reader options)))
