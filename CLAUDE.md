# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Pedestal is a Clojure web framework that brings simplicity, power, and focus to server-side development. It's built around the interceptor pattern and supports asynchronous request handling, Server-Sent Events, and WebSockets as first-class citizens.

**Requirements:**
- Clojure 1.11 or later
- Java 17+
- Servlet API 5.0

## Development Commands

### Running Tests

From the `tests` subdirectory:
```bash
cd tests
clj -X:test
```

The test suite includes a custom test runner (`io.pedestal.test-runner/test`) to avoid test hangs. Tests require JVM options for attaching and async checking which are configured in `tests/deps.edn`.

**Testing utilities:**
- Use `io.pedestal.connector.test/response-for` to test interceptor chains without starting a server
- The `coerce-request-body` protocol handles String, File, or InputStream request bodies
- Tests use `matcher-combinators` for assertions and `mockfn` for mocking

**Running specific tests:**
```bash
# Run a specific namespace
cd tests
clj -M -e "(require 'io.pedestal.http.route-test) (clojure.test/run-tests 'io.pedestal.http.route-test)"

# Or use the test runner with namespace filters
clj -X:test :includes '[:io.pedestal.http]'
```

### Building the Project

From the root directory:

```bash
# Install all modules to local Maven repository
clj -T:build install

# Build and deploy all modules (requires clean workspace)
clj -T:build deploy-all

# Dry run (build and install locally, don't deploy)
clj -T:build deploy-all :dry-run true
```

### Linting

```bash
# Run clj-kondo on all sources
clj -T:build lint
```

### Documentation

```bash
# Generate Codox documentation for all modules
clj -T:build codox

# Generate with custom output path
clj -T:build codox :output-path '"target/api-docs"'
```

### Security Checks

```bash
# Run CVE vulnerability checks
clj -T:nvd cve-check
```

### Experimenting with Telemetry

The `tests/deps.edn` includes aliases for experimenting with OpenTelemetry:

```bash
# Using OpenTelemetry SDK
cd tests
clj -M:otel

# Using OpenTelemetry Java Agent (requires downloading agent jar first)
clj -M:otel-agent
```

### Version Management

```bash
# Advance version and commit/tag
clj -T:build advance-version :level :patch :commit true :tag true

# Valid levels: :major, :minor, :patch, :release, :snapshot, :beta, :rc, :alpha

# Update version manually
clj -T:build update-version :version '"1.0.0"'
```

## Architecture

### Module Structure

Pedestal is organized into multiple modules in dependency order:

1. **common** - Shared utilities and environment detection
2. **telemetry** - Metrics, tracing via OpenTelemetry
3. **log** - SLF4J-based structured logging abstraction
4. **interceptor** - Core interceptor execution engine
5. **error** - Error handling utilities
6. **route** - HTTP routing and URL generation
7. **service** - High-level HTTP service configuration and default interceptors
8. **servlet** - Jakarta Servlet API bridge
9. **jetty** - Jetty 12 HTTP server implementation
10. **http-kit** - Http-Kit server implementation
11. **embedded** - Embedded server utilities

All modules use `deps.edn` for dependency management. Each module has its own `deps.edn` with `:local/root` references to other modules.

### Core Concepts

#### Interceptors

The foundation of Pedestal. An interceptor is a map with optional `:enter`, `:leave`, and `:error` functions:

```clojure
{:name  ::my-interceptor
 :enter (fn [context] ...) ; Process request (forward)
 :leave (fn [context] ...) ; Process response (reverse)
 :error (fn [context ex] ...)} ; Handle exceptions
```

Interceptors receive and return a **context map** containing `:request` and `:response` (Ring format).

#### Context Map

The context flows through the interceptor chain:
- `:request` - Ring request map (from HTTP)
- `:response` - Ring response map (returned as HTTP)
- `:route` - Matched route information
- `:url-for` - Function to generate URLs from route names
- Namespaced keys under `::io.pedestal.interceptor.chain` for internal state

