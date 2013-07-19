; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Make network requests."}
  io.pedestal.app.net.xhr
  (:require [goog.net.XhrManager :as manager]
            [goog.structs.Map :as map]
            [clojure.string :as string]))

(def ^:private
  *xhr-manager*
  (goog.net.XhrManager. nil
                        nil
                        nil
                        6
                        (* 60 1000)))

(defn success?
  [{status :status}]
  (or (and (>= status 200)
           (<  status 300))
      (= status 304)))

(defn redirect?
  [{status :status}]
  (boolean (#{301 302 303 307} status)))

(defn error?
  [{status :status}]
  (>= status 400))

(defn- headers->map
  [xhr]
  (let [headers (-> xhr
                    .getAllResponseHeaders
                    string/lower-case
                    string/trim
                    string/split-lines)]
    (try (into {}
               (map #(string/split % #"\s*:\s+")
                    headers))
         (catch js/Error e {}))))

(defn- handle-response
  [on-success on-error id e]
  (let [xhr      (.-currentTarget e)
        response {:id      id
                  :body    (.getResponseText xhr)
                  :status  (.getStatus xhr)
                  :headers (headers->map xhr)
                  :xhr     xhr}
        handler  (if (success? response)
                   on-success
                   on-error)]
    (handler response)))

(defn request
  "Asynchronously make a network request for the resource at uri. If
  provided via the `:on-success` and `:on-error` keyword arguments,
  the appropriate one of `on-success` or `on-error` will be called on
  completion. They will be passed a map containing the keys `:id`,
  `:body`, `:status`, and `:event`. The entry for `:event` contains an
  instance of the `goog.net.XhrManager.Event`.

  Other allowable keyword arguments are `:request-method`, `:body`,
  `:headers`, `:priority`, and `:retries`. `:request-method` defaults
  to \"GET\" and `:retries` defaults to `0`.

  `priority` defaults to 100. The lower the number the higher the
  priority."
  [id uri & {:keys [request-method body headers priority retries
                    on-success on-error]
             :or   {request-method   "GET"
                    retries  0}}]
  (assert on-success "on-success keyword argument is required")
  (assert on-error "on-error keyword argument is required")
  (try
    (.send *xhr-manager*
           id
           uri
           request-method
           body
           (when headers (clj->js headers))
           priority
           (partial handle-response on-success on-error id)
           retries)
    (catch js/Error e
      (.log js/console e)
      nil)))

(defn url
  [path]
  (str (.-origin (.-location js/document)) path))
