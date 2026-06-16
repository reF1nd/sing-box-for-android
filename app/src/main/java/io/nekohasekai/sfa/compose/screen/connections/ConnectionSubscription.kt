package io.nekohasekai.sfa.compose.screen.connections

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference

/** Each attempt owns its callbacks and queue; cancelling it also discards queued native events. */
internal class ConnectionSubscription<E>(
    private val createClient: (CoroutineScope, Listener<E>) -> Client,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    interface Client {
        fun connect()
        fun disconnect()
    }

    interface Listener<E> {
        fun onEvent(event: E)
        fun onFailure(failure: Failure)
    }

    class Failure(message: String, val retryable: Boolean = true) : Exception(message)

    suspend fun collect(
        createConsumer: () -> suspend (List<E>) -> Unit,
        onFailure: (Failure) -> Unit,
    ) {
        var retryDelay = 1_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val consume = createConsumer()
            var firstEventAt: Long? = null
            try {
                collectAttempt { events ->
                    consume(events)
                    if (firstEventAt == null) firstEventAt = nowMillis()
                }
            } catch (failure: Failure) {
                currentCoroutineContext().ensureActive()
                onFailure(failure)
                if (!failure.retryable) return
                // A handshake alone does not make a repeatedly failing stream healthy.
                if (firstEventAt?.let { nowMillis() - it >= 30_000L } == true) {
                    retryDelay = 1_000L
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun collectAttempt(consume: suspend (List<E>) -> Unit) = coroutineScope {
        val events = Channel<E>(64)
        val pendingFailure = AtomicReference<Failure?>()
        val listener = object : Listener<E> {
            override fun onEvent(event: E) {
                val result = events.trySend(event)
                if (result.isFailure && !result.isClosed) {
                    // Incremental events cannot be dropped: obtain a fresh snapshot instead.
                    onFailure(Failure("Connection event queue overflow"))
                }
            }

            override fun onFailure(failure: Failure) {
                // The first failure determines retry policy, including terminal errors.
                if (pendingFailure.compareAndSet(null, failure)) {
                    events.close(failure)
                }
            }
        }
        val client = createClient(this, listener)
        try {
            client.connect()
            while (true) {
                val first = events.receive()
                pendingFailure.get()?.let { throw it }
                val batch = mutableListOf(first)
                // Bound each batch as well as the queue so a busy producer cannot starve UI work.
                while (batch.size < 64) {
                    batch.add(events.tryReceive().getOrNull() ?: break)
                }
                pendingFailure.get()?.let { throw it }
                consume(batch)
            }
        } finally {
            events.cancel()
            client.disconnect()
        }
    }
}

internal fun isRetryableConnectionError(message: String): Boolean = listOf(
    "Unauthenticated",
    "PermissionDenied",
    "InvalidArgument",
    "Unimplemented",
).none { message.contains("code = $it ") }
