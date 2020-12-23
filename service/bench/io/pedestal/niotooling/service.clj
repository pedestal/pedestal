(ns io.pedestal.niotooling.service
  (:require [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor.helpers :as interceptor]
            [ring.util.response :as ring-resp]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clj-http.client :as http]
            [io.pedestal.niotooling.ahttp :as ahttp])
  (:import (java.io ByteArrayInputStream
                    File
                    FileInputStream)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.nio.file StandardOpenOption)
           (java.nio.channels ReadableByteChannel
                              WritableByteChannel
                              Pipe
                              Pipe$SourceChannel
                              FileChannel
                              Channels)))

(def ^File the-index (io/file "dev/public/bench.html"))
(def the-index-path (.toPath the-index))
(def options-array (into-array [StandardOpenOption/READ]))

(def hello-world-bb (ByteBuffer/wrap (.getBytes "Hello World!" "UTF-8")))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn headers-echo
  [request]
  (ring-resp/response (str "Let me show you my headers: " (pr-str (:headers request)))))

(defn sleeper-page
  [request]
  (Thread/sleep (* 100  (inc  (rand 0.5))))
  (ring-resp/response "Hello World!"))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(defn basic-file
  [request]
  (assoc-in (ring-resp/response (io/input-stream the-index))
            [:headers "Content-type"]
            "text/html"))

(defn basic-proxy
  [request]
  (-> (http/get "http://localhost:8081/")
      (select-keys  [:status :headers :body])
      (update-in  [:headers] select-keys  ["Content-Type"])))

(defn nio-home
  [request]
  ;; I could also just use the protocol below
  ;(ring-resp/response (nio-body "Hello NIO World!"))
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body hello-world-bb})

(defn nio-file
  [request]
  ;; I could also just use the protocol below
  (assoc-in (ring-resp/response (FileChannel/open the-index-path options-array))
            [:headers "Content-type"]
            "text/html"))

(defn nio-proxy [req]
  (let [resp (ahttp/request {:request-method :get
                             :uri "http://localhost:8081"})]
    {:status (-> resp :status async/<!!)
     :headers (-> resp :headers async/<!! (select-keys ["Content-Type"]))
     :body (-> resp :body)}))

(interceptor/defbefore async-nio-proxy [context]
  (let [chan (async/chan)
        resp (ahttp/request {:request-method :get
                             :uri "http://localhost:8081"})]
    (async/go
      (async/>! chan (assoc context :response {:status (-> resp :status async/<!)
                                               :headers (-> resp :headers async/<! (select-keys ["Content-Type"]))
                                               :body (-> resp :body)}))
      (async/close! chan))
    chan))



(defprotocol NioResponse
  (nio-body [t] "Package data into a ReadableByteChannel or ByteBuffer, suitable as an NIO Response"))

(def empty-byte-buffer (ByteBuffer/allocateDirect 0))

(extend-protocol NioResponse

  ReadableByteChannel
  (nio-body [t] t)

  Pipe
  (nio-body [t] (.source t))

  java.io.InputStream
  (nio-body [t] (Channels/newChannel t))

  java.io.File
  (nio-body [t] (FileChannel/open (.toPath t) (into-array [StandardOpenOption/READ])))

  ByteBuffer
  (nio-body [t] t)

  String
  (nio-body [t]
    ;(Channels/newChannel (str->inputstream t))
    (ByteBuffer/wrap (.getBytes t "UTF-8")))

  clojure.lang.IPersistentCollection
  (nio-body [t]
    ;(Channels/newChannel (str->inputstream (pr-str t)))
    (ByteBuffer/wrap (.getBytes (pr-str t) "UTF-8")))

  nil
  (nio-body [t] empty-byte-buffer))

(defn nio-home-proto
  [request]
  ;(ring-resp/response (nio-body "Hello NIO World!"))
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (nio-body "Hello NIO World!")})

(def routes
  `[[["/" {:get home-page}
      ["/nio" {:get nio-home}]
      ["/file" {:get basic-file}]
      ["/niofile" {:get nio-file}]
      ["/proxy" {:get basic-proxy}]
      ["/nioproxy" {:get nio-proxy}]
      ["/anioproxy" {:get async-nio-proxy}]
      ;; Set default interceptors for /about and any other paths under /
      ^:interceptors [(body-params/body-params) bootstrap/html-body]
      ["/about" {:get about-page}]
      ["/headers" {:get headers-echo}]]]])

;; Consumed by niotooling.server/create-server
;; See bootstrap/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes

              ;::router :prefix-tree

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::bootstrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"

              ;; Either :jetty or :tomcat (see comments in project.clj
              ;; to enable Tomcat)
              ;;::bootstrap/host "localhost"
              ::bootstrap/type :jetty
              ::bootstrap/port (or (some-> (System/getenv "NIOPORT")
                                           Integer/parseInt)
                                   8080)})


;; Other things to consider (discarded):
;; -------------------------------------

;(defn str->inputstream
;  ([^String text]
;   ;(str->inputstream text StandardCharsets/UTF_8) ; This doesn't seem to work; Use the old/trusted method
;   (str->inputstream text "UTF-8"))
;  ([^String text ^String encoding]
;   (ByteArrayInputStream. (.getBytes text encoding)))
;
;(defprotocol NioByteBuffer
;  (to-byte-buffer [t]))
;
;(extend-protocol NioByteBuffer
;
;  ByteBuffer
;  (to-byte-buffer [t] t)
;
;  String
;  (to-byte-buffer [t]
;    (ByteBuffer/wrap (.getBytes t StandardCharsets/UTF_8))))
;
;(defn ^Pipe nio-pipe
;  ([] (Pipe/open))
;  ([& byte-bufferables]
;   {:pre [(every? #(satisfies? NioByteBuffer byte-bufferables))]}
;   (let [p (Pipe/open)
;         ^WritableByteChannel sink (.sink p)]
;     ;; TODO: Maybe this should be done with a Selector
;     (doseq [bbable byte-bufferables]
;       (.write sink (to-byte-buffer bbable)))
;     (.close sink)
;     p)))

