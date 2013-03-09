;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.service.http.servlet
  "Generic Servlet adapter that closes over its implementation
  functions."
  (:import (javax.servlet Servlet ServletConfig)))

;; Do not construct instances directly; use the 'servlet' function.
(deftype FnServlet [init-fn service-fn destroy-fn
                    ^:unsynchronized-mutable config]
  Servlet
  (init [this servlet-config]
    (set! config servlet-config)
    (init-fn this servlet-config))
  (destroy [this]
    (destroy-fn this))
  (service [this servlet-request servlet-response]
    (service-fn this servlet-request servlet-response))
  (getServletConfig [this]
    config)
  (getServletInfo [this]
    (str "FnServlet dispatching to " service-fn))
  ServletConfig
  (getInitParameter [this name]
    (when-not (nil? config)
      (.getInitParameter ^ServletConfig config name)))
  (getInitParameterNames [this]
    (when-not (nil? config)
      (.getInitParameterNames ^ServletConfig config)))
  (getServletContext [this]
    (when-not (nil? config)
      (.getServletContext ^ServletConfig config)))
  (getServletName [this]
    (when-not (nil? config)
      (.getServletName ^ServletConfig config))))

(defn servlet
  "Returns an instance of javax.servlet.Servlet using provided
  functions for its implementation. Arguments are key-value pairs of:

    :init      optional, initialization function taking two arguments:
               the Servlet and its ServletConfig

    :service   required, handler function taking three arguments: the
               Servlet, ServletRequest, and ServletResponse

    :destroy   optional, shutdown function taking one argument: the
               Servlet

  The :init, :service, and :destroy options correspond to the Servlet
  interface methods of the same names.

  Note: this function returns an instance, not a class. If you need a
  class with a static name (for example, to deploy to a Servlet
  container) use the Java class pedestal.servlet.ClojureVarServlet."
  [& {:keys [init service destroy]}]
  {:pre [(fn? service)]}
  (FnServlet. (or init (fn [_ _]))
              service
              (or destroy (fn [_]))
              nil))
