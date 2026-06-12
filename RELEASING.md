# Releasing Pedestal

Releases are cut locally with a single command; CI does the deploy.

The repo is configured to use a [Clojars deploy token](https://github.com/clojars/clojars-web/wiki/Deploy-Tokens) with permissions to `io.pedestal` group only and 2 github secrets `CLOJARS_USERNAME` and `CLOJARS_PASSWORD`.

## Cutting a release

1. Make sure CI is green and `CHANGELOG.md` is up to date under the `## <version> - UNRELEASED` heading.
2. From the repository root:

   ```
   clj -T:build release :level :release
   ```

   Levels: `:release` (the default, strips the stability suffix, e.g. `1.2.3-beta-2` to `1.2.3`),
   `:beta`, `:rc`, `:major`, `:minor`, `:patch`. Add `:dry-run true` to preview the version numbers
   without changing anything.

This advances the version (`VERSION.txt`, all module `deps.edn` files, docs), stamps today's date on
the changelog heading (final releases only), commits, tags, and pushes. For final releases, it then
advances to the next patch SNAPSHOT with a fresh UNRELEASED changelog heading and pushes that too.

The pushed tag triggers the [Release workflow](.github/workflows/release.yml), which:

1. Verifies the tag matches `VERSION.txt`.
2. Builds all modules in dependency order and deploys them to Clojars.
3. Creates a GitHub release with the changelog entry; beta/RC tags are marked as prereleases
   with auto-generated notes.

## Manual fallback

`clj -T:build deploy-all` still deploys from a local checkout, with `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` set in the environment. Add `:sign true` (and `CLOJARS_GPG_ID`) to GPG-sign
the artifacts as in the pre-CI process.
