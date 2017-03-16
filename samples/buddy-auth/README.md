# buddy-auth

This sample demonstrates integration with
the [buddy-auth](https://funcool.github.io/buddy-auth/latest/) library
using a Basic Authentication backend. In the process, it demonstrates
how to port buddy-auth's `wrap-authentication` and
`wrap-authorization` middleware functions to interceptors.

## Getting Started

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello
   anonymous`.
3. Make a request to [localhost:8080](http://localhost:8080/) using
   HTTP Basic authentication with the username `aaron` and password
   `secret` to see: `Hello Aaron Aardvark`.
4. Make a request to [localhost:8080/admin](http://localhost:8080/admin) to
   see a `401` response.
5. Make a request to [localhost:8080/admin](http://localhost:8080/admin)
using HTTP Basic authentication with the username `aaron` and password
`secret` to see a `403` response.
5. Make a request to [localhost:8080/admin](http://localhost:8080/admin)
using HTTP Basic authentication with the username `gmw` and password
`rutabaga` to see: `Only admins can see this!`.
