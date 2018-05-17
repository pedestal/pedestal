(ns io.pedestal.http.aws.lambda)

;;; AWS provides a ServletContext
;;;  - requires a ContainerHandler
;
;(defn- create-server
;  [servlet options]
;  ;; TODO
;  )
;
;
;;; Start and stop are no-ops
;(defn start
;  [server options]
;  server)
;
;(defn stop [^Server server]
;  server)
;
;(defn server
;  ([service-map] (server service-map {}))
;  ([service-map options]
;   (let [server (create-server (:io.pedestal.http/servlet service-map) options)]
;     {:server   server
;      :start-fn #(start server options)
;      :stop-fn  #(stop server)})))


