package com.softropic.skillars.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ReadListener;

public class MockServletInputStream extends jakarta.servlet.ServletInputStream {

    private final InputStream delegate;

    public MockServletInputStream(byte[] body) {
        this.delegate = new ByteArrayInputStream(body);
    }

    public boolean isFinished() {
        return false;
    }

    public boolean isReady() {
        return true;
    }

    public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException();
    }

    public int read() throws IOException {
        return this.delegate.read();
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return this.delegate.read(b, off, len);
    }

    public int read(byte[] b) throws IOException {
        return this.delegate.read(b);
    }

    public long skip(long n) throws IOException {
        return this.delegate.skip(n);
    }

    public int available() throws IOException {
        return this.delegate.available();
    }

    public void close() throws IOException {
        this.delegate.close();
    }

    public synchronized void mark(int readlimit) {
        this.delegate.mark(readlimit);
    }

    public synchronized void reset() throws IOException {
        this.delegate.reset();
    }

    public boolean markSupported() {
        return this.delegate.markSupported();
    }
}
