; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.protocols
  {:added "0.8.0"})

(defprotocol ResponseBufferSize

  "Returns the buffer size of the response object; this protocol is adapted to the HTTPServletResponse object which
  is not a dependency available to the pedestal.service module."

  (response-buffer-size [this]
    "Returns the buffer size, in bytes, available to this response, or nil if it can not be determined."))

;; Cover the case where the servlet response object is not available.

(extend-protocol ResponseBufferSize

  nil

  (response-buffer-size [_] nil))


(defprotocol PedestalConnector
  "A connector to an HTTP network adaptor, created from a service map.

  The connector, once started, will handle incoming requests and outgoing responses, building
  on the interceptors and initial context provided in the service map."

  (start-connector! [this]
    "Starts (or restarts) the connector.  If the :join? key of the service map is true, this method will block
    until the connector is stopped.

    Invoking start-connector when the connector is already started results in connector-defined behavior.

    Returns the connector.")

  (stop-connector! [this]
    "Stops the connector, if started. Does nothing if not started.  This will unblock a thread
    that is blocked in start-connector.

    Returns the connector.")

  (test-request [this ring-request]
    "Test a Ring request map, returning a Ring response map.

    This method will operate regardless of whether the connector is started or stopped.

    The :body of the request must be nil, a String, a File, or an InputStream; likewise,
    in the returned response the :body will be nil or an InputStream."))

