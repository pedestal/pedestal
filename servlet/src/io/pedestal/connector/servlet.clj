; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.connector.servlet
  "Utilities for creating a ConnectorBridge for use with the ConnectorServlet; this is used when deploying a Pedestal
  application as a WAR file into a servlet container (such as Jetty)."
  {:added "0.8.0"}
  (:require [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor])
  (:import (io.pedestal.servlet ConnectorBridge)
           (jakarta.servlet Servlet)))

(defn create-bridge
  "Creates the ConnectorBridge object used by the ConnectorServlet.

  Only the :interceptors and :initial-context keys of the connector map are used.

  Options are as defined by [[http-interceptor-service-fn]] (:exception-analyzer is the only
  current option)."
  ([^Servlet servlet connector-map]
   (create-bridge servlet connector-map nil))
  ([^Servlet servlet connector-map options]
   (let [{:keys [initial-context interceptors]} connector-map
         service-fn (servlet-interceptor/http-interceptor-service-fn interceptors initial-context options)]
     (reify ConnectorBridge
       (service [_ servlet-request servlet-response]
         (service-fn servlet servlet-request servlet-response))

       (destroy [_] nil)))))

