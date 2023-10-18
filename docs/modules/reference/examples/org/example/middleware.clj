(ns org.example.middleware)

(defn wrap-head-unsupported
  [handler]
  (fn [request]                                             ;; <1>
    (if (= :head (:request-method request))                 ;; <2>
      {:status 400                                          ;; <3>
       :headers {}
       :body "HEAD not supported"}
      (handler request))))                                  ;; <4>
