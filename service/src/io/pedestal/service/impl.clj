; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.service.impl
  "Internal namespace, subject to change at any time."
  {:since "0.8.0"}
  (:require [clj-commons.ansi :as ansi]
            [clj-commons.format.exceptions :as exceptions]
            [clojure.string :as string])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

(defn byte-buffer->input-stream
  ^InputStream [^ByteBuffer bb]
  (with-open [bos     (ByteArrayOutputStream. (.capacity bb))
              channel (Channels/newChannel bos)]
    (.write channel bb)
    (-> bos .toByteArray ByteArrayInputStream.)))

(defn byte-channel->input-stream
  ^InputStream [^ReadableByteChannel channel]
  (Channels/newInputStream channel))

(defn function->input-stream
  ^InputStream [f]
  (with-open [bos (ByteArrayOutputStream. 1000)]
    (f bos)
    (-> bos .toByteArray ByteArrayInputStream.)))

(defn parse-url
  [url]
  (let [[_ scheme raw-host path query-string] (re-matches #"(?:([^:]+)://)?([^/]+)?(?:/([^\?]*)(?:\?(.*))?)?" url)
        [host port] (when raw-host (string/split raw-host #":"))
        uri (str "/" path)]
    ;; Violates the Ring spec, :protocol and :remote-addr are missing
    ;; (:request-method is added elsewhere)
    {:scheme       (if scheme
                     (keyword scheme)
                     :http)
     :server-name  (or host "localhost")
     :server-port  (if port
                     (Integer/parseInt port)
                     -1)
     :uri          uri
     :path-info    uri                                      ; specific to Pedestal?
     :query-string query-string}))

(defn  format-exception
  "Private function - do not use."
  [exception]
  (binding [ansi/*color-enabled* false]
    (exceptions/format-exception exception)))
