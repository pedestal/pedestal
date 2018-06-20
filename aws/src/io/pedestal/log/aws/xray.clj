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
                                        TraceHeader
                                        TraceHeader$SampleDecision)
           (java.util Map)))

(def trace-header-lower (string/lower-case TraceHeader/HEADER_KEY))

(extend-protocol log/TraceOrigin

  AWSXRayRecorder
  (-register [t]
    (AWSXRay/setGlobalRecorder t))
  (-span
    ([t operation-name]
     (let [^String op-name (if (keyword? operation-name) (name operation-name) operation-name)
           op-ns (when (keyword? operation-name) (namespace operation-name))
           ^Segment segment (.beginSegment t op-name)]
     ;; NOTE: this could smash a current running segment; It'll log if it does that
     (if op-ns
       (doto segment
         (.setNamespace ^String op-ns))
       segment)))
    ([t operation-name parent]
     (let [^String op-name (if (keyword? operation-name) (name operation-name) operation-name)
           op-ns (when (keyword? operation-name) (namespace operation-name))
           ;; The X-Ray API manages Thread Local segments in the Recorder's Segment Context.
           ;;   We need to check if there is an active Entity,
           ;;     If there is, we should to start a subsegment
           ^Entity entity (if-let [current-entity (try
                                                    (.getTraceEntity t)
                                                    (catch Exception e nil))]
                            (.beginSubsegment t ^String op-name)
                            (if parent
                              (.beginSegment t
                                             ^String op-name
                                             ^TraceID (.getTraceId ^Entity parent)
                                             ^String (.getId ^Entity parent))
                              (.beginSegment t op-name)))]
       (if op-ns
         (doto entity
           (.setNamespace ^String op-ns))
         entity)))
    ([t operation-name parent opts]
     (let [{:keys [initial-tags]
            :or {initial-tags {}}} opts
           ^Entity entity (log/-span t operation-name parent)
           _ (.setAnnotations entity ^Map initial-tags)]
       entity)))
  (-activate-span [t span]
    (.setTraceEntity t ^Entity span)
    span)
  (-active-span [t]
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
    ;; We call endSegment on the recorder to also trigger `sendSegment` and other cleanup tasks
    ([t] (.endSegment ^AWSXRayRecorder log/default-tracer) t)
    ([t micros]
     (.setEndTime t micros)
     (.endSegment ^AWSXRayRecorder log/default-tracer)
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
    ;; We call endSubsegment on the recorder to also trigger `sendSegment` and other cleanup tasks
    ([t] (.endSubsegment ^AWSXRayRecorder log/default-tracer) t)
    ([t micros]
     (.setEndTime t micros)
     (.endSubsegment ^AWSXRayRecorder log/default-tracer)
     t)))

(extend-protocol log/TraceSpanLog

  ;;TODO: Maybe Log messages should be registered as subsegments?
  Entity
  (-log-span
    ([t msg]
     (let [log-vec (log/span-baggage t :io.pedestal/log [])]
       (if (keyword? msg)
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event msg
                                                            "time-musec" (quot ^long (System/nanoTime) 1000)}))
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event "info"
                                                            log/span-log-msg msg
                                                            "time-musec" (quot ^long (System/nanoTime) 1000)})))
       t))
    ([t msg micros]
     (let [log-vec (log/span-baggage t :io.pedestal/log [])]
       (if (keyword? msg)
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event msg
                                                            "time-musec" micros}))
         (.putMetadata t "io.pedestal" "log" (conj log-vec {log/span-log-event "info"
                                                            log/span-log-msg msg
                                                            "time-musec" micros})))
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
   * Setting the JVM property io.pedestal.log.defaultTracer to 'io.pedestal.log.aws.xray/tracer'
   * Setting the PEDESTAL_TRACER environment variable to 'io.pedestal.log.aws.xray/tracer'

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
         operation-name (:io.pedestal.interceptor.trace/span-operation context "PedestalSpan")
         ^AWSXRayRecorder recorder log/default-tracer
         ^Entity ent (try
                       ;; Defensively protect against span parse/extract errors,
                       ;;  and on exception, just create a new span without a parent, tagged appropriately
                       (or ;; Is there already a span in the context?
                           (::log/span context)
                           ;; Is there an AWS X-Ray specific span/segment in the servlet request?
                           (when-let [span (and servlet-request
                                                (.getAttribute servlet-request "com.amazonaws.xray.entities.Entity"))]
                             (.beginSubsegment recorder ^String operation-name))
                           ;; Is there an X-Ray Trace ID in the headers?
                           (when-let [header-str (or (get-in context [:request :headers trace-header-lower])
                                                     (get-in context [:request :headers TraceHeader/HEADER_KEY]))]
                             (let [^TraceHeader trace-header (TraceHeader/fromString ^String header-str)
                                   ^TraceID trace-id (.getRootTraceId trace-header)
                                   ^String parent-id (.getParentId trace-header)]
                               ;; Defend against the case where you're cycling back in on yourself,
                               ;; within the same thread.
                               (if-let [current-entity (try
                                                          (.getTraceEntity recorder)
                                                          (catch Exception e nil))]
                                 (.beginSubsegment recorder ^String operation-name)
                                 (.beginSegment recorder
                                                ^String operation-name
                                                ^TraceID trace-id
                                                ^String parent-id))))
                           ;; Otherwise, create a new span
                           (log/span operation-name))
                       (catch Exception e
                         ;; Something happened during decoding a Span,
                         ;; Create a new span and tag it accordingly
                         (log/info :msg "Error occured when trying to resolve an AWS X-Ray Segment"
                                   :exception e)
                         (log/tag-span (log/span operation-name) :revolver-exception (.getMessage e))))]
     ;; X-Ray can remove tags it doesn't recognize (including those common to OpenTracing).
     ;;  This adds XRay-specific HTTP info to the trace, so it gets included.
     (.putHttp ent "request" {"method" (name (get-in context [:request :request-method]))
                              "url"(get-in context [:request :uri])
                              "user_agent" (get-in context [:request :headers "user-agent"])})
     ent)))

(defn span-postprocess
  [context ^Entity span]
  ;; In case someone is plumbing OpenTracing to X-Ray...
  (log/tag-span span "http.status_code" (get-in context [:response :status]))
  ;; X-Ray specific HTTP tagging...
  (.putHttp span "response" {"status" (get-in context [:response :status])})
  (log/finish-span span)
  ;; TODO: We should set the sample decision based on the Span's sample decision
  (assoc-in context [:response :headers TraceHeader/HEADER_KEY]
            (str (TraceHeader. ^TraceID (.getTraceId span)
                               ^String (.getParentId span)
                               ^TraceHeader$SampleDecision (if (and (instance? Segment span) (.isSampled ^Segment span))
                                                             TraceHeader$SampleDecision/SAMPLED
                                                             TraceHeader$SampleDecision/UNKNOWN)))))

