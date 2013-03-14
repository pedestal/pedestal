(ns ^:shared {{name}}.behavior
    (:require [clojure.string :as string]))

;; While creating new behavior, write tests to confirm that it is
;; correct. For examples of various kinds of tests, see
;; test/{{sanitized}}/test/behavior.clj.

(defn example-transform [transform-state message]
  (:value message))

(def example-app
  {:transform {:example-transform {:init "Hello World!" :fn example-transform}}})


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
  
  (defn example-transform [transform-state message]
    ;; returns new state
    )
  
  ;; effect
  
  (defn example-effect [message old-transform-state new-transform-state]
    ;; returns vector of messages to be added to input queue for future processing
    )
  
  ;; combine
  
  (defn example-combine-1 [combine-state input-name old-transform-state new-transform-state]
    ;; returns new combine state
    )
  
  (defn example-combine-2 [combine-state inputs]
    ;; inputs are a map of input names to their old and new state
    ;; returns new combine state
    )
  
  ;; continue
  
  (defn example-continue [combine-name old-combine-state new-combine-state]
    ;; returns vector of messages to be processed as part of current data flow execution
    )
  
  ;; emit
  
  (defn example-emit
    ([input]
       ;; input is a map of input names to their old and new state
       ;; called when emit is first displayed - returns rendering data
       )
    ([input changed-input]
       ;; input is a map of input names to their old and new state
       ;; changed-input is a set of the input names which have changed
       ;; called when inputs are updated - returns rendering data
       ))
  
  ;; example dataflow map
  
  {:transform {:example-transform {:init "" :fn example-transform}}
   :effect {:example-transform example-effect}
   :combine {:example-combine {:fn example-combine-1 :input #{:example-transform}}}
   :continue {:example-combine example-continue}
   :emit {:example-emit {:fn example-emit :input #{:example-combine}}}
   :focus {:home [[:a-path]]
                :default :home}}
  
  )
