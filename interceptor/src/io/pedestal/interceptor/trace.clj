(ns io.pedestal.interceptor.trace
  (:require [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log])
  (:import (io.opentracing Tracer
                           SpanContext)
           (io.opentracing.propagation Format$Builtin
                                       TextMapExtractAdapter)))

(def should-check-servlet?
  (try (do (import 'javax.servlet.HttpServletRequest) true)
       (catch Throwable _ false)))

(defn default-span-resolver
  [context]
  (let [servlet-request (and should-check-servlet? (:servlet-request context))]
    (or (::log/span context)
        (when-let [span-context (and servlet-request
                                     (.getAttribute ^javax.servlet.HttpServletRequest servlet-request "TracingFilter.activeSpanContext"))]
          (log/span (::span-operation context "PedestalSpan") ^SpanContext span-context))
        (when-let [span-context (.extract ^Tracer log/default-tracer
                                          Format$Builtin/HTTP_HEADERS
                                          (TextMapExtractAdaptor. (get-in context [:request :headers] {})))]
          (log/span (::span-operation context "PedestalSpan") ^SpanContext span-context))
        (when-let [xray-headers (get-in context [:request :headers "X-Amzn-Trace-Id"])]
          ;;TODO: Amazon tracing
          ))))

(defn tracing-interceptor
  "Return an Interceptor for automatically initiating a distributed trace
  span on every request, which is finished on `leave`.

  Spans are automatically populated with relevant
  tags: http.method, http.status_code, http.uril, span.kind

  If on `leave` there is an `:error` in the context, this interceptor with
  log the error with the span.

  Possible options:
   :span-resolver - a single-arg function that is given the context and
                    returns a started and activated span, resolving any propagated or parent span.
                    The default resolver is `io.pedestal.interceptor.trace.default-span-resolver`
                    which resolves (in order; first resolution wins):
                    1. Pedestal tracing values in the Context
                    2. OpenTracing Servlet values (if the Servlet API class is detected)
                    3. OpenTracing Header values
                    4. AWS X-Ray Header values
   :trace-filter - a single-arg function that is given the context and
                   returns true if a span should be created for this request.
                   If not set or set to nil, spans are created for every request
   :uri-as-span-operation? - Boolean; True if the request URI should be used as the default span name - defaults to true
   :default-span-operation - A string or keyword to use as the default span name if URI isn't found or :uri-as-span-operation? is false.
                             Defaults to 'PedestalSpan'

  If the trace-filter or the span-resolver return something falsey, the context is forwarded without
  an active span"
  [opts]
  (let [{:keys [span-resolver
                trace-filter
                uri-as-span-operation?
                default-span-operation]
         :or {span-resolver default-span-resolver
              trace-filter (fn [ctx] true)
              uri-as-span-operation? true
              default-span-operation "PedestalSpan"}} opts]
    (interceptor/interceptor
      {:enter (fn [context]
                (if-let [span (and (trace-filter context)
                                   (span-resolver (assoc context
                                                         ::span-operation (if uri-as-span-operation?
                                                                           (get-in context [:request :uri] default-span-operation)
                                                                           default-span-operation))))]
                  (assoc context
                         ::log/span (doto span))
                  context))
       :leave (fn [context]
                (if-let [span (::log/span context)]))})))

