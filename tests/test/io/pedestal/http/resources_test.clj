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
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [io.pedestal.http.resources :as resources]
            [io.pedestal.http.route :as route]
            [matcher-combinators.matchers :as m]))

(defn service-map
  [xf]
  {::http/port   8888
   ::http/routes (route/routes-from
                   (resources/resource-routes (xf {:resource-root "io/pedestal/public"
                                                   :fast?         false
                                                   :prefix        "/pub"}))
                   (resources/file-routes (xf {:file-root "file-root"
                                               :fast?     false
                                               :prefix    "/file"})))})
(defn create-responder
  ([] (create-responder identity))
  ([xf]
   (-> (service-map xf)
       test/create-responder)))

(deftest access-public-resource
  (let [responder (create-responder)]
    (testing "GET"
      (is (match? {:status  200
                   :headers {"Content-Type"   "text/html"
                             "Content-Length" 15}
                   :body    "<h1>WOOT!</h1>\n"}
                  (responder :get "/pub/index.html"))))

    (testing "ignored extra slashes in path"
      (is (match? {:status  200
                   :headers {"Content-Type"   "text/html"
                             "Content-Length" 15}
                   :body    "<h1>WOOT!</h1>\n"}
                  (responder :get "/pub//index.html"))))

    (testing "HEAD"
      (is (match? {:status  200
                   :headers {"Content-Type"   "text/html"
                             "Content-Length" 15}
                   :body    ""}
                  (responder :head "/pub/index.html"))))


    (testing "Can't use .. in path"
      (is (match? {:status  404
                   :headers {"Content-Type"   "text/plain"
                             "Content-Length" m/absent
                             "Last-Modified"  m/absent}
                   :body    ""}
                  (responder :get "/pub/../pub/index.html"))))

    (testing "Not found"
      (is (match? {:status 404
                   :body   ""}
                  (responder :get "/pub/not-present.txt"))))))

(deftest resource-in-sub-folder
  (let [responder (create-responder)
        content   (slurp "test/io/pedestal/public/sub/sub.html")]

    (is (match? {:status 200
                 :body   content}
                (responder :get "/pub/sub/sub.html")))

    (is (match? {:status 200
                 :body   content}
                (responder :get "/pub//sub///sub.html")))))

(deftest prefix-must-start-with-slash
  (is (thrown? AssertionError
               (resources/resource-routes {:prefix "not-this"}))))

(deftest prefix-must-not-end-with-slash
  (is (thrown? AssertionError
               (resources/resource-routes {:prefix "/public/"}))))

(deftest file-root-must-not-end-with-slash
  (is (thrown? AssertionError
               (resources/file-routes {:file-root "this/and/that/"}))))


(deftest access-public-file
  (let [responder          (create-responder)
        test-file-content  (slurp "file-root/test.html")
        index-file-content (slurp "file-root/index.html")
        sub-content        (slurp "file-root/sub/sub.html")
        sub-index-content  (slurp "file-root/sub/index.html")]
    (testing "GET"
      (is (match? {:status  200
                   :headers {"Content-Type"   "text/html"
                             "Content-Length" (count test-file-content)}
                   :body    test-file-content}
                  (responder :get "/file/test.html"))))

    (testing "HEAD"
      (is (match? {:status  200
                   :headers {"Content-Type"   "text/html"
                             "Content-Length" (count test-file-content)}
                   :body    ""}
                  (responder :head "/file/test.html"))))

    (testing "index.html access"
      (is (match? {:status  200
                   :headers {"Content-Length" (count index-file-content)}
                   :body    index-file-content}
                  (responder :get "/file"))))

    (testing "Can't use .. in path"
      (is (match? {:status  404
                   :headers {"Content-Type"   "text/plain"
                             "Content-Length" m/absent
                             "Last-Modified"  m/absent}
                   :body    ""}
                  (responder :get "/file/../pub/index.html"))))

    (testing "Access to sub-directory"
      (is (match? {:status 200
                   :body   sub-content}
                  (responder :get "/file/sub/sub.html"))))


    (testing "index on sub-directory"
      (is (match? {:status 200
                   :body   sub-index-content}
                  (responder :get "/file/sub"))))

    (testing "Not found"
      (is (match? {:status 404
                   :body   ""}
                  (responder :get "/file/not-present.txt"))))))

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
