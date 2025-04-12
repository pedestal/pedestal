;; tag::ns[]
(ns hello
  (:require [clojure.data.json :as json]                    ;; <1>
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.content-negotiation :as content-negotiation])) ;; <2>

;; end::ns[]

(def unmentionables #{"YHWH"
                      "Voldemort"
                      "Mxyzptlk"
                      "Rumplestiltskin"
                      "曹操"})


;; tag::ok_html[]
(defn ok [body]
  {:status 200 :body body
   :headers {"Content-Type" "text/html"}})                  ;; <1>
;; end::ok_html[]

;; tag::continuo[]

(defn ok [body]
  {:status 200 :body body})

(defn not-found []
  {:status 404 :body "Not found\n"})

(defn greeting-for [nm]
  (cond
    (unmentionables nm) nil
    (empty? nm) "Hello, world!\n"
    :else (str "Hello, " nm "\n")))

(defn respond-hello [request]
  (let [nm (get-in request [:query-params :name])
        resp (greeting-for nm)]
    (if resp
      (ok resp)
      (not-found))))

;; end::continuo[]
;; tag::echo[]

(def echo
  {:name ::echo                                             ;; <1>
   :enter (fn [context]                                     ;; <2>
            (let [request (:request context)                ;; <3>
                  response (ok request)]                    ;; <4>
              (assoc context :response response)))})        ;; <5>
;; end::echo[]

;; tag::routing[]
(def routes
  (route/expand-routes
    #{["/greet" :get respond-hello :route-name :greet]
      ["/echo" :get echo]}))
;; end::routing[]

;; tag::routing_conneg[]
(def supported-types ["text/html"
                      "application/edn"
                      "application/json"
                      "text/plain"])                        ;; <3>

(def content-negotiation-interceptor
  (content-negotiation/negotiate-content supported-types))

(def routes
  (route/expand-routes
    #{["/greet" :get [content-negotiation-interceptor       ;; <4>
                      respond-hello]
       :route-name :greet]
      ["/echo" :get echo]}))
;; end::routing_conneg[]

;; tag::coerce_entangled[]
(def coerce-body-interceptor
  {:name ::coerce-body
   :leave
   (fn [context]
     (let [accepted (get-in context [:request :accept :field] "text/plain") ;; <1>
           response (get context :response)
           body (get response :body)                        ;; <2>
           coerced-body (case accepted                      ;; <3>
                          "text/html" body
                          "text/plain" body
                          "application/edn" (pr-str body)
                          "application/json" (json/write-str body))
           updated-response (assoc response                 ;; <4>
                                   :headers {"Content-Type" accepted}
                                   :body coerced-body)]
       (assoc context :response updated-response)))})       ;; <5>

(def routes
  (route/expand-routes
    #{["/greet" :get [coerce-body-interceptor               ;; <6>
                      content-negotiation-interceptor
                      respond-hello]
       :route-name :greet]
      ["/echo" :get echo]}))
;; end::coerce_entangled[]

;; tag::coerce_refactored_comm[]
(def echo
  {:name ::echo
   :enter #(assoc % :response (ok (:request %)))})

(def supported-types ["text/html"
                      "application/edn"
                      "application/json"
                      "text/plain"])

(def content-negotiation-interceptor (content-negotiation/negotiate-content supported-types))

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "text/plain"))

(defn transform-content
  [body content-type]
  (case content-type
    "text/html" body
    "text/plain" body
    "application/edn" (pr-str body)
    "application/json" (json/write-str body)))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))
;; end::coerce_refactored_comm[]
;; tag::coerce_body_1[]

(def coerce-body-interceptor
  {:name ::coerce-body
   :leave
   (fn [context]
     (if (get-in context [:response :headers "Content-Type"])
       context
       (update-in context [:response] coerce-to (accepted-type context))))})
;; end::coerce_body_1[]
;; tag::routes_refactored[]

(def routes
  (route/expand-routes
    #{["/greet" :get [coerce-body-interceptor
                      content-negotiation-interceptor
                      respond-hello] :route-name :greet]
      ["/echo" :get echo]}))

;; end::routes_refactored[]

;; tag::coerce_body_2[]
(def coerce-body-interceptor
  {:name ::coerce-body
   :leave
   (fn [context]
     (cond-> context
       (nil? (get-in context [:response :headers "Content-Type"])) ;; <1>
       (update-in [:response] coerce-to (accepted-type context))))}) ;; <2>

;; end::coerce_body_2[]

;; tag::server[]
(def service-map
  {::http/routes routes
   ::http/type :jetty
   ::http/port 8890})

(defn start []
  (http/start (http/create-server service-map)))

;; For interactive development
(defonce server (atom nil))

(defn start-dev []
  (reset! server
          (http/start (http/create-server
                        (assoc service-map
                               ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []                                            ;
  (stop-dev)
  (start-dev))
;; end::server[]
