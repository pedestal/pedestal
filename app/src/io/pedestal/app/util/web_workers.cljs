(ns io.pedestal.app.util.web-workers
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.queue :as queue]
            [io.pedestal.app.render.push :as push-render]
            [cljs.reader :as reader]))

(defn create-render-queue [config input-queue]
  (assert (:id config) "render config must have :id")
  (assert (:config config) "render config must have :config")
  (push-render/push-render-queue (:id config)
                                 ((:config config))
                                 input-queue))

(defn run-on-web-worker! [worker-source & {:keys [render]}]
  (assert render "run-on-web-worker! currently supports only :render")
  (let [worker-input-queue (queue/queue :worker-input)
        render-queue (create-render-queue render worker-input-queue)
        worker (js/Worker. worker-source)]
    (queue/consume-queue worker-input-queue #(.postMessage worker (pr-str %)))
    (.addEventListener worker
                       "message"
                       (fn [e]
                         (p/put-message render-queue (reader/read-string (.-data e)))))))
