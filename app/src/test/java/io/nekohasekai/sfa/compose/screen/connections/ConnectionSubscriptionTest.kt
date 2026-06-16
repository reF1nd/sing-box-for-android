package io.nekohasekai.sfa.compose.screen.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSubscriptionTest {
    private class FakeClient(val listener: ConnectionSubscription.Listener<Int>) : ConnectionSubscription.Client {
        var connected = false
        var disconnected = false
        override fun connect() {
            connected = true
        }
        override fun disconnect() {
            disconnected = true
            listener.onFailure(ConnectionSubscription.Failure("owner disconnect"))
        }
        fun emit(value: Int) = listener.onEvent(value)
        fun fail(retryable: Boolean = true) = listener.onFailure(ConnectionSubscription.Failure("stream failed", retryable))
    }

    @Test
    fun retriesFailedConnectAndStreamWithCappedBackoff() = runTest {
        val clients = mutableListOf<FakeClient>()
        val subscription = ConnectionSubscription<Int>(
            createClient = { _, listener -> FakeClient(listener).also(clients::add) },
            nowMillis = { testScheduler.currentTime },
        )
        val job = launch { subscription.collect(createConsumer = { {} }, onFailure = {}) }
        runCurrent()
        for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            val count = clients.size
            clients.last().fail()
            runCurrent()
            assertTrue(clients.last().disconnected)
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals(count, clients.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(count + 1, clients.size)
        }
        job.cancelAndJoin()
        assertTrue(clients.last().disconnected)
    }

    @Test
    fun cancellationDuringBackoffNeverReconnects() = runTest {
        val clients = mutableListOf<FakeClient>()
        val subscription = ConnectionSubscription<Int>({ _, listener -> FakeClient(listener).also(clients::add) })
        val job = launch { subscription.collect(createConsumer = { {} }, onFailure = {}) }
        runCurrent()
        clients.single().fail()
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, clients.size)
    }

    @Test
    fun terminalFailureStopsAndPreservesFirstError() = runTest {
        val clients = mutableListOf<FakeClient>()
        val failures = mutableListOf<ConnectionSubscription.Failure>()
        val subscription = ConnectionSubscription<Int>({ _, listener -> FakeClient(listener).also(clients::add) })
        val job = launch { subscription.collect(createConsumer = { {} }, onFailure = failures::add) }
        runCurrent()
        clients.single().fail(retryable = false)
        clients.single().fail()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, clients.size)
        assertFalse(failures.single().retryable)
        assertTrue(clients.single().disconnected)
    }

    @Test
    fun oldCallbacksCannotClearOrPopulateNewSession() = runTest {
        val clients = mutableListOf<FakeClient>()
        val displayed = mutableListOf<Int>()
        val sessions = mutableListOf<MutableList<Int>>()
        val subscription = ConnectionSubscription<Int>(
            createClient = { _, listener -> FakeClient(listener).also(clients::add) },
            nowMillis = { testScheduler.currentTime },
        )
        val job = launch {
            subscription.collect(
                createConsumer = {
                    val store = mutableListOf<Int>().also(sessions::add)
                    val consume: suspend (List<Int>) -> Unit = { events ->
                        store.addAll(events)
                        displayed.clear()
                        displayed.addAll(store)
                    }
                    consume
                },
                onFailure = { displayed.clear() },
            )
        }
        runCurrent()
        val old = clients.single()
        old.emit(1)
        runCurrent()
        old.fail()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        clients.last().emit(2)
        runCurrent()
        old.emit(99)
        old.fail()
        runCurrent()
        assertEquals(listOf(2), displayed)
        assertEquals(listOf(listOf(1), listOf(2)), sessions)
        job.cancelAndJoin()
    }

    @Test
    fun targetChangeCancelsInFlightSnapshotAndDiscardsQueuedEvents() = runTest {
        val target = MutableStateFlow(1)
        val clients = mutableListOf<FakeClient>()
        val displayed = mutableListOf<Int>()
        val processing = CompletableDeferred<Unit>()
        val job = launch {
            target.collectLatest { id ->
                if (id == 0) return@collectLatest
                ConnectionSubscription<Int>({ _, listener -> FakeClient(listener).also(clients::add) }).collect(
                    createConsumer = {
                        val consume: suspend (List<Int>) -> Unit = { events ->
                            if (id == 1) {
                                processing.complete(Unit)
                                delay(10_000)
                            }
                            displayed.addAll(events)
                        }
                        consume
                    },
                    onFailure = {},
                )
            }
        }
        runCurrent()
        clients.single().emit(1)
        runCurrent()
        assertTrue(processing.isCompleted)
        clients.single().emit(99)
        target.value = 2
        runCurrent()
        assertTrue(clients.first().disconnected)
        clients.last().emit(2)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(2), displayed)
        target.value = 0
        runCurrent()
        clients.last().emit(100)
        clients.last().fail()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, clients.size)
        assertTrue(clients.last().disconnected)
        assertEquals(listOf(2), displayed)
        job.cancelAndJoin()
    }

    @Test
    fun overflowRestartsWithFreshConsumerInsteadOfDroppingDeltas() = runTest {
        val clients = mutableListOf<FakeClient>()
        val snapshots = mutableListOf<List<Int>>()
        var consumerCount = 0
        val subscription = ConnectionSubscription<Int>({ _, listener -> FakeClient(listener).also(clients::add) })
        val job = launch {
            subscription.collect(
                createConsumer = {
                    consumerCount++
                    val consume: suspend (List<Int>) -> Unit = { snapshots.add(it) }
                    consume
                },
                onFailure = {},
            )
        }
        runCurrent()
        repeat(100) { clients.single().emit(it) }
        runCurrent()
        assertTrue(snapshots.isEmpty())
        assertTrue(clients.single().disconnected)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, consumerCount)
        clients.last().emit(500)
        runCurrent()
        assertEquals(listOf(listOf(500)), snapshots)
        job.cancelAndJoin()
    }

    @Test
    fun normalBurstsRetainEventOrder() = runTest {
        val clients = mutableListOf<FakeClient>()
        val displayed = mutableListOf<Int>()
        val subscription = ConnectionSubscription<Int>({ _, listener -> FakeClient(listener).also(clients::add) })
        val job = launch {
            subscription.collect(
                createConsumer = { { displayed.addAll(it) } },
                onFailure = { throw AssertionError(it) },
            )
        }
        runCurrent()
        repeat(32) { clients.single().emit(it) }
        runCurrent()
        assertEquals((0 until 32).toList(), displayed)
        job.cancelAndJoin()
    }

    @Test
    fun stableDataStreamResetsRetryDelay() = runTest {
        val clients = mutableListOf<FakeClient>()
        val subscription = ConnectionSubscription<Int>(
            createClient = { _, listener -> FakeClient(listener).also(clients::add) },
            nowMillis = { testScheduler.currentTime },
        )
        val job = launch { subscription.collect(createConsumer = { {} }, onFailure = {}) }
        runCurrent()
        clients.last().fail()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        clients.last().emit(1)
        runCurrent()
        advanceTimeBy(30_000)
        clients.last().fail()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, clients.size)
        job.cancelAndJoin()
    }

    @Test
    fun emptyInitialSnapshotEndsLoadingButConnectAloneDoesNot() = runTest {
        lateinit var listener: ConnectionSubscription.Listener<List<Int>>
        var loading = true
        var snapshot: List<Int>? = null
        val subscription = ConnectionSubscription<List<Int>>({ _, callbacks ->
            listener = callbacks
            object : ConnectionSubscription.Client {
                override fun connect() = Unit
                override fun disconnect() = Unit
            }
        })
        val job = launch {
            subscription.collect(
                createConsumer = {
                    val consume: suspend (List<List<Int>>) -> Unit = { batches ->
                        snapshot = batches.last()
                        loading = false
                    }
                    consume
                },
                onFailure = { throw AssertionError(it) },
            )
        }
        runCurrent()
        assertTrue(loading)
        listener.onEvent(emptyList())
        runCurrent()
        assertFalse(loading)
        assertEquals(emptyList<Int>(), snapshot)
        job.cancelAndJoin()
    }

    @Test
    fun grpcTerminalErrorsDoNotRetryButTransportFailuresDo() {
        for (code in listOf("Unauthenticated", "PermissionDenied", "InvalidArgument", "Unimplemented")) {
            assertFalse(isRetryableConnectionError("connections stream recv: rpc error: code = $code desc = failed"))
        }
        assertTrue(isRetryableConnectionError("rpc error: code = Unavailable desc = connection reset"))
        assertTrue(isRetryableConnectionError("connections stream recv: EOF"))
        assertTrue(isRetryableConnectionError("probe command server: deadline exceeded"))
    }
}
