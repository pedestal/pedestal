(ns override-logging.server
  "Houses the application entry point for the deployed application.
  The implementation is moved to a separate namespace so that
  transitive dependencies are not AOT-compiled otherwise we will
  not be able to override the logger at runtime."
  (:gen-class :impl-ns override-logging.server-impl)) ;; Provides the `-main` impl for the uberjar
