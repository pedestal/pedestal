; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.middleware
  (:require [io.pedestal.app.util.log :as log]
            [io.pedestal.service.interceptor :refer [defon-response]])
  (:import java.io.File))

(defon-response js-encoding
  [{:keys [headers body] :as response}]
  (if (and (= (get headers "Content-Type") "text/javascript")
           (= (type body) File))
    (assoc-in response [:headers "Content-Type"]
              "text/javascript; charset=utf-8")
    response))
