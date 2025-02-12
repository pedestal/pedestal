; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.
(ns io.pedestal.service.dev
  "Optional interceptors and support code used when developing and debugging."
  {:added "0.8.0"}
  (:require [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.chain.debug :as chain.debug]
            [clojure.pprint :refer [pprint]]
            [io.pedestal.service.dev.impl :as dev.impl]
            [io.pedestal.log :as log]
            [ring.util.response :as ring-response]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.cors :as cors]))


(defn- attach-formatted-exception-response
  "When an error propagates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [context exception]
  (log/error :msg "Dev interceptor caught an exception; Forwarding it as the response."
             :exception exception)
  (assoc context
         :response (-> (ring-response/response
                         (with-out-str (println "Error processing request!")
                                       (println "Exception:\n")
                                       (println (dev.impl/format-exception exception))
                                       (println "\nContext:\n")
                                       (pprint context)))
                       (ring-response/status 500))))

(def uncaught-exception
  "A development-mode interceptor that captures exceptions, formats them using org.clj-commons/pretty, and generates a
   status 500 text response of the formatted exception."
  (interceptor
    {:name  ::exception-debug
     :error attach-formatted-exception-response}))

(defn inject-dev-interceptors
  "Prefix the :interceptors in the service map with [[dev-allow-origin]]
   and [[exception-debug]] interceptors to facilitate local development."
  [service-map]
  (update service-map :interceptors
          #(into [cors/dev-allow-origin uncaught-exception] %)))

(defn default-debug-observer-omit
  "Default for key paths to ignore when using [[debug-observer]].  This is primarily the
  request and response bodies, anything private to the io.pedestal.interceptor.chain namespaces,
  and a few routing-related keys (that produce non-useful logged output)."
  [key-path]
  (or (some->> key-path first namespace (contains? #{"io.pedestal.interceptor.chain"
                                                     "io.pedestal.http.tracing"}))
      (contains? #{[:bindings]
                   [:response :body]
                   [:request :body]
                   [:request :url-for]
                   [:url-for]
                   [:route]}
                 key-path)))

(defn enable-debug-interceptor-observer
  "Enables [[debug-observer]] for all request executions.  By default, uses
  [[default-debug-observer-omit]] to omit internal or overly verbose context map
  keys.

  The debug observer should not be enabled in production: it is somewhat expensive
  to identify changes to the context, and some data in the context that might be
  logged can be verbose, sensitive, or both.

  This modifies the :initial-context key of the service map."
  ([service-map]
   (enable-debug-interceptor-observer service-map {:omit default-debug-observer-omit}))
  ([service-map debug-observer-options]
   (update service-map :initial-context
           chain/add-observer (chain.debug/debug-observer debug-observer-options))))
