;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app-tools.middleware
  (:require [io.pedestal.app.util.log :as log]
            [io.pedestal.service.interceptor :refer [defon-response]])
  (:import java.io.File))

(defon-response js-encoding
  [{:keys [headers body] :as response}]
  (if (and (= (get headers "Content-Type") "text/javascript")
           (= (type body) File))
    (assoc-in response [:headers "Content-Type"]
              "text/javascript; charset=utf-8")
    response))
