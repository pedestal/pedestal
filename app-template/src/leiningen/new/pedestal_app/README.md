# {{name}}

Start working on this application by writing its behavior in the file
`app/src/{{sanitized}}/behavior.clj`


## Usage

`cd` into any directory and execute the following:

```bash
lein new pedestal-app my-project
cd my-project
lein repl
```

The `io.pedestal.app-tools.dev` namespace is loaded by default. It contains
several useful functions. To see a list of some of these functions, type:

```clj
(tools-help)
```

To begin working on an application, execute:

```clj
(start)
```

and then visit `http://localhost:3000`.

Alternatively, start the app server from the command line: `lein run`.

During development of an application, sources will be compiled
on-demand. Sources include everything located in the `app`
directory. All compiled output goes to `out/public`. The contents of
`out/public` are transient and the `out` directory can be deleted at
any time to trigger a complete re-build.

The contents of `out/public` are the deployment artifacts for this
project.

If you would like to serve the contents of `out/public` from another
server and not run the development server. Run:

```clj
(watch :development)
```

from the application project to automatically build the `:development`
environment when sources change.


## Links

* [Overview of how pedestal-app works](http://pedestal.io/documentation/application-overview/)
* [Comprehensive tutorial for pedestal-app](https://github.com/pedestal/app-tutorial)
