;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns ^:shared io.pedestal.app.protocols
    "Protocols for Pedestal applications.")

(defprotocol Activity
  (start [this])
  (stop [this]))

(defprotocol PutMessage
  (put-message [this message]
    "Put a message in a queue."))

(defprotocol TakeMessage
  (take-message [this f]
    "When the next message is available, call (f message). Ensures that no
    other function gets the same message."))
