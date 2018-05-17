(ns io.pedestal.log.aws.xray
  (:require [io.pedestal.log :as log])
  (:import (com.amazonaws.xray AWSXRay
                               AWSXRayRecorder
                               AWSXRayRecorderBuilder)
           (com.amazonaws.xray.entities Entity
                                        Segment
                                        Subsegment
                                        TraceID)
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
    ([t] (.end t) t)
    ([t micros]
     (.setEndTime t micros)
     (.end t)
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

(defn provide-tracer
  "This function returns an XRay Recorder.
  You can assign this tracer to be the default in Pedestal Log by either:
   * Setting the JVM property io.pedestal.log.defaultTracer to 'io.pedestal.log.aws.xray.provide-tracer'
   * Setting the PEDESTAL_TRACER environment variable to 'io.pedestal.log.aws.xray.provide-tracer'

  If you're using an OpenTracing adaptor for XRay,
  you can register the tracer directly with: `(io.pedestal.log/-register ...)`"
  []
  (AWSXRay/getGlobalRecorder))


