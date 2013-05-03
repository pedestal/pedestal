# Pedestal Changelog

**NOTE:** Whenever upgrading versions of pedestal-app, please be sure to clean your project's `out` directory by running `lein clean`.

## 0.1.6 - May 03, 2013

### Service

* Context path's now work with JBoss
* It is now possible to specify TCP port in routes (default: 8080). Specified ports will also be reflected in generated URL.

        (defroutes routes
          [[:app1 8080
            ["/" {:get app1-root}]]
           [:app2 8181
            ["/" {:get app2-root}]]]

### Miscellaneous bug-fixes and improvements

For a full list of changes, please see a [comparison](https://github.com/pedestal/pedestal/compare/0.1.5...0.1.6).

## 0.1.4/0.1.5 - April 05, 2013

We encountered a bug deploying version 0.1.4 so that release was re-done as version 0.1.5

### App

* `lein clean` now correctly deletes `out` directory.

### Service

* Added `text-as-html` and `data-as-json` interceptors.
* Newly generated service apps assume content is `text/html` if not specified otherwise.


### Miscellaneous bug-fixes and improvements

Special thanks to [@ddeaguiar](https://github.com/ddeaguiar) for grammar and
spelling corrections, as well as his help in removing
[lein-marginalia](https://github.com/fogus/lein-marginalia) as a dependency.
We've updated the [documentation](http://pedestal.io/documentation/) with
instructions on how to continue to generate marginalia documentation.

For a full list of changes, please see a
[comparison](https://github.com/pedestal/pedestal/compare/0.1.3...0.1.5).

## 0.1.3 - April 05, 2013

### App

* app-tools now serves every request with header Cache-Control: no-cache ([\#44](https://github.com/pedestal/pedestal/issues/44)) - [@rkneufeld](https://github.com/rkneufeld)

### Service

* Add CORS support to SSE and service template - [@timewald](https://github.com/timewald)

### Miscellaneous bug-fixes and improvements

For a full list of changes, please see a [comparison](https://github.com/pedestal/pedestal/compare/0.1.2...0.1.3).


## 0.1.2 - March 29, 2013

### General

* [Travis CI](https://travis-ci.org/pedestal/pedestal) integration has been enabled ([\#27](https://github.com/pedestal/pedestal/issues/27)) - [@cldwalker](https://github.com/cldwalker)
* lein repl history is now ignored by git ([\#38](https://github.com/pedestal/pedestal/issues/38)) - [@crimeminister](https://github.com/crimeminister)

### App

* app tests no longer have non-deterministic timing problems ([\#39](https://github.com/pedestal/pedestal/issues/39)) - [@brentonashworth](https://github.com/brentonashworth)
* app-tools now plays (more) nicely with Windows ([\#23](https://github.com/pedestal/pedestal/issues/23), [\#29](https://github.com/pedestal/pedestal/issues/29), [\#31](https://github.com/pedestal/pedestal/issues/31)) - [@djpowell](https://github.com/djpowell), [@rkneufeld](https://github.com/rkneufeld)

### Service

* Add support for Heroku ([\#18](https://github.com/pedestal/pedestal/issues/18)) - [@aredington](https://github.com/aredington), [@cldwalker](https://github.com/cldwalker), [@hiredman](https://github.com/hiredman), [@timewald](https://github.com/timewald)
* An HTTP status is now sufficient to bypass the not-found interceptor (whereas it used to require headers) ([\#30](https://github.com/pedestal/pedestal/issues/30), [\#34](https://github.com/pedestal/pedestal/issues/34)) - [@hiredman](https://github.com/hiredman), [@rkneufeld](https://github.com/rkneufeld)
* Removed a number of incorrect :or keys ([\#32](https://github.com/pedestal/pedestal/issues/32)) - [@cldwalker](https://github.com/cldwalker)
* Usage of 'read-string' has been audited and corrected ([\#40](https://github.com/pedestal/pedestal/issues/40)) - [@stuartsierra](https://github.com/stuartsierra)
* io.pedestal.service.http.body-params/set-content-type now uses correct casing for HTTP headers ([\#35](https://github.com/pedestal/pedestal/issues/35)) - [@stuarth](https://github.com/stuarth)