#### Execution Flow

1. **Enter Phase**: Interceptors execute in order, each transforming context
2. **Handler**: Creates the `:response` in context
3. **Leave Phase**: Interceptors execute in reverse, can modify `:response`
4. **Error Phase**: If exception occurs, `:error` handlers execute in reverse

Interceptors can return a core.async channel for asynchronous processing.

#### Service Map

The service map (`::http/...` keys) configures the HTTP service:
- `::http/routes` - Route definitions
- `::http/router` - Router type (`:prefix-tree`, `:map-tree`, etc.)
- `::http/type` - Server type (`:jetty`, `:http-kit`)
- `::http/host` and `::http/port` - Bind address
- `::http/interceptors` - Custom interceptor chain
- Security: `::http/allowed-origins`, `::http/enable-csrf`, `::http/secure-headers`

#### Routing

Routes are defined separately from handlers:
- Multiple syntaxes: table-based, terse, or expanded
- Routing is itself an interceptor in the chain
- Supports path parameters, query parameters, and constraints
- `url-for-routes` generates URLs from route names

**Router Implementations:**
- `:sawtooth` - **Default in 0.8.0+**, identifies routing conflicts, prefers literal paths over parameterized ones
- `:prefix-tree` - Fast, tree-based matching
- `:map-tree` - Alternative tree implementation
- `:linear-search` - Simple linear search (for small route sets)

**Sawtooth Router Benefits:**
- Reports conflicting routes at startup (e.g., `/users/search` vs `/users/:id`)
- Prefers literal matches: `/users/search` matches before `/users/:id`
- More predictable routing behavior

**Route Definition Formats:**

*Table format (recommended):*
```clojure
[["/users" :get users-list :route-name :list-users]
 ["/users/:id" :get user-detail :route-name :get-user]
 ["/users/:id" :put user-update :route-name :update-user]]
```

*Terse format:*
```clojure
["/users" {:get users-list}
           ["/:id" {:get user-detail
                    :put user-update}]]
```

*Map format:*
```clojure
#{[:get "/users" users-list :route-name :list-users]
  [:get "/users/:id" user-detail :route-name :get-user]}
```

### Request/Response Flow

```
HTTP Request
  ↓
Servlet/Connector (converts to Ring :request)
  ↓
Context Map created, interceptor chain enqueued
  ↓
Enter Phase (logging → CORS → body parsing → routing → handler)
  ↓
Leave Phase (response formatting → headers → logging)
  ↓
Convert :response to HTTP and return
```

Default interceptors include: tracing, logging, CORS, session handling, body parsing, routing, secure headers, static file serving, and response formatting.

### Modern API (v0.8.0+)

The `io.pedestal.connector` namespace provides a cleaner API not tied to Jakarta Servlet:

```clojure
(require '[io.pedestal.connector :as conn])

(-> (conn/default-connector-map "0.0.0.0" 8080)
    (conn/with-default-interceptors)
    (conn/with-routes routes)
    conn/start!)
```

This is the recommended approach for new applications. The default router is now `:sawtooth` (changed in 0.8.0).

**Legacy vs Modern:**
- **Modern** (0.8.0+): `io.pedestal.connector` namespace - simpler, not tied to Servlet API
- **Legacy** (deprecated): `io.pedestal.http` namespace - still works but will be removed in future
- All new development should use `io.pedestal.connector`

## REPL-Driven Development

Pedestal 0.8.0+ is designed for REPL-driven development:

### Live Code Updates

The `with-routes` macro (not function) ensures routes are re-evaluated on each request during development:

```clojure
(-> connector-map
    (conn/with-routes my-routes)) ; macro - routes refreshed from var
```

This works with `clj-reload` for smooth REPL workflows.

### Development Interceptors

Enable dev-mode interceptors for better debugging:

