/* Copyright 2023-2025 Nubank NA
 * Copyright 2013 Relevance, Inc.
 * Copyright 2014-2019 Cognitect, Inc.

 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
 * which can be found in the file epl-v10.html at the root of this distribution.
 *
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 *
 * You must not remove this notice, or any other, from this software.
 */
package io.pedestal.servlet;

import clojure.lang.IFn;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Java Servlets implementation that dispatches via Clojure Vars (that map to IFn's).
 *
 * <p>ClojureVarServlet is a completely generic implementation of the
 * jakarta.servlet.Servlet and ServletConfig interfaces. Its behavior is
 * specified via Servlet initialization parameters.
 *
 * <p>The three parameters are 'init', 'service', and 'destroy'. Only
 * 'service' is required; the others are optional. The value of each
 * parameter must be the namespace-qualified name of a Clojure Var.
 * The namespace will be loaded as with clojure.core/require, then the
 * Var will be resolved.
 *
 * <p>Each Var must be a Clojure function taking the
 * same arguments as the corresponding method in the Servlet interface:
 *
 * <pre>
 * init(Servlet, ServletConfig)
 * service(Servlet, ServletRequest, ServletResponse)
 * destroy(Servlet)
 * </pre>
 *
 * <p>The return value from any of these functions is ignored.
 *
 * <p>The root bindings of the Vars may be redefined during local development
 * and the servlet will use the latest bindings (that is, it invokes the Var,
 * it doesn't extract the function from the Var).
 */
public class ClojureVarServlet extends GenericServlet {
    private IFn serviceFn;
    private IFn destroyFn;

    /** Does nothing. Initialization happens in the init method. */
    public ClojureVarServlet() {}

    /** Initializes the Servlet with Clojure functions resolved from
     * Vars named in the Servlet initialization parameters. If an init
     * function was provided, invokes it. */
    @Override
    public void init() throws ServletException {
        ServletConfig config = this.getServletConfig();
        IFn initFn = Util.getVar(config, "init");
        serviceFn = Util.getVar(config, "service");
        destroyFn = Util.getVar(config, "destroy");

        if (serviceFn == null) {
            throw new ServletException("Missing required parameter 'service'");
        }

        if (initFn != null) { initFn.invoke(this, config); }
    }

    /** Invokes the service function with which this Servlet was
     * initialized. */
    @Override
    public void service(ServletRequest request, ServletResponse response) {
        serviceFn.invoke(this, request, response);
    }

    /** If a destroy function was provided when this Servlet was
     * initialized, invoke it. */
    @Override
    public void destroy() {
        if (destroyFn != null) {
            destroyFn.invoke(this);
        }
    }

}

