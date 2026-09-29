package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryableInitializationTest {
    @Test
    fun cancelledInitializationIsReportedAsFailureAndCanRetry() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        var attempts = 0
        val initialization = RetryableInitialization(scope) {
            if (++attempts == 1) throw CancellationException("initialization aborted")
        }
        val failure = runCatching { initialization.await(1_000) }.exceptionOrNull()
        assertTrue(failure is IOException)
        initialization.await(1_000)
        assertEquals(2, attempts)
    }

    @Test
    fun concurrentWaitersShareOneAttemptAndSuccessfulResult() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val ready = CompletableDeferred<Unit>()
        var attempts = 0
        val initialization = RetryableInitialization(scope) {
            attempts++
            ready.await()
        }
        val first = async { initialization.await(1_000) }
        val second = async { initialization.await(1_000) }
        runCurrent()
        assertEquals(1, attempts)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        ready.complete(Unit)
        first.await()
        second.await()
        initialization.await(1_000)
        assertEquals(1, attempts)
    }

    @Test
    fun failedAttemptCanRetryUsingCurrentEnvironment() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        var directory: String? = null
        var selectedDirectory: String? = null
        var attempts = 0
        val initialization = RetryableInitialization(scope) {
            attempts++
            selectedDirectory = checkNotNull(directory)
        }
        assertTrue(runCatching { initialization.await(1_000) }.isFailure)
        directory = "/available"
        initialization.await(1_000)
        assertEquals(2, attempts)
        assertEquals(directory, selectedDirectory)
    }

    @Test
    fun failedConcurrentWaitersObserveSameErrorBeforeNextAttempt() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val release = CompletableDeferred<Unit>()
        val failure = IOException("temporary setup failure")
        var attempts = 0
        val initialization = RetryableInitialization(scope) {
            if (++attempts == 1) {
                release.await()
                throw failure
            }
        }
        val first = async { runCatching { initialization.await(1_000) }.exceptionOrNull() }
        val second = async { runCatching { initialization.await(1_000) }.exceptionOrNull() }
        runCurrent()
        release.complete(Unit)
        // Coroutine stack recovery can copy the exception while retaining its cause.
        assertSame(failure, generateSequence(first.await()) { it.cause }.last())
        assertSame(failure, generateSequence(second.await()) { it.cause }.last())
        initialization.await(1_000)
        assertEquals(2, attempts)
    }

    @Test
    fun timeoutDoesNotStartOverlappingNativeInitialization() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val ready = CompletableDeferred<Unit>()
        var attempts = 0
        val initialization = RetryableInitialization(scope) {
            attempts++
            ready.await()
        }
        val first = async { runCatching { initialization.await(1_000) }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(first.await() is IOException)
        val retry = async { initialization.await(1_000) }
        runCurrent()
        assertEquals(1, attempts)
        assertFalse(retry.isCompleted)
        ready.complete(Unit)
        retry.await()
        assertEquals(1, attempts)
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelInitializationOrOtherWaiters() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val ready = CompletableDeferred<Unit>()
        var attempts = 0
        val initialization = RetryableInitialization(scope) {
            attempts++
            ready.await()
        }
        val cancelled = launch { initialization.await(1_000) }
        val other = async { initialization.await(1_000) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertFalse(other.isCompleted)
        ready.complete(Unit)
        other.await()
        assertEquals(1, attempts)
    }
}
