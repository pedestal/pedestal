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

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class MockHttpServletResponse implements HttpServletResponse {

    private final MockState state;

    MockHttpServletResponse(MockState state) {
        this.state = state;
    }


    @Override
    public void addCookie(Cookie cookie) {

    }

    @Override
    public boolean containsHeader(String name) {
        return false;
    }

    @Override
    public String encodeURL(String url) {
        return "";
    }

    @Override
    public String encodeRedirectURL(String url) {
        return "";
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        state.responseStatus = sc;
        state.responseStream.print(msg);

    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, "Servet Error");

    }

    @Override
    public void sendRedirect(String location) throws IOException {

    }

    @Override
    public void setDateHeader(String name, long date) {

    }

    @Override
    public void addDateHeader(String name, long date) {
        setDateHeader(name, date);

    }

    @Override
    public void setHeader(String name, String value) {
        state.responseHeaders.put(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        // Only keep the last one
        setHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        setIntHeader(name, value);
    }

    @Override
    public void setStatus(int sc) {
        state.responseStatus = sc;

    }

    @Override
    public int getStatus() {
        return state.responseStatus;
    }

    @Override
    public String getHeader(String name) {
        return "";
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return List.of();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return List.of();
    }

    @Override
    public String getCharacterEncoding() {
        return "";
    }

    @Override
    public String getContentType() {
        return "";
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return null;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return null;
    }

    @Override
    public void setCharacterEncoding(String charset) {

    }

    @Override
    public void setContentLength(int len) {
        state.responseContentLength = len;
        setHeader("Content-Length", Integer.toString(len));

    }

    @Override
    public void setContentLengthLong(long len) {
        state.responseContentLength = len;
        setHeader("Content-Length", Long.toString(len));

    }

    @Override
    public void setContentType(String type) {
        setHeader("Content-Type", type);
    }

    @Override
    public void setBufferSize(int size) {

    }

    @Override
    public int getBufferSize() {
        return 1500;
    }

    @Override
    public void flushBuffer() throws IOException {
        state.responseCommitted = true;
    }

    @Override
    public void resetBuffer() {

    }

    @Override
    public boolean isCommitted() {
        return state.responseCommitted;
    }

    @Override
    public void reset() {

    }

    @Override
    public void setLocale(Locale loc) {

    }

    @Override
    public Locale getLocale() {
        return null;
    }
}
