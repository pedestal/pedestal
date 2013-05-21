(ns config
  (:require [net.cgrand.enlive-html :as html]
            [io.pedestal.app-tools.compile :as compile]))

(def configs
  {:{{name}}
   {:build {:watch-files (compile/html-files-in "app/templates")
            :triggers {:html [#"{{sanitized}}/rendering.js"]}}
    :application {:generated-javascript "generated-js"
                  :default-template "application.html"
                  :output-root :public}
    :control-panel {:design {:uri "/design.html"
                             :name "Design"
                             :order 0}}
    :built-in {:render {:dir "{{name}}"
                        :renderer '{{sanitized}}.rendering
                        :logging? true
                        :order 2
                        :menu-template "tooling.html"}}
    :aspects {:data-ui {:uri "/{{name}}-data-ui.html"
                        :name "Data UI"
                        :order 1
                        :out-file "{{name}}-data-ui.js"
                        :main '{{sanitized}}.simulated.start
                        :recording? true
                        :logging? true
                        :output-root :tools-public
                        :template "tooling.html"}
              :development {:uri "/{{name}}-dev.html"
                            :name "Development"
                            :out-file "{{name}}-dev.js"
                            :main '{{sanitized}}.start
                            :logging? true
                            :order 3}
              :fresh {:uri "/fresh.html"
                      :name "Fresh"
                      :out-file "fresh.js"
                      :main 'io.pedestal.app.net.repl_client
                      :order 4
                      :output-root :tools-public
                      :template "tooling.html"}
              :production {:uri "/{{name}}.html"
                           :name "Production"
                           :optimizations :advanced
                           :out-file "{{name}}.js"
                           :main '{{sanitized}}.start
                           :order 5}}}})
