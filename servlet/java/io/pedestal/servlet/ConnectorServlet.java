/* Copyright 2025 Nubank NA

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
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ConnectorServlet extends GenericServlet {

    private ConnectorBridge bridge;

    /**
     * Does nothing. Initialization happens in the init method.
     */
    public ConnectorServlet() {
    }

    @Override
    public void init() throws ServletException {
        ServletConfig config = this.getServletConfig();
        IFn createFn = Util.getVar(config, "createBridge");

        if (createFn == null) {
            throw new ServletException("Missing required parameter 'createBridge'");
        }

        bridge = (ConnectorBridge) createFn.invoke(this, config);
    }


    /**
     *  Casts the request and response to the HttpServletRequest to HttpServletResponse, and passes
     *  them to the ConnectorBridge's service() method.
     */
    @Override
    public void service(ServletRequest request, ServletResponse response) {
        bridge.service((HttpServletRequest) request, (HttpServletResponse) response);
    }

    @Override
    /**
     * Invokes the ConnectorBridge.destroy() method.
     */
    public void destroy() {
        if (bridge != null) {
            bridge.destroy();

            bridge = null;
        }
    }

}
