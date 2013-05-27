(ns {{namespace}}.services)

(comment

  ;; The services implementation will need some way to send messages
  ;; back to the application. The queue passed to the services function
  ;; will convey messages to the application.
  (defn echo-services-fn [message queue]
    (put-message queue message))
  
  )

;; During development, it is helpful to implement services which
;; simulate communication with the real services. This implementaiton
;; can be placed in the file
;;
;; app/src/{{sanitized}}/simulated/services.cljs
;;
