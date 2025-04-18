;; tag::ns[]
(ns hello
  (:require [clojure.data.json :as json]                    ;; <1>
            [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]
            [io.pedestal.http.content-negotiation :as content-negotiation])) ;; <2>

;; end::ns[]

(def unmentionables #{"YHWH"
                      "Voldemort"
                      "Mxyzptlk"
                      "Rumplestiltskin"
                      "曹操"})


;; tag::ok_html[]
(defn ok [body]
  {:status  200
   :headers {"Content-Type" "text/html"}                    ;; <1>
   :body    body})
;; end::ok_html[]

;; tag::continuo[]

(defn ok [body]
  {:status 200 :body body})

(defn not-found []
  {:status 404 :body "Not found\n"})

(defn greeting-for [greet-name]
  (cond
    (unmentionables greet-name) nil
    (empty? greet-name) "Hello, world!\n"
    :else (str "Hello, " greet-name "\n")))

(defn greet-handler [request]
  (let [greet-name (get-in request [:query-params :name])
        message    (greeting-for greet-name)]
    (if message
      (ok message)
      (not-found))))

;; end::continuo[]
;; tag::echo[]

(def echo
  {:name  :echo                                             ;; <1>
   :enter (fn [context]                                     ;; <2>
            (let [request  (:request context)               ;; <3>
                  response (ok request)]                    ;; <4>
              (assoc context :response response)))})        ;; <5>
;; end::echo[]

;; tag::routing[]
(def routes
  #{["/greet" :get greet-handler :route-name :greet]
    ["/echo" :get echo :route-name :echo]})
;; end::routing[]

;; tag::routing_conneg[]
(def supported-types ["text/html"
                      "application/edn"
                      "application/json"
                      "text/plain"])                        ;; <3>

(def content-negotiation-interceptor
  (content-negotiation/negotiate-content supported-types))

(def routes
  #{["/greet" :get [content-negotiation-interceptor         ;; <4>
                    greet-handler]
     :route-name :greet]
    ["/echo" :get echo]})
;; end::routing_conneg[]

;; tag::coerce_entangled[]
(def coerce-body-interceptor
  {:name ::coerce-body
   :leave
   (fn [context]
     (let [accepted         (get-in context [:request :accept :field] "text/plain") ;; <1>
           response         (get context :response)
           body             (get response :body)            ;; <2>
           coerced-body     (case accepted                  ;; <3>
                              "text/html" body
                              "text/plain" body
                              "application/edn" (pr-str body)
                              "application/json" (json/write-str body))
           updated-response (assoc response                 ;; <4>
                                   :headers {"Content-Type" accepted}
                                   :body coerced-body)]
       (assoc context :response updated-response)))})       ;; <5>

(def routes
  #{["/greet" :get [coerce-body-interceptor                 ;; <6>
                    content-negotiation-interceptor
                    greet-handler]
     :route-name :greet]
    ["/echo" :get echo]})
;; end::coerce_entangled[]

;; tag::coerce_refactored_comm[]
(def echo
  {:name  :echo
   :enter #(assoc % :response (ok (:request %)))})

(def supported-types ["text/html"
                      "application/edn"
                      "application/json"
                      "text/plain"])

(def content-negotiation-interceptor
  (content-negotiation/negotiate-content supported-types))

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
  #{["/greet" :get [coerce-body-interceptor
                    content-negotiation-interceptor
                    greet-handler]
     :route-name :greet]
    ["/echo" :get echo]})

;; end::routes_refactored[]

;; tag::coerce_body_2[]
(defn missing-response-content-type?                       ;; <1>
  [context]
  (nil? (get-in context [:response :headers "Content-Type"])))

(def coerce-body-interceptor
  {:name ::coerce-body
   :leave
   (fn [context]
     (cond-> context
       (missing-response-content-type? context)             ;; <2>
       (update :response coerce-to (accepted-type context))))}) ;; <3>

;; end::coerce_body_2[]

;; tag::connector[]
(defn create-connector []
  (-> (conn/default-connector-map 8890)
      (conn/with-default-interceptors)
      (conn/with-routes routes)
      (hk/create-connector nil)))

;; For interactive development
(defonce *connector (atom nil))                             ;; <1>

(defn start []
  (reset! *connector                                        ;; <2>
          (conn/start! (create-connector))))

(defn stop []
  (conn/stop! @*connector)
  (reset! *connector nil))

(defn restart []                                            ;; <3>
  (stop)
  (start))
;; end::connector[]
