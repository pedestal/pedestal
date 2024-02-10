(ns metrics-playground
  (:require [io.pedestal.metrics :as m]))

(comment
  (m/increment-counter ::hit-rate nil)



  )

