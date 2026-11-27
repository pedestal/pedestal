; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.response-body-write-test
  "Writing response bodies through the Servlet API: NIO bodies (ByteBuffer, ReadableByteChannel) in any
  servlet container, and what the client receives when writing the body fails.

  Each case runs through the mock servlet objects (io.pedestal.servlet-support), whose response is
  not Jetty's, and through a real Jetty connector."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.log :as log]
            [io.pedestal.servlet-support :as servlet-support]
            [mockfn.macros :as mock]
            [mockfn.matchers :as m])
  (:import (java.io ByteArrayInputStream IOException OutputStream)
           (java.net ServerSocket)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

(def ^:private port
  "A free port, so that this test doesn't depend on a port that another test or process uses."
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(def ^:private large-size
  "Larger than the response buffer of the mock (1500 bytes) and of Jetty (32 KiB)."
  100000)

(def ^:private large-body (apply str (repeat large-size "x")))

(def ^:private *closed-channels (atom 0))

(defn ^:private tracking-channel
  "Wraps a channel to count how many times it is closed."
  ^ReadableByteChannel [^ReadableByteChannel channel]
  (reify ReadableByteChannel
    (read [_ buffer] (.read channel buffer))
    (isOpen [_] (.isOpen channel))
    (close [_]
      (swap! *closed-channels inc)
      (.close channel))))

(defn ^:private failing-channel
  "A channel that returns n bytes (the letter A), then throws."
  ^ReadableByteChannel [n]
  (let [*remaining (atom n)]
    (reify ReadableByteChannel
      (read [_ buffer]
        (let [k (min @*remaining (.remaining buffer))]
          (if (pos? k)
            (do
              (dotimes [_ k] (.put buffer (byte 65)))
              (swap! *remaining - k)
              k)
            (throw (IOException. "Channel read failed")))))
      (isOpen [_] true)
      (close [_]
        (swap! *closed-channels inc)))))

(defn ^:private fn-body
  "A function body that writes s, flushes when flush? is true, then throws an IOException
  (with the message \"Body write failed\", unless another is given)."
  ([s flush?]
   (fn-body s flush? "Body write failed"))
  ([^String s flush? ^String message]
   (fn [^OutputStream output-stream]
     (.write output-stream (.getBytes s "UTF-8"))
     (when flush?
       (.flush output-stream))
     (throw (IOException. message)))))

(def ^:private bodies
  {"/byte-buffer"           #(ByteBuffer/wrap (.getBytes "Hello ByteBuffer" "UTF-8"))
   "/byte-channel"          #(tracking-channel (Channels/newChannel (ByteArrayInputStream. (.getBytes ^String large-body "UTF-8"))))
   "/failing-channel"       #(failing-channel 0)
   "/channel-fails-late"    #(failing-channel large-size)
   "/fn-fails-at-once"      #(fn-body "" false)
   "/fn-fails-buffered"     #(fn-body "buffered" false)
   "/fn-fails-after-flush"  #(fn-body "PARTIAL" true)
   "/fn-broken-pipe"        #(fn-body "" false "Broken pipe")})

(def ^:private handler
  (interceptor
    {:name  ::handler
     :enter (fn [context]
              (let [body-fn (get bodies (get-in context [:request :uri]))]
                (assoc context :response {:status  200
                                          :headers {"Content-Type" "text/plain"
                                                    "X-Handler"    "yes"}
                                          :body    (body-fn)})))}))

(def ^:private connector-map
  (-> (conn/default-connector-map port)
      (conn/with-interceptor handler)))

(def ^:private error-text "Internal server error: exception")

(defn ^:private root-cause
  [t]
  (if-let [cause (ex-cause t)]
    (recur cause)
    t))

(defmacro ^:private capturing-logs
  "Evaluates body with the servlet interceptor's WARN and ERROR events captured; returns
  [result events], each event a map of :level, :msg and :cause (the message of the root cause)."
  [& body]
  `(let [*events# (atom [])
         record#  (fn [level# body# throwable#]
                    (swap! *events# conj {:level level#
                                          :msg   (second (re-find #":msg \"([^\"]*)\"" body#))
                                          :cause (some-> throwable# root-cause ex-message)}))
         logger#  #_:clj-kondo/ignore
                  (reify log/LoggerSource
                    (-level-enabled? [_# level#] (contains? #{:warn :error} level#))
                    (-warn [_# body#] (record# :warn body# nil))
                    (-warn [_# body# throwable#] (record# :warn body# throwable#))
                    (-error [_# body#] (record# :error body# nil))
                    (-error [_# body# throwable#] (record# :error body# throwable#)))
         result#  (mock/providing [(log/make-logger "io.pedestal.http.impl.servlet-interceptor") logger#
                                   (log/make-logger (m/any)) mock/fall-through]
                    ~@body)]
     [result# @*events#]))

(defn ^:private mock-get
  [servlet-kind path]
  (let [servlet (servlet-support/create-servlet connector-map {:servlet servlet-kind})]
    (servlet-support/response-for servlet :get path)))

(defmacro ^:private with-jetty
  [& body]
  `(let [connector# (jetty/create-connector connector-map nil)]
     (try
       (conn/start! connector#)
       ~@body
       (finally
         (conn/stop! connector#)))))

(defn ^:private jetty-get
  [path]
  (http/get (str "http://localhost:" port path) {:throw-exceptions false}))

(def ^:private servlet-kinds [:connector-servlet :fn-servlet])

(defn ^:private await-closed-channel
  "Waits (up to 2 seconds) for a tracked channel to be closed, which may happen after the client
  has received the response; returns true if it was."
  []
  (loop [remaining 100]
    (cond
      (pos? @*closed-channels) true
      (zero? remaining) false
      :else (do
              (Thread/sleep 20)
              (recur (dec remaining))))))

;; NIO bodies are written correctly

(deftest nio-bodies-through-a-servlet-response-that-is-not-jetty
  (doseq [servlet-kind servlet-kinds]
    (testing servlet-kind
      (is (match? {:status  200
                   :headers {"Content-Type" "text/plain"}
                   :body    "Hello ByteBuffer"}
                  (mock-get servlet-kind "/byte-buffer")))
      (reset! *closed-channels 0)
      (let [response (mock-get servlet-kind "/byte-channel")]
        (is (= 200 (:status response)))
        (is (= large-size (count (:body response))))
        (is (= large-body (:body response))))
      (is (= 1 @*closed-channels) "the channel is closed once written"))))

(deftest nio-bodies-through-jetty
  ;; Embedded Jetty keeps its own implementation, which sends the body without copying it.
  (with-jetty
    (is (match? {:status  200
                 :headers {"Content-Type" "text/plain"}
                 :body    "Hello ByteBuffer"}
                (jetty-get "/byte-buffer")))
    (reset! *closed-channels 0)
    (let [response (jetty-get "/byte-channel")]
      (is (= 200 (:status response)))
      (is (= large-body (:body response))))
    ;; Closed by Jetty and by Pedestal's callback, possibly after the client has the body.
    (is (await-closed-channel) "the channel is closed")))

;; A write that fails before anything is sent: 500

(deftest failure-before-anything-is-sent-through-the-mock
  (doseq [servlet-kind servlet-kinds
          [path cause] [["/failing-channel" "Channel read failed"]
                        ["/fn-fails-at-once" "Body write failed"]
                        ;; Written to the response buffer, but not sent: discarded by the reset.
                        ["/fn-fails-buffered" "Body write failed"]]]
    (testing (str servlet-kind " " path)
      (let [[response events] (capturing-logs (mock-get servlet-kind path))]
        (is (= 500 (:status response)))
        (is (= error-text (:body response)))
        (is (= "text/plain" (get-in response [:headers "Content-Type"])))
        (is (not (contains? (:headers response) "X-Handler"))
            "the reset drops the headers of the failed response")
        (is (match? [{:level :error :msg "error-stylobate triggered" :cause cause}]
                    events))))))

(deftest failure-before-anything-is-sent-through-jetty
  (with-jetty
    (doseq [path ["/fn-fails-at-once" "/fn-fails-buffered"]]
      (testing path
        (let [[response events] (capturing-logs (jetty-get path))]
          (is (= 500 (:status response)))
          (is (= error-text (:body response)))
          (is (not (contains? (:headers response) "X-Handler")))
          (is (match? [{:level :error :msg "error-stylobate triggered" :cause "Body write failed"}]
                      events)))))

    (testing "NIO body"
      (reset! *closed-channels 0)
      (let [[response events] (capturing-logs (jetty-get "/failing-channel"))]
        ;; Jetty closes its output when sending the channel fails: the 500 status and headers are sent,
        ;; but writing the error text fails, which is logged as a warning.
        (is (= 500 (:status response)))
        (is (not (string/includes? (:body response) "A")))
        (is (not (contains? (:headers response) "X-Handler")))
        (is (match? [{:level :error :msg "error-stylobate triggered" :cause "Channel read failed"}
                     {:level :warn :msg "Writing the error response failed"}]
                    events))
        (is (await-closed-channel) "the channel is closed")))))

(deftest analyzer-that-suppresses-the-exception-gets-no-500
  ;; When the exception analyzer returns nil, the stylobate neither logs the exception nor sends a 500:
  ;; the response is left as it was when writing the body failed.
  (let [servlet  (servlet-support/create-servlet connector-map {:exception-analyzer (constantly nil)})
        [response events] (capturing-logs (servlet-support/response-for servlet :get "/fn-fails-at-once"))]
    (is (= 200 (:status response)))
    (is (= "" (:body response)))
    (is (= [] events))))

(deftest broken-pipe-is-quietly-ignored
  ;; The default exception analyzer suppresses a broken pipe: nothing is logged, and no 500 is sent.
  (doseq [servlet-kind servlet-kinds]
    (testing servlet-kind
      (let [[response events] (capturing-logs (mock-get servlet-kind "/fn-broken-pipe"))]
        (is (= 200 (:status response)))
        (is (= "" (:body response)))
        (is (= [] events)))))
  (testing "through Jetty"
    (with-jetty
      (let [[response events] (capturing-logs (jetty-get "/fn-broken-pipe"))]
        (is (= 200 (:status response)))
        (is (= "" (:body response)))
        (is (= [] events))))))

;; A write that fails after part of the body was sent: the status was already sent, so the response stays a 200.

(deftest failure-after-the-response-is-committed-through-the-mock
  (doseq [servlet-kind servlet-kinds]
    (testing servlet-kind
      (let [[response events] (capturing-logs (mock-get servlet-kind "/fn-fails-after-flush"))]
        ;; The client keeps what was sent; nothing is appended, and the error is logged.
        (is (= 200 (:status response)))
        (is (= "PARTIAL" (:body response)))
        (is (= "yes" (get-in response [:headers "X-Handler"])))
        (is (match? [{:level :error :msg "error-stylobate triggered" :cause "Body write failed"}]
                    events))))))

(deftest failure-after-the-response-is-committed-through-jetty
  ;; Jetty then ends the response normally (for a chunked response, with the final empty chunk),
  ;; so the client can't tell that the body is incomplete.
  (with-jetty
    (testing "function body"
      (let [[response events] (capturing-logs (jetty-get "/fn-fails-after-flush"))]
        (is (= 200 (:status response)))
        (is (= "PARTIAL" (:body response)))
        (is (match? [{:level :error :msg "error-stylobate triggered" :cause "Body write failed"}]
                    events))))

    (testing "NIO body"
      (let [[response events] (capturing-logs (jetty-get "/channel-fails-late"))
            body (:body response)]
        (is (= 200 (:status response)))
        ;; What Jetty had sent before the failure: some of the bytes, never more.
        (is (< 0 (count body) (inc large-size)))
        (is (re-matches #"A+" body))
        (is (match? [{:level :error :msg "error-stylobate triggered" :cause "Channel read failed"}]
                    events))))))
