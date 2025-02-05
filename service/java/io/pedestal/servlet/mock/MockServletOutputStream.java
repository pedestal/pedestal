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
import jakarta.servlet.WriteListener;

import java.io.IOException;
import java.io.OutputStream;

/** @since 0.8.0 */
class MockServletOutputStream extends ServletOutputStream {

    private final MockState state;
    private final OutputStream delegate;

    MockServletOutputStream(MockState state, OutputStream delegate) {
        this.state = state;
        this.delegate = delegate;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {

    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
        super.close();
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        // Flushing (not closing!) the stream signals to the Servlet API that the response
        // is complete.
        state.complete();
    }
}

