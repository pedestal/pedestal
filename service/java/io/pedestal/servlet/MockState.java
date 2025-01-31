// Copyright 2025 Nubank NA
//
// The use and distribution terms for this software are covered by the
// Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
// which can be found in the file epl-v10.html at the root of this distribution.
//
// By using this software in any fashion, you are agreeing to be bound by
// the terms of this license.
//
// You must not remove this notice, or any other, from this software.

package io.pedestal.servlet;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Used by pedestal.test when testing with a mock servlet container.  Provides implementations
 * of HttpServletRequest, HttpServletResponse, AsyncContext, ServletInputStream.
 */
public class MockState {
    final String url, method, scheme, host, path, queryString;
    final int port;
    final Map<String, String> requestHeaders;
    final Map<String,String> responseHeaders = new HashMap<>();
    final ServletInputStream requestStream;
    final ServletOutputStream responseStream;

    public final MockHttpServletRequest request;
    public final MockHttpServletResponse response;
    public final MockAsyncContext asyncContext;
    public boolean asyncCompleted = false;
    public boolean asyncStarted;
    public int responseStatus = 0;
    public long responseContentLength;

    boolean responseCommitted = false;

    public MockState(String url, String method, String scheme, String host, int port, String path, String queryString, Map<String, String> requestHeaders, InputStream requestBody) {
        this.url = url;
        this.method = method;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.path = path;
        this.queryString = queryString;
        this.requestHeaders = requestHeaders;

        request = new MockHttpServletRequest(this);
        response = new MockHttpServletResponse(this);
        asyncContext = new MockAsyncContext(this);

        requestStream = new MockServletInputStream(requestBody);
        responseStream = new MockServletOutputStream(new ByteArrayOutputStream(1000));
    }

}
