; Copyright 2024-2025 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.ring-middlewares
  "This namespace creates interceptors for ring-core middlewares.

  Ring provides a trove of useful and familiar functionality; this namespace exposes that functionality
  as interceptors that work with Pedestal.

  In some cases, some or all of the Ring middleware has been reimplemented here."
  (:require [clojure.java.io :as io]
            [io.pedestal.http.params :as pedestal-params]
            [io.pedestal.interceptor :refer [interceptor]]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.file :as file]
            [ring.middleware.file-info :as file-info]
            [ring.middleware.flash :as flash]
            [ring.middleware.multipart-params :as multipart-params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [ring.middleware.session :as session]
            [ring.util.mime-type :as mime]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp]
            [io.pedestal.http.tracing :as tracing])
  (:import (jakarta.servlet.http HttpServletResponse)
           (java.nio.channels FileChannel)
           (java.nio.file OpenOption
                          StandardOpenOption)
           (java.io File)))

(defn- response-fn->leave
  [response-fn & args]
  (fn [context]
    (let [{:keys [request response]} context]
      (cond-> context
        response (assoc :response (apply response-fn response request args))))))

(defn- leave-interceptor
  "Defines a leave only interceptor given a response fn.

  The function is passed the request, the response, and any additional args.

  The function is only invoked if the context contains a response."
  [name response-fn & args]
  (interceptor
    {:name  name
     :leave (apply response-fn->leave response-fn args)}))

(defn- content-type-response
  "Tries adding a content-type header to response by request URI (unless one
  already exists)."
  [resp req mime-types]
  (let [content-type (get-in resp [:headers "Content-Type"])]
    (if content-type
      resp
      (let [mime-type (mime/ext-mime-type (:uri req) mime-types)]
        (cond-> resp
          mime-type (assoc-in [:headers "Content-Type"] mime-type))))))

(defn content-type
  "Applies a Content-Type header to a response if missing by mapping the
  file name extension in the request's URI.

  The MIME mapping occurs in the function ring.util.mime-type/ext-mime-type.

  The opts arguments are key/value pairs; the :mime-types key is a map
  of overrides for extensions to MIME type mappings."
  [& [opts]]
  (leave-interceptor ::content-type-interceptor content-type-response (:mime-types opts)))

