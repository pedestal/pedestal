package io.pedestal.servlet;

import clojure.lang.IFn;
import clojure.lang.Var;
import clojure.lang.RT;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.GenericServlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * Java Servlets implementation that dispatches via Clojure Vars.
 *
 * <p>ClojureVarServlet is a completely generic implementation of the
 * javax.servlet.Servlet and ServletConfig interfaces. Its behavior is
 * specified via Servlet initialization parameters.
 *
 * <p>The three parameters are 'init', 'service', and 'destroy'. Only
 * 'service' is required; the others are optional. The value of each
 * parameter must be the namespace-qualified name of a Clojure Var.
 * The namespace will be loaded as with clojure.core/require, then the
 * Var will be resolved.
 *
 * <p>The root binding of each Var must be a Clojure function taking the
 * same arguments as the corresponding method in the Servlet interface:
 *
 * <pre>
 * init(Servlet, ServletConfig)
 * service(Servlet, ServletRequest, ServletResponse)
 * destroy(Servlet)
 * </pre>
 *
 * <p>The return value of any of these functions is ignored.
 *
 * <p>The Vars will be resolved and dereferenced only once, when the
 * Servlet is initialized. Changing the root binding of a Var after
 * the Servlet has been initialized will have no effect.
 */
public class ClojureVarServlet extends GenericServlet {
    private IFn serviceFn;
    private IFn destroyFn;
    private static Var REQUIRE = RT.var("clojure.core", "require");
    private static Var SYMBOL = RT.var("clojure.core", "symbol");

    /** Does nothing. Initialization happens in the init method. */
    public ClojureVarServlet() {;}

    /** Initializes the Servlet with Clojure functions resolved from
     * Vars named in the Servlet initialization parameters. If an init
     * function was provided, invokes it. */
    @Override
    public void init() throws ServletException {
        ServletConfig config = this.getServletConfig();
        Var initVar = getVar(config, "init");
        Var serviceVar = getVar(config, "service");
        Var destroyVar = getVar(config, "destroy");

        if (serviceVar == null) {
            throw new ServletException("Missing required parameter 'service'");
        }

        serviceFn = (IFn) serviceVar.deref();
        if (destroyVar != null) { destroyFn = (IFn) destroyVar.deref(); }
        if (initVar != null) { initVar.invoke(this, config); }
    }

    /** Invokes the service function with which this Servlet was
     * initialized. */
    @Override
    public void service(ServletRequest request, ServletResponse response) {
        serviceFn.invoke(this, request, response);
    }

    /** If a destroy function was provided when this Servlet was
     * initialized, invokes it. */
    @Override
    public void destroy() {
        if (destroyFn != null) {
            destroyFn.invoke(this);
        }
    }

    private static Var getVar(ServletConfig config, String param)
        throws ServletException {

        String varName = config.getInitParameter(param);
        if (varName == null) { return null; }

        String[] parts = varName.split("/", 2);
        String namespace = parts[0];
        String name = parts[1];
        if (namespace == null || name == null) {
            throw new ServletException("Invalid namespace-qualified symbol '"
                                       + varName + "'");
        }

        try {
            REQUIRE.invoke(SYMBOL.invoke(namespace));
        } catch(Throwable t) {
            throw new ServletException("Failed to load namespace '"
                                       + namespace + "'", t);
        }

        Var var = RT.var(namespace, name);
        if (var == null) {
            throw new ServletException("Var '" + varName + "' not found");
        }
        return var;
    }
}
