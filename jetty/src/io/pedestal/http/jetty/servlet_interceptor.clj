(ns io.pedestal.http.jetty.servlet-interceptor
  (:require [io.pedestal.http.impl.servlet-interceptor])
  (:import java.nio.channels.ReadableByteChannel))

(extend-protocol WriteBodyByteChannel
  org.eclipse.jetty.server.Response
  (write-body-byte-channel [servlet-response ^ReadableByteChannel body-chan context resume-chan]
    (let [os ^org.eclipse.jetty.server.HttpOutput (.getOutputStream servlet-response)]
      (.sendContent os body-chan (reify org.eclipse.jetty.util.Callback
                                   (succeeded [this]
                                     (.close body-chan)
                                     (async/put! resume-chan context))
                                   (failed [this throwable]
                                     (.close body-chan)
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))))))))
