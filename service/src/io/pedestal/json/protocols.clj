; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software

(ns io.pedestal.json.protocols
  {:added "0.8.2"}
  (:import (java.io OutputStream Reader)))

(defprotocol JSONProcessor

  "A wrapper around an underlying JSON parsing and encoding library."

  (read-json [this ^Reader reader options]
    "Reads JSON from the provided Reader. 
    
    The JSONProcesser must support the following options:
    
    | Option      | Type    | Description
    |---          |---      |---
    | :key-fn     | fn      | Converts string keys; defaults to `keyword`
    | :eof-error? | boolean | If true, then throw an exception if the reader is at EOF (default is false)
    | :eof-value  | any     | Value to return if reader is at EOF (defaults to :eof)
    | :value-fn   | any     | Passed the key and the value and returns an updated value
    
    Generally, any additional options are passed through to the underlying JSONProcessor
    implementation, but care should be given because different implementations will support different
    options.")

  (stream-json [this object ^OutputStream stream]
    "Writes the object, as JSON, to the given output stream.  

Returns the output stream, still open."))
