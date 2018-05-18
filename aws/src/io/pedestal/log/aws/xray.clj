(ns io.pedestal.log.aws.xray
  (:require [io.pedestal.log :as log]
            [clojure.string :as string])
  (:import (com.amazonaws.xray AWSXRay
                               AWSXRayRecorder
                               AWSXRayRecorderBuilder)
           (com.amazonaws.xray.entities Entity
                                        Segment
                                        Subsegment
                                        TraceID
                                        TraceHeader)
           (java.util Map)))

(extend-protocol log/TraceOrigin

  AWSXRayRecorder
  (-register [t]
    (AWSXRay/setGlobalRecorder t))
  (-span
    ;;TODO: Handle `parent` as a string as from the headers
    ([t operation-name]
     (.beginSegment t ^String operation-name))
    ([t operation-name parent]
     (.beginSegment t
                    ^String operation-name
                    ^TraceID (.getTraceId ^Entity parent)
                    ^String (.getId ^Entity parent)))
    ([t operation-name parent opts]
     (let [{:keys [initial-tags]
            :or {initial-tags {}}} opts
           ^Segment segment (.beginSegment t
                                           ^String operation-name
                                           ^TraceID (.getTraceId ^Entity parent)
                                           ^String (.getId ^Entity parent))
           _ (.setAnnotations segment ^Map initial-tags)]
       segment)))
  (-activate-span [t span]
    (.setTraceEntity t ^Entity span)
    span)
  (-active-span [t]
    ;; TODO: It may be smarter to use .getCurrentSegment here, but that might be overly specific
    (.getTraceEntity t)))

(extend-protocol log/TraceSpan

  Segment
  (-set-operation-name [t operation-name]
    (log/info :msg "X-Ray is unable to set operation/span name once the Segment exists. Returning segment as-is"
              :segment t
              :attempted-operation-name operation-name)
    t)
  (-tag-span [t tag-key tag-value]
    (cond
      (string? tag-value) (.putAnnotation t ^String (log/format-name tag-key) ^String tag-value)
      (number? tag-value) (.putAnnotation t ^String (log/format-name tag-key) ^Number tag-value)
      (instance? Boolean tag-value) (.putAnnotation t ^String (log/format-name tag-key) ^Boolean tag-value)
      :else (.putAnnotation t ^String (log/format-name tag-key) ^String (str tag-value)))
    t)
  (-finish-span
    ([t] (.end t) t)
    ([t micros]
     (.setEndTime t micros)
     (.end t)
     t))

  Subsegment
  (-set-operation-name [t operation-name]
    (log/info :msg "X-Ray is unable to set operation/span name once the Subsegment exists. Returning segment as-is"
              :subsegment t
              :attempted-operation-name operation-name)
    t)
  (-tag-span [t tag-key tag-value]
    (cond
      (string? tag-value) (.putAnnotation t ^String (log/format-name tag-key) ^String tag-value)
      (number? tag-value) (.putAnnotation t ^String (log/format-name tag-key) ^Number tag-value)
      (instance? Boolean tag-value) (.putAnnotation t ^String (log/format-name tag-key) ^Boolean tag-value)
      :else (.putAnnotation t ^String (log/format-name tag-key) ^String (str tag-value)))
    t)
  (-finish-span
    ([t] (.end t) (.close t) t)
    ([t micros]
     (.setEndTime t micros)
     (.end t)
     (.close t)
     t))
  )

(extend-protocol log/TraceSpanLog

  ;;TODO: Log messages should be registered as subsegments?
  Entity
  (-log-span
    ([t msg]
     (let [log-vec (log/span-baggage t :io.pedestal/log [])]
       (if (keyword? msg)
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event msg}))
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event "info"
                                                            log/span-log-msg msg})))
       t))
    ([t msg micros]
     (let [log-vec (log/span-baggage t :io.pedestal/log [])]
       (if (keyword? msg)
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event msg
                                                            "time" micros}))
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event "info"
                                                            log/span-log-msg msg
                                                            "time" micros})))
       t)))
  (-error-span
    ([t throwable]
     (.addException t ^Throwable throwable)
     t)
    ([t throwable micros]
     ;; TODO: This call ignores `micros` for now
     (.addException t ^Throwable throwable))))

