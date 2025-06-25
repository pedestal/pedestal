;; tag::ns[]
(ns hello                                                   ;; <1>
  (:require [io.pedestal.connector :as conn]                ;; <2>
            [io.pedestal.http.http-kit :as hk]))            ;; <3>
;; end::ns[]

(defn greet-handler [_request]                              ;; <1>
  {:status 200
   :body   "Hello, world!"})                                ;; <2>

;; tag::response_debug_body[]
(defn greet-handler [request]                               ;; <1>
  {:status 200 :body request})                              ;; <2>
;; end::response_debug_body[]

;; tag::response[]
(defn greet-handler [request]
  (let [green-name (get-in request [:query-params :name])]  ;; <1>
    {:status 200 :body (str "Hello, " green-name "\n")}))   ;; <2>
;; end::response[]

;; tag::response_sidequest[]
(defn greet-handler [request]
  (let [greet-name (get-in request [:query-params :name])]
    {:status 200 :body (str "Hello, " name "\n")}))
;; end::response_sidequest[]

;; tag::response_logic[]
(defn greet-handler [request]
  (let [greet-name (get-in request [:query-params :name])
        message    (if (empty? greet-name)                  ;; <1>
               "Hello, world!\n"                            ;; <2>
               (str "Hello, " greet-name "\n"))]            ;; <3>
    {:status 200 :body message}))                           ;; <4>
;; end::response_logic[]

;; tag::response_logic_refactor[]
(defn ok [message]                                          ;; <1>
  {:status 200 :body message})

(defn greeting-for [greet-name]                             ;; <2>
  (if (empty? greet-name)
    "Hello, world!\n"
    (str "Hello, " greet-name "\n")))

(defn greet-handler [request]                               ;; <3>
  (let [greet-name (get-in request [:query-params :name])
        resp       (greeting-for greet-name)]
    (ok resp)))
;; end::response_logic_refactor[]

;; tag::not_to_be_named[]
(def unmentionables #{"YHWH"
                      "Voldemort"
                      "Mxyzptlk"
                      "Rumplestiltskin"
                      "曹操"})
;; end::not_to_be_named[]

;; tag::greeting_with_404[]
(defn ok [message]
  {:status 200 :body message})

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
;; end::greeting_with_404[]

;; tag::routing[]
(def routes
  #{["/greet" :get greet-handler :route-name :greet]}) 
;; end::routing[]

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
