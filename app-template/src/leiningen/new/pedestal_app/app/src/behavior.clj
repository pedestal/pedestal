(ns ^:shared {{namespace}}.behavior
    (:require [clojure.string :as string]
              [io.pedestal.app.messages :as msg]))
{{#annotated?}}
;; While creating new behavior, write tests to confirm that it is
;; correct. For examples of various kinds of tests, see
;; test/{{sanitized}}/test/behavior.clj.
{{/annotated?}}

(defn set-value-transform [old-value message]
  (:value message))

(def example-app
{{#annotated?}}
  ;; There are currently 2 versions (formats) for dataflow
  ;; descritpion: the original version (version 1) and the current
  ;; version (version 2). If the version is not specified, the
  ;; descritpion will be assumed to be version 1 and an attempt
  ;; will be made to convert it to version 2.
{{/annotated?}}
  {:version 2
   :transform [[:set-value [:greeting] set-value-transform]]})

{{#annotated?}}
;; Once this behavior works, run the Data UI and record
;; rendering data which can be used while working on a custom
;; renderer. Rendering involves making a template:
;;
;; app/templates/{{name}}.html
;;
;; slicing the template into pieces you can use:
;;
;; app/src/{{sanitized}}/html_templates.cljs
;;
;; and then writing the rendering code:
;;
;; app/src/{{sanitized}}/rendering.cljs

(comment
  ;; The examples below show the signature of each type of function
  ;; that is used to build a behavior dataflow.

  ;; transform

  (defn example-transform [old-state message]
    ;; returns new state
    )

  ;; derive

  (defn example-derive [old-state inputs]
    ;; returns new state
    )

  ;; emit

  (defn example-emit [inputs]
    ;; returns rendering deltas
    )

  ;; effect

  (defn example-effect [inputs]
    ;; returns a vector of messages which effect the outside world
    )

  ;; continue

  (defn example-continue [iniputs]
    ;; returns a vector of messages which will be processed as part of
    ;; the same dataflow transaction
    )

  ;; dataflow description reference

  {:transform [[:op [:path] example-transform]]
   :derive    #{[#{[:in]} [:path] example-derive]}
   :effect    #{[#{[:in]} example-effect]}
   :continue  #{[#{[:in]} example-continue]}
   :emit      [[#{[:in]} example-emit]]}
  )
{{/annotated?}}
