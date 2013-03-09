(ns {{name}}.html-templates
  (:use [io.pedestal.app.templates :only [tfn dtfn tnodes]]))

(defmacro {{name}}-templates
  []
  {:{{name}}-page (dtfn (tnodes "{{name}}.html" "hello") #{:id})})
