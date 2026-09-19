package com.valdroid.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
