(ns io.pedestal.interceptor.provider)

(defprotocol InterceptorChainProvider

  ;(provider-context [t ])
  ;(async-supported? [t])
  ;(go-async [t])
  ;(round-trip-chain [t interceptors])
  ;(one-way-chain [t interceptors])

  )

;; Rules for chain providers
;; --------------------------
;;
;; 1.) A function, that when given a map of provider details,
;;     returns an initial context.
;; 2.) If they allow for async handling,
;;     they must provide an `:enter-async` key on the context -
;;     a function that takes a context, triggers the provider/container's
;;     processing async, and returns a context with an additional key,
;;     `:async?` set to a truthy value
;; 3.) Optionally provide a terminatation predicates for the interceptor chain
;;     on the context under :io.pedestal.interceptor.chain/terminators
;; 4.)
