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
    "Reads JSON from the provided Reader.  Options:
    
    :key-fn - converts a string key to a key
    :eof-error? - if true, then throw exception at unexpected EOF; otherwise return :eof
    :eof-value - value to return at EOF
    :value-fn - called on each map value, passed the key and value")

  (stream-json [this object ^OutputStream stream]
    "Writes the object, as JSON, to the given output stream.  

Returns the output stream, still open."))
