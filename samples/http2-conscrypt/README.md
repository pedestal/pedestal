# HTTP2 with Conscrypt Security provider

## Description

This very simple app demonstrates using Jetty's HTTP2 capabilties.

This sample should work with JDK 8 and up, and uses Google Conscrypt Security Provider
(which uses Google's OpenSSL fork, BoringSSL).  This means you don't need
a bootclasspath agent to patch JDK8 libs, and you don't need to worry about JDK9+
libs.
Additionally, It should have better performance than the Java native libs.

## Usage

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) to see an HTTP 1.1 message.
3. Go to [localhost:8443](https://localhost:8443/) to see an HTTP2 message.

You can also use an HTTP2-enabled command line client:

1. Direct HTTP2 clear-text request: `nghttp -v http://localhost:8080/`
2. Upgrade an HTTP/1.1 request to a clear-text HTTP2 request: `nghttp -vu http://localhost:8080/`

### Enabling HTTP/2 Push

Jetty fully supports HTTP/2 Push.  The easiest way to hook up Push for
all requests is with a ServletFilter - [PushCacheFilter](http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/servlets/PushCacheFilter.html)
or [PushSessionCacheFilter](http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/servlets/PushSessionCacheFilter.html).

You can also Push individual resources by hand, grabbing the [PushBuilder](http://download.eclipse.org/jetty/9.3.11.v20160721/apidocs/org/eclipse/jetty/server/PushBuilder.html)
directly from the [Servlet Request](http://download.eclipse.org/jetty/9.3.11.v20160721/apidocs/org/eclipse/jetty/server/Request.html#getPushBuilder--).

You can find more information in the [Jetty docs for configuring HTTP/2 Push](http://www.eclipse.org/jetty/documentation/current/http2-configuring-push.html).


License
-------
Copyright 2013 Relevance, Inc.
Copyright 2018 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