```clojure
(-> connector-map
    conn/optionally-with-dev-mode-interceptors) ; adds dev interceptors when dev-mode? is true
```

Dev mode is enabled via:
- System property: `-Dio.pedestal.dev-mode=true`
- Environment variable: `PEDESTAL_DEV_MODE=true`

### Testing Without a Server

Use `io.pedestal.connector.test/response-for` to test interceptor chains:

```clojure
(require '[io.pedestal.connector.test :as test])

(test/response-for service-fn
                   :get "/users/123"
                   :headers {"Accept" "application/json"})
```

## Code Conventions

### Namespaces

- Core protocol definitions and specs use `io.pedestal.X` namespaces
- Implementation details under `io.pedestal.X.impl`
- HTTP-related code under `io.pedestal.http`
- Test code mirrors source structure under `tests/test`

### Testing

- All tests are consolidated in the `tests/` subdirectory
- Tests reference modules via `:local/root` dependencies
- Use `matcher-combinators` for assertions
- Use `mockfn` for mocking
- Test namespaces end with `-test`

### Interceptor Conventions

**Naming:**
- Interceptor names must be namespaced keywords: `::my-interceptor`
- Anonymous (unnamed) interceptors are **deprecated** in 0.8.0
- Functions converted to interceptors now get default names based on their class name
- To disable default handler names: set `io.pedestal.interceptor/*default-handler-names*` to false

**Creation:**
- Use `defbefore`, `defafter` helpers for simple interceptors
- Use `definterceptor` macro to define interceptor record types (new in 0.8.0)
- Interceptors can be created from functions, records, or maps

**Using `definterceptor`:**

The `definterceptor` macro creates a record that implements `IntoInterceptor` and can also work with component lifecycle:

```clojure
(require '[io.pedestal.interceptor :refer [definterceptor]])

(definterceptor logging-interceptor [logger]
  (enter [this context]
    (log/info logger :event ::request-received)
    context)
  (leave [this context]
    (log/info logger :event ::response-sent)
    context))

;; Create instance
(def my-interceptor (->logging-interceptor my-logger))

;; Can also implement component protocols
(definterceptor database-query [db-conn]
  component/Lifecycle
  (start [this] ...)
  (stop [this] ...)

  (handle [this request]
    (query-database db-conn request)))
```

The interceptor name is automatically derived as a namespaced keyword from the record name.

### Build System

The build system in `build/build.clj` handles all modules:
- Uses `io.github.hlship/build-tools` for common build tasks
- Coordinates building multiple interdependent modules
- Handles version updates across all `deps.edn` files
- Creates combined API documentation via Codox

### Logging

- Use `io.pedestal.log` namespace, not direct SLF4J
- Log statements support structured data: `(log/info :event ::my-event :data data)`
- MDC support via bindings
- Logback is used for tests (`ch.qos.logback/logback-classic`)

## Working with the Codebase

### Making Changes

1. Most work happens in individual module directories
2. Keep modules in dependency order when making cross-module changes
3. Update tests in `tests/` subdirectory
4. Run `clj -T:build lint` before committing
5. Ensure tests pass with `cd tests && clj -X:test`

### Adding Dependencies

- Add to the appropriate module's `deps.edn`
- If it's a common test dependency, add to `tests/deps.edn`
- Maintain version alignment across modules where dependencies are shared

### Documentation

- API docs generated via Codox from docstrings
- Main documentation site uses Antora (in `docs/` directory)
- Ensure docstrings follow standard Clojure format
- Examples in `samples/` and `docs/modules/guides/examples/`

### Common Patterns

**Request/Response:**
- Ring-compatible request/response maps throughout: `{:status :headers :body}`
- Request body can be String, InputStream, File, or nil
- Response body can be String, InputStream, File, ReadableByteChannel, or collections (auto-converted to JSON)

**Context Threading:**
- Every interceptor receives and returns the context map
- Add data to context for downstream interceptors: `(assoc context :db-conn conn)`
- Read from context: `(:db-conn context)`

