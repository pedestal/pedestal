# Deploying a Pedestal application to Bluemix

## Create the pedestal app

If you haven't already, create a Pedestal application with Leiningen:

```
lein new pedestal-service the-next-big-server-side-thing
```

## Make it use `VCAP_APP_PORT` as the server port

Bluemix apps have to bind to the port provided in the `VCAP_APP_PORT` environment variable. Otherwise, Bluemix won't know how to talk to the app and will report that the app failed to start.

Edit `service.clj` and replace 

```
::bootstrap/port 8080})
```

with

```
::bootstrap/port (Integer. 
                   (let [port (System/getenv "VCAP_APP_PORT")]
                     (or port "8080") ))})
```

### Add a manifest

Add a `manifest.yml` file to your project directory:

```
---
applications:
- name: application-name
  memory: 512M
  host: host-name
  stack: cflinuxfs2
  instances: 1
  path: .
  buildpack: https://github.com/heroku/heroku-buildpack-clojure.git
```

This tells Bluemix to use a custom buildpack for Clojure and the `cflinuxfs2` stack, which gives us Ubuntu 14.04 instead of the default 10.04. Can you believe Bluemix is still using 10.04 by default? [That's not even supported anymore!](https://wiki.ubuntu.com/Releases)

### Login to Bluemix and push the app

Login, push...

```
cf login
cf push
```

... and go to `host-name.ng.mybluemix.net`. 
