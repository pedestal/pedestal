; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.connector.dev
  "Optional interceptors and support code used when developing and debugging."
  {:added "0.8.0"}
  (:require [io.pedestal.http.response :as response]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.chain.debug :as chain.debug]
            [clojure.pprint :refer [pprint]]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.service.impl :as impl]))

(defn- attach-formatted-exception-response
  "When an error propagates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [context exception]
  (log/error :msg "Dev interceptor caught an exception; Forwarding it as the response."
             :exception exception)
  (response/respond-with context
                         500
                         {"Content-Type" "text/plain"}
                         (with-out-str (println "Error processing request!")
                                       (println "Exception:\n")
                                       (println (impl/format-exception exception))
                                       (println "\nContext:\n")
                                       (pprint context))))

(def uncaught-exception
  "A development-mode interceptor that captures exceptions, formats them using org.clj-commons/pretty, and generates a
   status 500 text response of the formatted exception."
  (interceptor
    {:name  ::exception-debug
     :error attach-formatted-exception-response}))

(def dev-interceptors
  "[[dev-allow-origin]] and [[exception-debug]] interceptors; these should be used only during
  local development, and should come before other interceptors."
  [cors/dev-allow-origin uncaught-exception])

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

(defn with-interceptor-observer
  "Adds [[debug-observer]] as a context observer for all request executions.  By default, uses
  [[default-debug-observer-omit]] to omit internal or overly verbose context map
  keys.

  The debug observer should not be enabled in production: it is somewhat expensive
  to identify changes to the context, and some data in the context that might be
  logged can be verbose, sensitive, or both.

  This modifies the :initial-context key of the service map."
  ([connector-map]
   (with-interceptor-observer connector-map {:omit default-debug-observer-omit}))
  ([connector-map debug-observer-options]
   (update connector-map :initial-context
           chain/add-observer (chain.debug/debug-observer debug-observer-options))))