**Asynchronous Processing:**
- Return core.async channel from interceptor for non-blocking operations
- Channel must convey the updated context map
- Execution pauses until context is delivered

**Extensibility via Protocols:**
- `IntoInterceptor` - convert custom types to interceptors
- `ExpandableRoutes` - custom route definition formats
- `Handler`, `OnEnter`, `OnLeave`, `OnError` - for record-based interceptors
- `PedestalConnector` - custom server implementations

**Configuration Over Code:**
- Service/connector map for declarative configuration
- Interceptor chain composition via data structures
- Route definitions as data (vectors, maps, sets)

**Security First:**
- Secure defaults: HTTPS, CSP headers, CSRF tokens
- CORS middleware for cross-origin requests
- Session handling with secure cookies
- Body size limits and content-type validation

## Important Notes and Breaking Changes

### Version 0.8.0 Breaking Changes

**Always clean your target directory when upgrading Pedestal versions:**
```bash
rm -rf target/
```

**Key Breaking Changes:**
- Minimum Clojure version: 1.11
- Sawtooth router is now the default (was prefix-tree)
- Anonymous interceptors are deprecated
- `io.pedestal.http` namespace deprecated (use `io.pedestal.connector`)
- Many 0.7.0 deprecated APIs removed
- WebSocket upgrade requests now go through routing (not special-cased)
- Static file serving now goes through routing (not via interceptors)
- Server-Sent Events fields terminated with `\n` (was `\r\n`)

### WebSockets and Server-Sent Events

**WebSockets:**
- Now handled through regular routing (0.8.0+)
- Implement `InitializeWebSocket` protocol for WebSocket endpoints
- WebSocket routes defined like any other route with `:ws true` flag

**Server-Sent Events (SSE):**
- Use `io.pedestal.http.sse` namespace
- Return streaming response with proper SSE formatting
- SSE fields now terminated with single `\n` (both `\n` and `\r\n` are valid per spec)
- See `samples/server-sent-events` for examples

### Async Request Handling

Interceptors can return core.async channels for non-blocking operations:

```clojure
{:enter (fn [context]
          (async/go
            (let [data (async/<! (fetch-from-db))]
              (assoc context :response {:status 200 :body data}))))}
```

## Troubleshooting and Common Issues

### Route Conflicts

If you see routing conflict warnings with the Sawtooth router:
- Review conflicting paths (e.g., `/users/search` and `/users/:id`)
- Reorder routes or use constraints to disambiguate
- The Sawtooth router will prefer literal matches over parameterized ones

### Anonymous Interceptor Warnings

If you see deprecation warnings about anonymous interceptors:
- Add `:name` keys to all interceptor maps
- Use namespace-qualified keywords: `{:name ::my-interceptor ...}`
- For functions, add `:name` metadata: `^{:name ::handler} (fn [req] ...)`

### REPL Development Issues

If routes aren't updating in the REPL:
- Ensure you're using the `with-routes` **macro**, not a function
- Check that dev-mode is enabled
- Use `clj-reload` for proper namespace reloading
- Routes must be in a var for the macro to re-evaluate them

### Test Failures

If tests hang or timeout:
- Tests use custom runner in `tests/dev/io.pedestal.test-runner`
- Ensure JVM options in `tests/deps.edn` are applied
- Check for unclosed core.async channels in tests

### Module Dependencies

When making changes across modules:
- Modules have `:local/root` dependencies on each other
- Install to local Maven repo with `clj -T:build install` after cross-module changes
- Respect module dependency order (see Architecture section)

## Resources

- Documentation: https://pedestal.io
- Support: #pedestal on Clojurians Slack
- Contributing Guide: https://pedestal.io/pedestal/0.8/contributing.html
- Sample Applications: See `samples/` directory for examples of WebSockets, SSE, metrics, CORS, etc.
