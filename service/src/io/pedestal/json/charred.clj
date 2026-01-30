; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software

(ns io.pedestal.json.charred
  "Implementation of the JSONProcessor protocol for the charred library."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [io.pedestal.json.protocols :as p])
  (:import (charred JSONWriter)
           (java.io OutputStream Writer)))

(defn- write-json-noclose
  ;; Calling charred/write-json closes the writer, which doesn't work with Pedestal and Servlet API.
  ;; Instead, we create a writer via json/json-writer-fn and write the object to it.
  [obj ^Writer stream-writer writer-fn]
  (let [^JSONWriter json-writer (writer-fn stream-writer)]
    (.writeObject json-writer obj)
    ;; The feels dirty, but some JSON could still be in the JSONWriter's internal writer
    ;; that wraps stream-writer, and that needs to be flushed but not closed, and
    ;; JSONWriter implements a close() but not a flush().
    (.flush (.-w json-writer))))

(defn stream-json
  "Writes the object as JSON to the stream and returns the stream.  Some gymnastics occur to ensure
  that the stream is not closed."
  [object ^OutputStream stream writer-fn]
  (with-open [writer (io/writer stream)]
    (write-json-noclose object writer writer-fn)
    stream))

(defn processor
  "Returns a JSONProcessor that functions using the charred JSON library."
  []
  (let [writer-fn (charred/json-writer-fn nil)]
    (reify p/JSONProcessor
      (read-json [_ reader options]
        (charred/read-json reader options))

      (stream-json [_ object stream]
        (stream-json object stream writer-fn)))))
