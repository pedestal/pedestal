;; tag::ns[]
(ns hello                                                   ;; <1>
  (:require [io.pedestal.http :as http]                     ;; <2>
            [io.pedestal.http.route :as route]))            ;; <3>
;; end::ns[]


;; tag::response_debug_body[]
(defn respond-hello [request]
  {:status 200 :body request})                              ;; <1>
;; end::response_debug_body[]

;; tag::response[]
(defn respond-hello [request]
  (let [nm (get-in request [:query-params :name])]          ;; <1>
    {:status 200 :body (str "Hello, " nm "\n")}))           ;; <2>
;; end::response[]


;; tag::response_logic[]
(defn respond-hello [request]
  (let [nm (get-in request [:query-params :name])
        resp (if (empty? nm)                                ;; <1>
               "Hello, world!\n"                            ;; <2>
               (str "Hello, " nm "\n"))]                    ;; <3>
    {:status 200 :body resp}))                              ;; <4>
;; end::response_logic[]

;; tag::response_logic_refactor[]
(defn ok [body]                                             ;; <1>
  {:status 200 :body body})

(defn greeting-for [nm]                                     ;; <2>
  (if (empty? nm)
    "Hello, world!\n"
    (str "Hello, " nm "\n")))

(defn respond-hello [request]                               ;; <3>
  (let [nm (get-in request [:query-params :name])
        resp (greeting-for nm)]
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
;; end::greeting_with_404[]

;; tag::routing[]
(def routes
  (route/expand-routes                                      ;; <1>
    #{["/greet" :get respond-hello :route-name :greet]}))   ;; <2>
;; end::routing[]

;; tag::server[]
(def service-map
  {::http/routes routes
   ::http/type :jetty
   ::http/port 8890})

(defn start []
  (http/start (http/create-server service-map)))

;; For interactive development
(defonce server (atom nil))                                 ;; <1>

(defn start-dev []
  (reset! server                                            ;; <2>
          (http/start (http/create-server
                        (assoc service-map
                               ::http/join? false)))))      ;; <3>

(defn stop-dev []
  (http/stop @server))

(defn restart []                                            ;; <4>
  (stop-dev)
  (start-dev))
;; end::server[]
