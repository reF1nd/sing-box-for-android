package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal class RetryableInitialization(
    private val scope: CoroutineScope,
    private val initialize: suspend () -> Unit,
) {
    private val access = Mutex()
    private var attempt: Deferred<Unit>? = null

    suspend fun await(timeoutMillis: Long) {
        val completed = withTimeoutOrNull(timeoutMillis) {
            val current = access.withLock {
                attempt?.takeUnless { it.isCompleted && it.isCancelled }
                    ?: scope.async { initialize() }.also { attempt = it }
            }
            // A cancelled or timed-out waiter must not cancel shared native work.
            // Retry only after a failed attempt has actually returned.
            try {
                current.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("Core initialization was cancelled", e)
            }
            true
        }
        if (completed == null) throw IOException("Core initialization timed out")
    }
}
