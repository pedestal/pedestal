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

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

class Util {
    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    private static final IFn SYMBOL = Clojure.var("clojure.core", "symbol");

    static IFn getVar(ServletConfig config, String param, boolean required)
            throws ServletException {

        String varName = config.getInitParameter(param);
        if (varName == null) {
            if (required) {
                throw new ServletException(String.format("Missing required parameter '%s'", param));
            }

            return null;
        }

        String[] parts = varName.split("/", 2);
        String namespace = parts[0];
        String name = parts.length > 1 ? parts[1] : null;
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

        // If the Var doesn't already exist, this creates it, but it will throw
        // a reasonable exception when invoked.
        return Clojure.var(namespace, name);
    }
}
