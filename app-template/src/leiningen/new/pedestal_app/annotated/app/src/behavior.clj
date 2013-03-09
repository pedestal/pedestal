(ns ^:shared {{name}}.behavior
    (:require [clojure.string :as string]))

;; While creating new behavior, write tests to confirm that it is
;; correct. For examples of various kinds of tests, see
;; test/{{sanitized}}/test/behavior.clj.

(defn example-model [model-state message]
  (:value message))

(def example-app
  {:models {:example-model {:init "Hello World!" :fn example-model}}})


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
  
  ;; model
  
  (defn example-model [model-state message]
    ;; returns new state
    )
  
  ;; output
  
  (defn example-output [message old-model-state new-model-state]
    ;; returns vector of messages or map of {:events [] :messages []}
    )
  
  ;; view
  
  (defn example-view-1 [view-state input-name old-model-state new-model-state]
    ;; returns new view state
    )
  
  (defn example-view-2 [view-state inputs]
    ;; inputs are a map of input names to their old and new state
    ;; returns new view state
    )
  
  ;; events
  
  (defn example-events [view-name old-view-state new-view-state]
    ;; returns vector of messages
    )
  
  ;; emitter
  
  (defn example-emitter
    ([input]
       ;; input is a map of input names to their old and new state
       ;; called when emitter is first displayed - returns rendering data
       )
    ([input changed-input]
       ;; input is a map of input names to their old and new state
       ;; changed-input is a set of the input names which have changed
       ;; called when inputs are updated - returns rendering data
       ))
  
  ;; example dataflow map
  
  {:models {:example-model {:init "" :fn example-model}}
   :output {:example-model example-output}
   :views {:example-view {:fn example-view-1 :input #{:example-model}}}
   :events {:examle-view example-event}
   :emitters {:example-emitter {:fn example-emitter :input #{:example-view}}}
   :navigation {:home [[:a-path]]
                :default :home}}
  
  )