(defn- middleware
  ([interceptor-name request-fn]
   (middleware interceptor-name request-fn nil))
  ([interceptor-name request-fn response-fn]
   (interceptor
     (cond-> {:name interceptor-name}
       request-fn (assoc :enter #(update % :request request-fn))
       response-fn (assoc :leave #(update % :response response-fn))))))

(def cookies
  "Add support for HTTP cookies.  On :enter, a :cookies key is added to the request map, containing
  the parsed cookie data (from the \"cookie\" HTTP header), as a map from cookie name to cookie data;
  each cookie is itself a map  with key :value.

  This is a wrapper around the ring.middleware.cookies namespace.

  When the response map contains a :cookies key, a \"Set-Cookie\" header will be added to
  propagate cookie data back to the client."
  (middleware ::cookies
              cookies/cookies-request
              cookies/cookies-response))

(defn file
  "Allow file-system files to be accessed as static resources.  On :enter, if a static file can be found
  that matches incoming request URI, a response is generated from its content.  Since the file interceptor usually
  ordered before any routing interceptor, this means that such files can mask other application routes.

  The interceptor supports both GET and HEAD requests.

  The underlying support comes from ring.middleware.file/file-request.

  The :body key of the response will be a java.io.File.

  If succesful, marks the current tracing span as routed, with a route-name of :file.

  Options are specified as key/value pairs after the root-path.

  Common options are :index-files? (defaults to true) which maps directory requests to requests for
  an index file (if present),
  and :allow-symlinks? (defaults to false) which allows symbolic links to be followed rather than ignored."
  [root-path & [opts]]
  (interceptor
    {:name  ::file
     :enter (fn [context]
              (let [response (file/file-request (:request context) root-path opts)]
                (if-not response
                  context
                  (-> context
                      (assoc :response response)
                      (tracing/mark-routed :file)))))}))

(defn file-info
  "An interceptor that, on :leave, will check the request's \"if-modified-since\" headed
   and convert the response into a status 304 if the underlying file (the :body of the response,
   a java.io.File) has not been modified since the specified date.  It will also set the \"Content-Type\" response
   header.  The :mime-types option can be provided, it works the same here as in the [[content-type]] interceptor.

   See ring.middleware.file-info/file-info-response for more details."
  [& [mime-types]]
  (leave-interceptor ::file-info file-info/file-info-response mime-types))

(defn flash
  "Support temporary data (the \"flash\") in the session (see [[session]]).

  On :leave, the :flash key of the response is stored into the session.

  On :enter, the previously stored flash value, if any, is removed from the session and added as the request :flash key."
  []
  (interceptor
    {:name  ::flash
     :enter #(update % :request flash/flash-request)
     :leave (response-fn->leave flash/flash-response)}))

(defn head
  "Interceptor to handle head requests

  On :enter, when the request method is :head, the request method is converted to :get.
  On :leave, for a :head request, the response :body is set to nil."
  []
  (interceptor {:name  ::head
                :enter (fn [ctx]
                         (if (= :head (get-in ctx [:request :request-method]))
                           (-> ctx
                               (assoc ::head-request? true)
                               (assoc-in [:request :request-method] :get))
                           ctx))
                :leave (fn [{:keys [response] :as ctx}]
                         (if (and response (::head-request? ctx))
                           (update ctx :response assoc :body nil)
                           ctx))}))

(def keyword-params
  "Retained for backward compatibility. io.pedestal.http.params/keyword-params is recommended"
  pedestal-params/keyword-params)

(defn multipart-params
  "Interceptor for multipart form parameters (i.e., forms with file uploads).

  A wrapper around ring.middleware.multipart-params/multipart-params-request.

  This will add a :multipart-params key to the request, and merge the multipart parameters
  into the request :params map."
  [& [opts]]
  (middleware ::multipart-params
              #(multipart-params/multipart-params-request % opts)))

(defn nested-params
  "Interceptor for ring.middleware.nested-params/nested-params-request Ring middleware.

  Nested parameter names follow a particular naming pattern, the result is that the :params
  may of the request is converted to a nested map."
  [& [opts]]
  (middleware ::nested-params #(nested-params/nested-params-request % opts)))

(defn not-modified
  "Adds support for the  \"if-modified-since\" and \"if-none-match\" request headers; generally
   this applies to responses generated via the [[file]] or [[resource]] interceptors.

   This is a wrapper around ring.middleware.not-modified/not-modified-response."
  []
  (leave-interceptor ::not-modified not-modified/not-modified-response))

(defn params
  "Extract query parameters from the request URI and request body and adds a :query-params and
  :form-params keys to the request, and merges those maps into the request :params map.

  This is a wrapper around ring.middleware.params/params-request."
  [& [opts]]
  (middleware ::params #(params/params-request % opts)))

(defn resource
  "Allows access to static resources on the classpath.

  This is a wrapper around ring.middleware.resource/resource-request.

  If succesful, marks the current tracing span as routed, with a route-name of :resource

  The response :body may be a java.io.InputStream or a java.io.File depending on the request and the classpath."
  [root-path]
  (interceptor
    {:name  ::resource
     :enter (fn [context]
              (let [{:keys [request]} context
                    response (resource/resource-request request root-path)]
                (if-not response
                  context
                  (-> context
                      (assoc :response response)
                      (tracing/mark-routed :resource)))))}))

(defn fast-resource
  "Fast access to static resources from the classpath; essentially works like the [[resource]] interceptor, but
  the response :body will be a java.nio.channels.FileChannel that can be streamed to the client
  asynchronously.

  A file is large if it is larger than the HTTP buffer size, which is calculated from
  the servlet-response's bufferSize, or defaults to 1460 bytes (if the servlet response is not known).

  If successful, marks the current tracing span as routed, with a route-name of :fast-resource.

  If your container doesn't recognize FileChannel response bodies, this interceptor will cause errors.

  Supports a map of options:

  :index? - If path is a directory, will attempt to find an 'index.*' file to serve. Defaults to true
  :follow-symlinks? - Serve files through symbolic links. Defaults to false
  :loader - A class loader specific for these resource fetches. Default to nil (use the main class loader)."
  ([root-path]
   (fast-resource root-path {:index?          true
                             :allow-symlinks? false
                             :loader          nil}))
  ([root-path opts]
   (let [{:keys [loader]} opts]
     (interceptor
       {:name  ::fast-resource
        :enter (fn [context]
                 (let [{:keys [request]} context
                       {:keys [^HttpServletResponse servlet-response uri path-info request-method]} request]
                   (if (#{:head :get} request-method)
                     (let [buffer-size-bytes (if servlet-response
                                               (.getBufferSize servlet-response)
                                               ;; let's play it safe and assume 1500 MTU
                                               1460)
                           uri-path          (subs (codec/url-decode (or path-info uri)) 1)
                           path              (-> (str (or root-path "") "/" uri-path)
                                                 (.replace "//" "/")
                                                 (.replaceAll "^/" ""))
                           resource          (if loader
                                               (io/resource path loader)
                                               (io/resource path))
                           file-resp         (and resource
                                                  (ring-resp/file-response (.getAbsolutePath ^File (io/as-file resource))
                                                                           opts))
                           response          (and file-resp
                                                  (if (>= buffer-size-bytes
                                                          ;; TODO: Nothing like losing the data to private functions
                                                          ;;  - rewrite the above to do the file lookup and response generation directly
                                                          (Long/parseLong (get-in file-resp [:headers "Content-Length"])))
                                                    file-resp
                                                    (assoc file-resp
                                                           :body (FileChannel/open (.toPath ^File (:body file-resp))
                                                                                   (into-array OpenOption [StandardOpenOption/READ])))))]
                       (if response
                         (-> context
                             (assoc :response response)
                             (tracing/mark-routed :fast-resource))
                         context))
                     context)))}))))

;; Ugly access to private function:

(def ^:private session-options #'session/session-options)

(defn session
  "Interceptor for session ring middleware. A session is a simple store of data, associated with a single client,
  that persists between requests.  A cookie (by default \"ring-session\") is used to connect requests and responses
  to a session.  A store (the default is an in-memory Atom) stores the data between requests.

  The request key :session is a map storing the session data, and :session/key store the key uniquely identifying the client session.

  Options are documented in ring.middleware.session/wrap-session.

  On :enter, uses ring.middleware.session/session-request, which adds a :session key to the request.

  On :leave, uses the :session and :session/key response keys to update the store and, if necessary, create a new
  cookie with the new session key.

  It is the application's responsibility to copy the :session and :session/key to the response. When this does not occur,
  the session will be removed from the store."
  ([] (session {}))
  ([options]
   (let [options (session-options options)]
     (interceptor {:name  ::session
                   :enter (fn [context] (update context :request session/session-request options))
                   :leave (response-fn->leave session/session-response options)}))))
