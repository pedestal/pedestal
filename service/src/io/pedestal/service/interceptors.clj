; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.interceptors
  {:added "0.8.0"}
  (:require [clojure.string :as string]
            [io.pedestal.log :as log]
            [io.pedestal.http.response :as response]
            [io.pedestal.metrics :as metrics]
            [io.pedestal.interceptor :refer [interceptor]]
            [ring.util.response :as ring-response]))

(defn- response-interceptor
  [interceptor-name pred xform]
  (interceptor
    {:name  interceptor-name
     :leave (fn [context]
              (let [{:keys [response]} context]
                (cond-> context
                  (pred response) (assoc :response (xform response)))))}))


(def ^:private request-meter-fn (metrics/counter ::request nil))

(def log-request
  "Log the request's method and uri."
  (interceptor
    {:name  ::log-request
     :enter (fn [context]
              (let [{:keys [request]} context
                    {:keys [uri request-method]} request]
                (log/info :msg (format "%s %s"
                                       (-> request-method name string/upper-case)
                                       uri))
                (request-meter-fn)
                context))}))


(def not-found
  "An interceptor that returns a 404 when routing failed to resolve a route, or no :response
  map was attached to the context."
  (interceptor
    {:name  ::not-found
     :leave (fn [context]
              (if (and (not (-> context :response response/response?))
                       (response/response-expected? context))
                (assoc context :response
                       (ring-response/not-found "Not Found"))
                context))}))

(defn- missing-content-type?
  [response]
  (nil? (get-in response [:headers "Content-Type"])))

(def html-body
  "Sets the Content-Type header to \"text/html\" if the body is a string and a
  type has not been set."
  (response-interceptor
    ::html-body
    #(and (-> % :body string?)
          (missing-content-type? %))
    #(ring-response/content-type % "text/html;charset=UTF-8")))


(def json-body
  "Set the Content-Type header to \"application/json\" and convert the body to
  JSON if the body is a collection and a type has not been set."
  (response-interceptor
    ::json-body
    #(and (-> % :body coll?)
          (missing-content-type? %))
    (fn [response]
      (-> response
          (ring-response/content-type "application/json;charset=UTF-8")
          (update :body response/stream-json)))))

(defn transit-body-interceptor
  "Returns an interceptor which sets the Content-Type header to the
  appropriate value depending on the transit format. Converts the body
  to the specified Transit format if the body is a collection and a
  type has not been set. Optionally accepts transit-opts which are
  handed to trasit/writer and may contain custom write handlers.

  Expects the following arguments:

  iname                - namespaced keyword for the interceptor name
  default-content-type - content-type string to set in the response
  transit-format       - either :json or :msgpack
  transit-options      - optional. map of options for transit/writer"
  ([iname default-content-type transit-format]
   (transit-body-interceptor iname default-content-type transit-format {}))
  ([iname default-content-type transit-format transit-opts]
   (response-interceptor
     iname
     #(and (-> % :body coll?)
           (missing-content-type? %))
     (fn [response]
       (-> response
           (ring-response/content-type default-content-type)
           (update :body response/stream-transit transit-format transit-opts))))))

(def transit-json-body
  "Set the Content-Type header to \"application/transit+json\" and convert the body to
  transit+json if the body is a collection and a type has not been set."
  (transit-body-interceptor
    ::transit-json-body
    "application/transit+json;charset=UTF-8"
    :json))

(def transit-msgpack-body
  "Set the Content-Type header to \"application/transit+msgpack\" and convert the body to
  transit+msgpack if the body is a collection and a type has not been set."
  (transit-body-interceptor
    ::transit-msgpack-body
    "application/transit+msgpack;charset=UTF-8"
    :msgpack))
