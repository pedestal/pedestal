# Pedestal Application template

Generate a new Pedestal Application.


## Usage

From within this project run:

```bash
lein install
```

To create a new project run:

```bash
# Generate a project with introductory comments
lein new pedestal-app example
# Alternatively, generate a project without comments
lein new pedestal-app example no-comment

cd example
lein repl
```

where `example` is the project name.

At the REPL type:

```clj
(dev)
(start)
```

and then open a browser to `http://localhost:3000`.

You now have a working development environment!

<!-- Copyright 2013 Relevance, Inc. -->