(extend-protocol log/TraceSpanLogMap

  Entity
  (-log-span-map
    ([t msg-map]
     (let [log-vec (log/span-baggage t :io.pedestal/log [])]
       (.putMetadata t "io.pedestal" "log" (conj log-vec msg-map))
       t))
    ([t msg-map micros]
     ;; TODO: This call ignores `micros` for now
     (let [log-vec (log/span-baggage t :io.pedestal/log [])]
       (.putMetadata t "io.pedestal" "log" (conj log-vec msg-map))
       t))))

(extend-protocol log/TraceSpanBaggage

  Entity
  (-set-baggage [t k v]
    (if-let [k-ns (and (keyword? k) (namespace k))]
      (.putMetadata t k-ns (name k) v)
      (.putMetadata t (log/format-name k) v)))
  (-get-baggage
    ([t k]
     (let [k-ns (if (keyword? k) (or (namespace k) "default"))
           k-str (if (keyword? k) (name k) (log/format-name k))
           meta-ns-map (.get ^Map (.getMetadata t) ^String k-ns)]
       (when meta-ns-map
         (.get ^Map meta-ns-map ^String k-str))))
    ([t k not-found]
     (let [k-ns (if (keyword? k) (or (namespace k) "default"))
           k-str (if (keyword? k) (name k) (log/format-name k))
           meta-ns-map (.get ^Map (.getMetadata t) ^String k-ns)]
       (when meta-ns-map
         (.getOrDefault ^Map meta-ns-map ^String k-str not-found)))))
  (-get-baggage-map [t]
    (.getMetadata t)))

(defn tracer
  "This function returns an XRay Recorder.
  You can assign this tracer to be the default in Pedestal Log by either:
   * Setting the JVM property io.pedestal.log.defaultTracer to 'io.pedestal.log.aws.xray.provide-tracer'
   * Setting the PEDESTAL_TRACER environment variable to 'io.pedestal.log.aws.xray.provide-tracer'

  If you're using an OpenTracing adaptor for XRay,
  you can register the tracer directly with: `(io.pedestal.log/-register ...)`"
  []
  (AWSXRay/getGlobalRecorder))

(defn span-resolver
  "This is an AWS-specific span resolver for use with the
  `io.pedestal.interceptor.trace/tracing-interceptor` Interceptor.

  This resolves any possible Span/Segment in the following order:
   1. Pedestal tracing value in the Context
   2. AWS X-Ray Servlet values (if the Servlet API class is detected)
   3. AWS X-Ray Headers
   4. Nothing found - a new span/segment is created. "
  ([context]
   (span-resolver context (try (Class/forName "javax.servlet.HttpServletRequest")
                                   (catch Exception _ nil))))
  ([context servlet-class]
   (let [servlet-req (and servlet-class (:servlet-request context))
         servlet-request (and servlet-req servlet-class (with-meta servlet-req {:tag servlet-class}))
         operation-name (:io.pedestal.interceptor.trace/span-operation context "PedestalSpan")]
     (try
       ;; OpenTracing can throw errors when an extract fails due to no span being present (according to the docs)
       ;; Defensively protect against span parse/extract errors,
       ;;  and on exception, just create a new span without a parent, tagged appropriately
       (or ;; Is there already a span in the context?
           (::log/span context)
           ;; Is there an AWS X-Ray specific span/segment in the servlet request?
           (when-let [span (and servlet-request
                                (.getAttribute servlet-request "com.amazonaws.xray.entities.Entity"))]
             (log/span operation-name span))
           ;; Is there an X-Ray Trace ID in the headers?
           (when-let [header-str (or (get-in context [:request :headers "x-amzn-trace-id"])
                                     (get-in context [:request :headers "X-Amzn-Trace-Id"]))]
             (let [^TraceHeader trace-header (TraceHeader/fromString ^String header-str)
                   ^TraceID trace-id (.getRootTraceId trace-header)
                   ^String parent-id (.getParentId trace-header)]
               (.beginSegment ^AWSXRayRecorder (AWSXRay/getGlobalRecorder)
                              ^String operation-name
                              trace-id
                              ^String parent-id)))
           ;; Otherwise, create a new span
           (log/span operation-name))
       (catch Exception e
         ;; Something happened during decoding a Span,
         ;; Create a new span and tag it accordingly
         (log/tag-span (log/span operation-name) :revolver-exception (.getMessage e)))))))

