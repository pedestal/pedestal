(ns {{name}}.services)

;; The services namespace responsible for communicating with back-end
;; services. It receives messages from the application's behavior,
;; makes requests to services and sends responses back to the
;; behavior.
;;
;; This namespace will usually contain a function which can be
;; configured to receive output messages from the behavior in the file
;;
;; app/src/{{sanitized}}/start.cljs
;;
;; After creating a new application, set the output handler function
;; to recieve output
;;
;; (app/consume-output app services-fn)
;;
;; A very simple example of a services function which echos all messages
;; back to the behavior is shown below

(comment

  ;; The services implementation will need some way to send messages
  ;; back to the application. The queue passed to services function
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
