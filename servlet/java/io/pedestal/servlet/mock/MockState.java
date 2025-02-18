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

package io.pedestal.servlet.mock;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Used by pedestal.test when testing with a mock servlet container.  Provides implementations
 * of HttpServletRequest, HttpServletResponse, AsyncContext, ServletInputStream.
 *
 * @since 0.8.0
 */
public class MockState {
    final String url, method, scheme, host, path, queryString;
    final int port;
    final Map<String, String> requestHeaders;
    public final Map<String, String> setResponseHeaders = new HashMap<>();
    public final Map<String, List<String>> addedResponseHeaders = new HashMap<>();
    final ServletInputStream requestStream;
    final ServletOutputStream servletOutputStream;

    public final ByteArrayOutputStream responseStream = new ByteArrayOutputStream(1000);

    public final HttpServletRequest request;
    public final HttpServletResponse response;
    final MockAsyncContext asyncContext;
    public boolean asyncStarted;
    public int responseStatus = 0;
    public long responseContentLength;

    final CountDownLatch completed = new CountDownLatch(1);

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
        servletOutputStream = new MockServletOutputStream(this, responseStream);
    }

    void complete() {
        completed.countDown();
    }

    /**
     * Waits for the response to complete (which occurs when the
     * response output stream is flushed).
     *
     * @param millis max wait time
     * @return true if complete, false if time out
     */
    public boolean waitForCompletion(long millis) {
        try {
            return completed.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }
}
