// Copyright 2025 Nubank NA
//
// The use and distribution terms for this software are covered by the
// Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
// which can be found in the file epl-v10.html at the root of this distribution.
//
// By using this software in any fashion, you are agreeing to be bound by
// the terms of this license.
//
// You must not remove this notice, or any other, from this software.

package io.pedestal.servlet;

import clojure.lang.IFn;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * An implementation of HttpServlet that delegates to Clojure functions.
 *
 * initFn (optional): passed servlet and ServletConfig instance
 * serviceFn (required): passed servlet, HttpServletRequest, HttpServletResponse
 * destroyFn (optional): passed servlet
 *
 * @since 0.8.0
 */
public class FnServlet extends HttpServlet
{
    private final IFn initFn, serviceFn, destroyFn;

    public FnServlet(IFn initFn, IFn serviceFn, IFn destroyFn) {
        this.initFn = initFn;
        this.serviceFn = serviceFn;
        this.destroyFn = destroyFn;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (initFn != null) {
            initFn.invoke(this, config);
        }
    }

    @Override
    public String getServletInfo() {
        return "FnServlet dispatching to " + serviceFn.toString();
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        serviceFn.invoke(this, req, resp);
    }

    @Override
    public void destroy() {
        if (destroyFn != null) {
            destroyFn.invoke(this);
        }
    }
}
