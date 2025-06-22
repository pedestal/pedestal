; Copyright 2024-2025 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.servlet
  "Generic Servlet adapter that closes over its implementation
  functions; this dynamically creates a Servlet instance that can be used with
  a servlet container such as Jetty."
  (:require [io.pedestal.internal :refer [deprecated]])
  (:import (io.pedestal.servlet FnServlet)
           (jakarta.servlet.http HttpServlet)))

(defn ^{:deprecated "0.8.0"} servlet
  "Returns an instance of jakarta.servlet.HttpServlet using provided
  functions for its implementation.

  Options:

  * :init      optional, initialization function taking two arguments:
               the Servlet and its ServletConfig
  * :service   required, handler function taking three arguments: the
               Servlet, ServletRequest, and ServletResponse
  * :destroy   optional, shutdown function taking one argument: the
               Servlet

  The :init, :service, and :destroy options correspond to the Servlet
  interface methods of the same names.

  The returned servlet instance also implements the ServletConfig interface.

  Note: this function returns an instance, not a class. If you need a
  class with a static name (for example, to deploy to a Servlet
  container) use the Java class io.pedestal.servlet.ClojureVarServlet."
  ^HttpServlet [& {:keys [init service destroy]}]
  {:pre [(fn? service)]}
  (deprecated `servlet :in "0.8.0")
  (FnServlet. init service destroy))
