; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.resources-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [io.pedestal.http.resources :as resources]
            [io.pedestal.http.route :as route]))

(defn service-map
  [xf]
  {::http/port   8888
   ::http/routes (route/routes-from
                   (resources/resource-routes (xf {:resource-root "io/pedestal/public"
                                                   :prefix        "/res"}))
                   (resources/file-routes (xf {:file-root "file-root"
                                               :prefix    "/file"})))})
(defn create-responder
  ([] (create-responder identity))
  ([xf]
   (-> (service-map xf)
       test/create-responder)))

(deftest get-resource-that-exists
  (let [responder (create-responder)]
    (is (match? {:status  200
                 :headers {"Content-Type"   "text/html"
                           "Content-Length" 15}
                 :body    "<h1>WOOT!</h1>\n"}
                (responder :get "/res/index.html")))))

(deftest ignores-extra-slashes-in-path
  (let [responder (create-responder)]
    (is (match? {:status  200
                 :headers {"Content-Type"   "text/html"
                           "Content-Length" 15}
                 :body    "<h1>WOOT!</h1>\n"}
                (responder :get "/res//index.html")))))

(deftest head-resource-that-exists
  (let [responder (create-responder)]
    (is (match? {:status  200
                 :headers {"Content-Type"   "text/html"
                           "Content-Length" 15}
                 :body    ""}
                (responder :head "/res/index.html")))))

(deftest get-resource-when-path-includes-..-returns-404
  (let [responder (create-responder)]

    (is (match? {:status 404}
                (responder :get "/res/../res/index.html")))))

(deftest get-resource-does-not-exist
  (let [responder (create-responder)]
    (is (match? {:status 404}
                (responder :get "/res/not-present.txt")))))

(deftest get-resource-in-sub-folder
  (let [responder (create-responder)
        content   (slurp "test/io/pedestal/public/sub/sub.html")]

    (is (match? {:status 200
                 :body   content}
                (responder :get "/res/sub/sub.html")))

    (is (match? {:status 200
                 :body   content}
                (responder :get "/res//sub///sub.html")))))

(deftest prefix-must-start-with-slash
  (is (thrown? AssertionError
               (resources/resource-routes {:prefix "not-this"}))))

(deftest prefix-must-not-end-with-slash
  (is (thrown? AssertionError
               (resources/resource-routes {:prefix "/public/"}))))

(deftest file-root-must-not-end-with-slash
  (is (thrown? AssertionError
               (resources/file-routes {:file-root "this/and/that/"}))))


(deftest get-file-that-exists
  (let [responder         (create-responder)
        test-file-content (slurp "file-root/test.html")]
    (is (match? {:status  200
                 :headers {"Content-Type"   "text/html"
                           "Content-Length" (count test-file-content)}
                 :body    test-file-content}
                (responder :get "/file/test.html")))))

(deftest head-of-file-that-exists
  (let [responder         (create-responder)
        test-file-content (slurp "file-root/test.html")]
    (is (match? {:status  200
                 :headers {"Content-Type"   "text/html"
                           "Content-Length" (count test-file-content)}
                 :body    ""}
                (responder :head "/file/test.html")))))

(deftest head-of-existing-file-when-not-enabled-returns-404
  (let [responder (create-responder #(assoc % :allow-head? false))]
    (is (match? {:status 404}
                (responder :head "/file/test.html")))))


(deftest get-index-for-root-dir
  (let [responder          (create-responder)
        index-file-content (slurp "file-root/index.html")]
    (is (match? {:status  200
                 :headers {"Content-Length" (count index-file-content)}
                 :body    index-file-content}
                (responder :get "/file")))))

(deftest get-index-file-with-htm-extension
  (let [responder (create-responder)
        content   (slurp "file-root/sub/a/index.htm")]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/file/sub/a")))))

(deftest get-index-file-with-other-extension
  (let [responder (create-responder)
        content   (slurp "file-root/sub/b/index.txt")]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/file/sub/b")))))

(deftest status-404-if-no-index-file-matches
  (let [responder (create-responder)]
    (is (match? {:status 404}
                (responder :get "/file/sub/c")))))

(deftest get-file-when-path-contains-..-returns-404
  (let [responder (create-responder)]

    (is (match? {:status 404}
                (responder :get "/file/../res/index.html")))))

(deftest get-file-in-sub-directory
  (let [responder   (create-responder)
        sub-content (slurp "file-root/sub/sub.html")]
    (is (match? {:status 200
                 :body   sub-content}
                (responder :get "/file/sub/sub.html")))))

(deftest get-file-index-on-sub-directory
  (let [responder         (create-responder)
        sub-index-content (slurp "file-root/sub/index.html")]
    (is (match? {:status 200
                 :body   sub-index-content}
                (responder :get "/file/sub")))))

(deftest get-file-that-does-not-exist
  (let [responder (create-responder)]
    (is (match? {:status 404}
                (responder :get "/file/not-present.txt")))))


(deftest extra-slashes-in-file-path
  (let [responder         (create-responder)
        test-file-content (slurp "file-root/test.html")
        sub-file-content  (slurp "file-root/sub/sub.html")]

    (testing "ignores extra slashes in root path"
      (is (match? {:status 200
                   :body   test-file-content}
                  (responder :get "/file////test.html"))))

    (testing "ignores extra slashes in sub path"
      (is (match? {:status 200
                   :body   sub-file-content}
                  (responder :get "/file//sub/////sub.html"))))))

(deftest no-index-files-if-disabled
  (let [responder (create-responder #(assoc % :index-files? false))]

    (is (match? {:status 404}
                (responder :get "/file")))

    (is (match? {:status 404}
                (responder :get "/file/sub")))))

;; Testing different paths through the code, there isn't a good way to
;; see inside the black box, however.

(deftest access-large-resource-from-jar
  (let [responder (create-responder #(assoc % :resource-root "com/cognitect"))
        ;; This is a convenient, largish file (large enough to be streamed async).
        content   (-> "com/cognitect/transit/TransitFactory.class" io/resource slurp)]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/res/transit/TransitFactory.class")))))

(deftest access-small-resource-from-jar
  (let [responder (create-responder #(assoc % :resource-root "ring"))
        ;; This is the other branch, where the InputStream for the file is returned rather
        ;; than the ReadableChannel.  This file is ~350 bytes, well below the 1500 buffer size
        ;; provided by the test-servlet-response.
        content   (-> "ring/util/async.clj" io/resource slurp)]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/res/util/async.clj")))))

(deftest get-large-file
  (let [responder (create-responder)
        content   (slurp "file-root/sub/image.jpg")]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/file/sub/image.jpg")))))

(deftest get-large-resource-from-jar-slow
  (let [responder (create-responder #(assoc %
                                            :resource-root "com/cognitect"
                                            :fast? false
                                            ;; Turn off a few other options just to boost
                                            ;; code coverage
                                            :cache? false
                                            :index-files? false
                                                          :allow-head? false))
        content   (-> "com/cognitect/transit/TransitFactory.class" io/resource slurp)]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/res/transit/TransitFactory.class")))))

(deftest get-large-file-slow
  (let [responder (create-responder #(assoc % :fast? false
                                            :cache? false))
        content   (slurp "file-root/sub/image.jpg")]
    (is (match? {:status 200
                 :body   content}
                (responder :get "/file/sub/image.jpg")))))
