Copyright
---------
Copyright 2013 Relevance, Inc.

# Pedestal Applications

Follow these instructions to create a new Pedestal Application project
based on these libraries. This project requires [Leiningen 2.0](https://github.com/technomancy/leiningen).

```bash
git clone git@github.com:relevance/platform.git
cd platform/app
lein install
cd ../app-tools
lein install
cd ../app-template
lein install
```

This will install the current version of each library into your local
Maven repository. One of the installed libraries is a Leiningen 'new'
template for creating a new application. Add this plugin to your
Leiningen user profiles in `~/.lein/profiles.clj`. You may need to
create this file if this is your first project.

```clj
{:user {:plugins [[pedestal-app/lein-template "0.0.8-SNAPSHOT"]]}}
```

## Usage

`cd` into any directory and execute the following:

```bash
lein new pedestal-app my-project
cd my-project
lein repl
```

The `user` namespace contains several useful functions. To see a list
of some of these functions, type:

```clj
(tools-help)
```

To begin working on an application, execute:

```clj
(run)
```

and then visit `http://localhost:3000`.

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
