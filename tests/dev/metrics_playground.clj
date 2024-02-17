(ns metrics-playground
  (:require [io.pedestal.metrics :as m]
            [io.pedestal.tracing :as t])
  (:import (io.opentelemetry.context Context)))


(comment
  (m/increment-counter ::hit-rate nil)
  (m/advance-counter ::request-time {:path "/api"} 47)
  ((m/histogram ::request-size nil) 35)

  (Context/current)

  (def sb (t/create-span :request {:path "/bazz"}))

  (def span (t/start sb))

  (t/end-span span)

  )

