# Pedestal Application Template

Generate a new Pedestal Application.

## Usage

To create a new project run:

```bash
# Generate a project with introductory comments
lein new pedestal-app example
# Alternatively, generate a project without comments
lein new pedestal-app example no-comment
```

You will have a new app in example! To explore further, read
the [readme in your generated
app](https://github.com/pedestal/pedestal/blob/master/app-template/src/leiningen/new/pedestal_app/README.md).

## Developer Notes

There are two ways to try out local changes to this template:

1. Run `lein new pedestal-app NAME` in this directory.
2. `lein install` in this directory; ensure the correct version of the template is in :plugins of your
   `~/.lein/profiles.clj`; generate a new app.

<!-- Copyright 2013 Relevance, Inc. -->
