;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.net.repl-client
  (:require [goog.uri.utils :as uri]
            [clojure.browser.repl :as repl]))

(defn- server
  "Return a string which is the scheme and domain portion of the URL
  for the server from which this code was served."
  []
  (let [location (.toString window.location ())]
    (str (uri/getScheme location) "://" (uri/getDomain location))))

;; TODO: Replace all use of repl with main

(defn ^:export repl
  "Connects to a ClojureScript REPL on the server which served this
  page and the specified port. The port defaults to 9000.

  This allows a browser-connected REPL to send JavaScript to the
  browser for evaluation. This function should be called from a script
  in the host HTML page."
  ([]
     (repl 9000))
  ([port]
     (repl/connect (str (server) ":" port "/repl"))))

(defn ^:export main
  "This function is provided to allow for more compact config for
  the :fresh aspect in config/config.clj"
  []
  (repl))
