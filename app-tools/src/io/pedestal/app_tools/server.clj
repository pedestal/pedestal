;; Copyright (c) 2012 Relevance, Inc. All rights reserved.
(ns io.pedestal.app-tools.server
  (:require [io.pedestal.app-tools.service :as service]))

(defn app-development-server [port config]
  (service/dev-service port config))
