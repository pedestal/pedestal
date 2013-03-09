;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.util.scheduler
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.util.log :as log])
  (:import (java.util.concurrent Callable Executors
                                 ScheduledExecutorService
                                 ScheduledFuture ThreadFactory
                                 TimeUnit)))

(defprotocol Scheduler
  (schedule [this msecs f]
    "Schedules the no-argument function f to be called after N
    milliseconds. Returns a ScheduledTask.")
  (periodic [this msecs f]
    "Schedules the no-argument function f to be called repeatedly
    every N milliseconds. Returns a ScheduledTask which may be used to
    cancel all future invocations."))

(defprotocol ScheduledTask
  (cancel [this]
    "Cancels the scheduled task. Has no effect if the scheduled task
    has already occurred."))

(extend-type ScheduledFuture
  ScheduledTask
  (cancel [this] (.cancel this false)))

(extend-type ScheduledExecutorService
  Scheduler
  (schedule [this msecs f]
    (.schedule this ^Callable f msecs TimeUnit/MILLISECONDS))
  (periodic [this msecs f]
    (.scheduleAtFixedRate this f msecs msecs TimeUnit/MILLISECONDS)))

(defn scheduler []
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [this r]
       (doto (Thread. r "ScheduledExecutorService")
         (.setUncaughtExceptionHandler
          (reify Thread$UncaughtExceptionHandler
            (uncaughtException [this thread err]
              (log/error :exception err)))))))))
