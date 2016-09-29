(ns chain-providers.service
  (:require [io.pedestal.http :as http])
  (:import (java.nio ByteBuffer)
           (java.net InetSocketAddress)
           (org.eclipse.jetty.server Request
                                     Server)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (javax.servlet.http HttpServletRequest
                               HttpServletResponse)))

(defn my-custom-provider
  "Given a service-map,
  provide all necessary functionality to execute the interceptor chain,
  including extracting and packaging the base :request into context.
  These functions/objects are added back into the service map for use within
  the server-fn.
  See io.pedestal.http.impl.servlet-interceptor.clj as an example.

  Interceptor chains:
   * Terminate based on the list of :terminators in the context.
   * Call the list of functions in :enter-async when going async.  Use these to toggle async mode on the container
   * Will use the fn at :async? to determine if the chain has been operating in async mode (so the container can handle on the outbound)"
  [service-map]
  (assoc service-map ::handler (proxy [AbstractHandler] []
                                 (handle [target base-request servlet-req servlet-resp]
                                   ;; This is where you'd build a context and execute interceptors
                                   (let [resp (.getResponse base-request)]
                                     (.setContentType resp "text/html; charset=utf-8")
                                     (.setStatus resp 200)
                                     (.sendContent (.getHttpOutput resp)
                                                   (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8")))
                                     (.setHandled base-request true))))))

(defn my-custom-server-fn
  "Given a service map (with interceptor provider established) and a server-opts map,
  Return a map of :server, :start-fn, and :stop-fn.
  Both functions are 0-arity"
  [service-map server-opts]
  (let [handler (::handler service-map)
        {:keys [host port join?]
         :or {host "127.0.0.1"
              port 8080
              join? false}} server-opts
        addr (InetSocketAddress. host port)
        server (doto (Server. addr)
                 (.setHandler handler))]
    {:server server
     :start-fn (fn []
                 (.start server)
                 (when join? (.join server))
                 server)
     :stop-fn (fn []
                (.stop server)
                server)}))

(def service {:env                  :prod

              ;; This provider doesn't use a routing interceptor
              ::http/routes         (constantly nil)

              ;; This would normally be a keyword for :jetty, :tomcat,
              ;; etc.
              ;;
              ;; When it is a function, that function is the server function
              ::http/type           my-custom-server-fn

              ;; In the default template, this key is left blank to
              ;; get the default chain provider (the
              ;; servlet-interceptor).
              ;;
              ;; Here we override it to a custom-written chain
              ;; provider
              ::http/chain-provider my-custom-provider

              ::http/port           8080
              })
