Copyright
---------
Copyright 2013 Relevance, Inc.

# Pedestal Application template

Generate a new Pedestal Application.


## Usage

From within this project run:

```bash
lein install
```

Add a new dependency to your Leiningen user profile in `~/.lein/profiles.clj`:

```clj
{:user {:plugins {pedestal-app/lein-template "0.0.9-SNAPSHOT"}}}
```

When you are ready to create a new project, run:

```bash
lein new pedestal-app example
cd example
lein repl
```

where `example` is the project name.

At the REPL type:

```clj
(run)
```

and then open a browser to `http://localhost:3000`. 

You now have a working development environment!
