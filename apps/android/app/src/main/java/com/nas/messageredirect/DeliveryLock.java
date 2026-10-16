package com.nas.messageredirect;

import java.util.concurrent.locks.ReentrantLock;

/** Serializes sending and configuration changes, including manual/background jobs. */
final class DeliveryLock {
    static final ReentrantLock LOCK = new ReentrantLock();
    private DeliveryLock() { }
}
