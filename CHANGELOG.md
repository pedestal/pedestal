# Pedestal Changelog

**NOTE:** Whenever upgrading versions of pedestal-app, please be sure to clean your project's `out` directory by running `lein clean`.

## 0.1.9 - June 14, 2013

### General

* All Pedestal libraries now properly depend on Clojure 1.5.1.

### App

* The dataflow engine now properly reports changes when nodes have nil or falsey values. [#78](https://github.com/pedestal/pedestal/pull/78)
* Messages that throw exceptions during processing now log an error message.
* Templates can now insert content at a specific index with `io.pedestal.app.render.push.templates/insert-t`. [#81](https://github.com/pedestal/pedestal/pull/81)
* Generated `dev/dev.clj` now uses `(start)` instead of `(run)`, bringing it in line with pedestal-service. [#84](https://github.com/pedestal/pedestal/pull/84)

### Service

* You can now pass a `:fragment` option to `url-for` indicating the fragment identifier for that URL (i.e. "foo" of "http://example.com/things#foo".) [#85](https://github.com/pedestal/pedestal/pull/85)

### Miscellaneous bug-fixes and improvements

For a full list of changes, please see this comparison of [0.1.8...0.1.9](https://github.com/pedestal/pedestal/compare/0.1.8...0.1.9).

## 0.1.8 - May 29, 2013

### General

* App and service templates now allow creating projects with namespaces [#68](https://github.com/pedestal/pedestal/issues/68).
            
            $ lein new pedestal-app com.example/foo
            ... creates foo/ with src/com/example/foo/*.clj

### Service

* Corrected a test error in the generated service template project.

### Miscellaneous bug-fixes and improvements

For a full list of changes, please see this comparison of [0.1.7...0.1.8](https://github.com/pedestal/pedestal/compare/0.1.7...0.1.8).

## 0.1.7 - May 24, 2013

### App

* The new simplified dataflow engine is here! We're working to update our documentation and samples now. Stay informed by following [@pedestal_team](http://twitter.com/pedestal_team) on twitter.
    * Existing applications will continue to function normally.
* `io.pedestal.app.templates/dtfn` now allows for more than one data field [#60](https://github.com/pedestal/pedestal/issues/60).

### Service

* Empty EDN responses are now handled gracefully [#69](https://github.com/pedestal/pedestal/pull/69).
* Resources for services can now be drawn from `resources/` [#51](https://github.com/pedestal/pedestal/pull/51).
* Typo fixes in HTML and JSON interceptors [#72](https://github.com/pedestal/pedestal/pull/72).
* Corrected a few places the `Content-Type` header was not being set properly [#58](https://github.com/pedestal/pedestal/issues/58), [#65](https://github.com/pedestal/pedestal/pull/65).


### Miscellaneous bug-fixes and improvements

For a full list of changes, please see this comparison of [0.1.6...0.1.7](https://github.com/pedestal/pedestal/compare/0.1.6...0.1.7).


## 0.1.6 - May 03, 2013

### Service

* Context paths now work with JBoss
* It is now possible to specify TCP port in routes (default: 8080). Specified ports will also be reflected in generated URL.

        (defroutes routes
          [[:app1 8080
            ["/" {:get app1-root}]]
           [:app2 8181
            ["/" {:get app2-root}]]]

### Miscellaneous bug-fixes and improvements

For a full list of changes, please see a comparison of [0.1.5...0.1.6](https://github.com/pedestal/pedestal/compare/0.1.5...0.1.6).

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

For a full list of changes, please see a comparison of
[0.1.3...0.1.5](https://github.com/pedestal/pedestal/compare/0.1.3...0.1.5).

## 0.1.3 - April 05, 2013

### App

* app-tools now serves every request with header Cache-Control: no-cache ([\#44](https://github.com/pedestal/pedestal/issues/44)) - [@rkneufeld](https://github.com/rkneufeld)

### Service

* Add CORS support to SSE and service template - [@timewald](https://github.com/timewald)

### Miscellaneous bug-fixes and improvements

For a full list of changes, please see a comparison of [0.1.2...0.1.3](https://github.com/pedestal/pedestal/compare/0.1.2...0.1.3).


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
