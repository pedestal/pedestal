; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.construct
  (:require [io.pedestal.app.flow :as flow]
            [io.pedestal.app.model :as model]
            [io.pedestal.app.map :as mapper]
            [io.pedestal.app.route :as route])
  (:use [clojure.core.async :only [chan put!]]))

(defn- router-transforms
  "Given an inform with :channel-added and :channel-removed events, generates
  ::router transforms the router can process."
  [_ inform-message]
  (mapv (fn [[ _ event c config]]
          (cond (= event :channel-added)
                [[[::router] :add [c config :*]]]
                (= event :channel-removed)
                [[[::router] :remove [c config :*]]]))
        inform-message))

(defn build
  "Given an initial model and an app config, construct an app and return its
  inform channel. An app's info model lives at [:info]. Each app has a router
  defined at [::router] that handles routing all transforms.

  When an app receives an inform, it produces transforms and sends them to its
  router. To handle an app's UI or external services, it is recommended to
  generate transforms to a separate path e.g. [:out] and add a channel to the
  app's router. For example:

  (let [cin (construct/build {:info {}} {:in [] :out []})
        cout (chan 10)]
    (put! cin [[[::app/router] :channel-added cout [:out :**]]]))

  An app config has the following config keys:

  * :in: This config vector is used by mapper/inform->transforms to generate
    inbound transforms to the router from an external inform. This is required.
  * :flow: This config vector is used by flow/transform->inform to generate
    :info informs from a router transform.  This is optional. If not present,
    model/transform->inform generates the informs.
  * :out: This config vector is used by mapper/inform->transforms to generate
    outbound transforms to the router from a model/flow inform. This is required."
  [init-model config]
  ;; TODO: How would we configure custom arg-fns for each mapper?
  ;; maybe the answer is: if you need to do that then wire it up yourself
  (let [in-config (conj (:in config) [router-transforms [::router] :*])
        router-c (chan 10)
        in-inform-c (mapper/inform->transforms in-config router-c)
        out-inform-c (mapper/inform->transforms (:out config) router-c)
        model-transform-c (if (:flow config)
                            (flow/transform->inform init-model (:flow config) out-inform-c)
                            (model/transform->inform init-model out-inform-c))]
    (route/router [::router] router-c)
    (put! router-c [[[::router] :add [model-transform-c [:info :**] :*]]])
    in-inform-c))
