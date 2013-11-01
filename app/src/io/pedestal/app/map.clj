; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.map
  "Find functions which map an inform message to transform
  messages (hereafter named i->t fns) based on patterns in the event
  entry.

  Transform messages are vectors of one or more transformation entries:

  [[target op other-args] ...]

  Inform messages are vectors of one or more event entries:

  [[source event other-data] ...]

  In the above event entry, source is a path which is the unique id of
  some component in the system; event represents the type of event.

  Source paths are vectors. For example:

  [:a :b :c :d]

  Events are keywords. For example:

  :click, :submit, :added, :removed

  A configuration is provided which maps functions to source/event
  patterns.

  [[function-a [:path1] :*]
   [function-b [:path2] :click]
   [function-c [:path3] :* [:path4] :*]]

  Given a source path and event for an event entry, this map is used
  to find an i->t function.

  A matching path could be an identical path like

  [:a :b :c :d]

  Path patterns may also contain wildcard keywords :* and :**. :*
  matches any path element and :** matches zero or more path elements.

  We could match the path above with the following patterns:

  [:a :b :c :d]
  [:a :b :c :*]
  [:a :* :c :*]
  [:a :* :* :*]
  [:a :**]
  [:**]

  The :* wildcard can be used to match any event.

  An i->t function takes two arguments, the patterns defined in the
  configuration and the inform message that matches these
  patterns. This is not always the best signature for a given i->t
  function so an args-fn can be provided to support any signature."
  (:require [io.pedestal.app.match :as match])
  (:use [clojure.core.async :only [go chan <! >!]]))

(defn default-args-fn
  "Default args-fn implementation which returns the patterns and
  inform message as two arguments to be applied to an i->t function."
  [patterns inform] [patterns inform])

(defn inform-to-transforms
  "Given an index and an inform message, return a collection of
  transform messages. Optionally provide an args-fn to supply the
  desired arguments to the i->t functions."
  ([index inform]
     (inform-to-transforms index inform default-args-fn))
  ([index inform args-fn]
     (let [fns (match/match index inform)]
       (vec (mapcat (fn [[f patterns inf]] (apply f (args-fn patterns inf))) fns)))))

(defn inform->transforms
  "Given a configuration and a transform channel, return an inform channel.
  When an inform message is put on the inform channel, the resulting
  transform messages will be put on the transform channel."
  ([config transform-c]
     (inform->transforms config transform-c default-args-fn))
  ([config transform-c args-fn]
     (let [inform-c (chan 10)
           idx (match/index config)]
       (go (loop []
             (let [inform (<! inform-c)]
               (when inform
                 (doseq [t (inform-to-transforms idx inform args-fn)]
                   (>! transform-c t))
                 (recur)))))
       inform-c)))
