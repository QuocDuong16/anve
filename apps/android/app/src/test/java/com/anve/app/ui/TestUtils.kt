package com.anve.app.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Polls until cond() holds, for tests that drive ViewModels issuing real
 * HTTP calls on OkHttp threads (virtual-time dispatchers cannot observe
 * them). Fails the test by throwing on timeout.
 */
suspend fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!cond()) delay(10)
    }
}
