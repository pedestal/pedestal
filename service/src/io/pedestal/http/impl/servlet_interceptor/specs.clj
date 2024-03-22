; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.impl.servlet-interceptor.specs
  (:require [clojure.spec.alpha :as s]
            [io.pedestal.interceptor.specs :as interceptor]
            [io.pedestal.http.impl.servlet-interceptor :as si]))

(s/def ::http-interceptor-service-fn-options
  (s/keys :opt-un [::exception-analyzer]))

(s/def ::exception-analyzer fn?)

(s/fdef si/http-interceptor-service-fn
        :args (s/cat :interceptors (s/coll-of ::interceptor/interceptor-record)
                     :default-context (s/? (s/nilable map?))
                     :options (s/? (s/nilable ::http-interceptor-service-fn-options)))
        :ret fn?)
