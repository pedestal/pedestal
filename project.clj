(defproject io.pedestal/pedestal "0.0.1-SNAPSHOT"
  :plugins [[lein-sub "0.2.3"]]
  :sub ["service"
        "jetty"
        "tomcat"
        "service-template"
        "app"
        "app-tools"
        "app-template"])
