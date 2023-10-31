; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor-chain-benchmark
  "Basic benchmarks for the executing the interceptor chain."
  (:require [criterium.core :as c]
            [net.lewisship.trace :refer [trace]]
            [cheshire.core :as cheshire]
            [io.pedestal.http :as service]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.http :as http]))

;; Going to create a simple, default application with two cases:
;; 1. A simple POST request to a handler that succeeds
;; 2. A simple request to a handler that throws an exception

(defn echo-handler [request]
  #_(trace :request request)
  {:status 200
   :headers {"content-type" "application/json"}
   :body (-> request :json-params cheshire/generate-string)})

(def eat-exception
  (interceptor
    {:name ::eat-exception
     :error (fn [context _]
              (assoc context :response {:status 500
                                        :body (str "exception in "
                                                   (get-in context [:request :uri]))}))}))

(defn fail-handler [_]
  (throw (Exception. "Failure inside fail-handler.")))

(def ^:private routes
  #{["/echo" :post [(body-params) echo-handler] :route-name ::echo]
    ["/fail" :get [eat-exception fail-handler] :route-name ::fail]})

(def service-fn
  (-> {::http/routes routes}
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(defn json-response-for
  [url body-data]
  (response-for service-fn :post url
                :headers {"Content-Type" "application/json"}
                :body (cheshire/generate-string body-data)))

(comment
  (c/with-progress-reporting
    (c/bench
      (do (json-response-for "/echo" {:foo 1 :bar 2})
          nil)))

  (c/with-progress-reporting
    (c/bench
      (do (response-for service-fn :get "/fail")
          nil)))
  )

;; Results
;;
;; These are informal results, collected on Howard's M1 Mac, YMMV.
;; Intent was to see if changes to the interceptor.chain namespace improved
;; performance. A lot of the code being tested is outside interceptor.chain however.
;; These numbers imply that even if interceptor chain processing was magically free,
;; it wouldn't affect request handling speed significantly.

;; Baseline:
;; /echo    94.08 µs
;; /fail    149.7 µs

;; Simple opts (only push to stack if :leave or :error)

;; /echo  89.3 µs
;; /fail  141.8 µs

;; After interceptor.chain rewrite:

;; /echo 53.8 µs
;; /fail 90.3 µs

;; Some further, minor optimizations:

;; /echo 52.6 µs
;; /fail 90.8 µs

;; Oct 30 - split invoke-interceptors and invoke-interceptors-only

;; /echo 51.8 µs
;; /fail 86.5 µs

;; Oct 31 - Back to (nearly) Square One
;; Reverted most changes to interceptor.chain namespace.

;; /echo 55.6 µs
;; /fail 93.2 µs
