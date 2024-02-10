(ns metrics-playground
  (:require [io.pedestal.metrics :as m]))

(comment
  (m/increment-counter ::hit-rate nil)
  (m/advance-counter ::request-time {:path "/api"} 47)
  ((m/histogram ::request-size nil) 35)

  )

