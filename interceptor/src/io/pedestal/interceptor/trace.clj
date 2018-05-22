(ns io.pedestal.interceptor.trace
  (:require [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log])
  (:import (io.opentracing Tracer
                           SpanContext)
           (io.opentracing.propagation Format$Builtin
                                       TextMapExtractAdapter)))

(defn default-span-resolver
  ([context]
   (default-span-resolver context (try (Class/forName "javax.servlet.HttpServletRequest")
                                       (catch Exception _ nil))))
  ([context servlet-class]
   (let [servlet-req (and servlet-class (:servlet-request context))
         servlet-request (and servlet-req servlet-class (with-meta servlet-req {:tag servlet-class}))
         operation-name (::span-operation context "PedestalSpan")]
     (try
       ;; OpenTracing can throw errors when an extract fails due to no span being present (according to the docs)
       ;; Defensively protect against span parse/extract errors,
       ;;  and on exception, just create a new span without a parent, tagged appropriately
       (or ;; Is there already a span in the context?
           (::log/span context)
           ;; Is there a span in the servlet request?
           (when-let [span-context (and servlet-request
                                        (.getAttribute servlet-request "TracingFilter.activeSpanContext"))]
             (log/span operation-name ^SpanContext span-context))
           ;; Is there an OpenTracing span in the headers? (header key is "uber-id")
           (when-let [span-context (.extract ^Tracer log/default-tracer
                                             Format$Builtin/HTTP_HEADERS
                                             (TextMapExtractAdapter. (get-in context [:request :headers] {})))]
             (log/span operation-name ^SpanContext span-context))
           ;; Otherwise, create a new span
           (log/span operation-name))
       (catch Exception e
         ;; Something happened during decoding a Span,
         ;; Create a new span and tag it accordingly
         (log/info :msg "Error occured when trying to resolve an OpenTracing Span"
                   :exception e)
         (log/tag-span (log/span operation-name) :revolver-exception (.getMessage e)))))))

(defn default-span-postprocess
  [context span]
  (log/tag-span span "http.status_code" (get-in context [:response :status]))
  (log/finish-span span)
  context)

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
                    The default resolver is `io.pedestal.interceptor.trace.ot-span-resolver`
                    which resolves (in order; first resolution wins):
                    1. Pedestal tracing values in the Context
                    2. OpenTracing Servlet values (if the Servlet API class is detected)
                    3. OpenTracing Header values
                    4. Nothing found - A new span is created
   :trace-filter - a single-arg function that is given the context and
                   returns true if a span should be created for this request.
                   If not set or set to nil, spans are created for every request
   :uri-as-span-operation? - Boolean; True if the request URI should be used as the default span name - defaults to true
   :default-span-operation - A string or keyword to use as the default span name if URI isn't found or :uri-as-span-operation? is false.
                             Defaults to 'PedestalSpan'
   :span-postprocess - A function given the context and the span,
                       performs any necessary span cleanup tasks
                       and returns the context

  If the trace-filter or the span-resolver return something falsey, the context is forwarded without
  an active span"
  ([]
   (tracing-interceptor {}))
  ([opts]
   (let [{:keys [span-resolver
                 trace-filter
                 uri-as-span-operation?
                 default-span-operation
                 span-postprocess]
          :or {span-resolver default-span-resolver
               trace-filter (fn [ctx] true)
               uri-as-span-operation? true
               default-span-operation "PedestalSpan"
               span-postprocess default-span-postprocess}} opts
         servlet-class (try (Class/forName "javax.servlet.HttpServletRequest")
                            (catch Exception _ nil))]
     (interceptor/interceptor
       {:name ::tracing-interceptor
        :enter (fn [context]
                 (if-let [span (and (trace-filter context)
                                    (span-resolver (assoc context
                                                          ::span-operation (if uri-as-span-operation?
                                                                             (get-in context [:request :uri] default-span-operation)
                                                                             default-span-operation))
                                                   servlet-class))]
                   (assoc context ::log/span (log/tag-span
                                               span
                                               {"http.method" (name (get-in context [:request :request-method]))
                                                "http.url" (get-in context [:request :uri])
                                                "http.user-agent" (get-in context [:request :headers "user-agent"])
                                                "component" "pedestal"
                                                "span.kind" "server"}))
                   context))
        :leave (fn [context]
                 (if-let [span (::log/span context)]
                   (span-postprocess context span)
                   context))
        :error (fn [context throwable]
                 (if-let [span (::log/span context)]
                   (do
                     (log/log-span span throwable)
                     (log/finish-span span)
                     (assoc context ::chain/error throwable))
                   (assoc context ::chain/error throwable)))}))))

