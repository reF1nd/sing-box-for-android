package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.utils.RetryableInitialization
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceStartTaskTest {
    @Test
    fun initializationTimeoutReportsFailureAndDoesNotResumeOldStart() = runTest {
        val applicationScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val ready = CompletableDeferred<Unit>()
        val initialization = RetryableInitialization(applicationScope) { ready.await() }
        val startup = ServiceStartTask(backgroundScope)
        val errors = mutableListOf<Exception>()
        var starts = 0
        startup.start({ initialization.await(1_000) }, { starts++ }, errors::add)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(errors.single() is IOException)
        ready.complete(Unit)
        runCurrent()
        assertEquals(0, starts)
        startup.start({ initialization.await(1_000) }, { starts++ }, errors::add)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test
    fun duplicateStartWhileWaitingDoesNotCreateAnotherService() = runTest {
        val ready = CompletableDeferred<Unit>()
        var starts = 0
        val startup = ServiceStartTask(backgroundScope)
        assertTrue(startup.start({ ready.await() }, { starts++ }, { throw AssertionError(it) }))
        assertFalse(startup.start({}, { starts++ }, { throw AssertionError(it) }))
        runCurrent()
        assertEquals(0, starts)
        ready.complete(Unit)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test
    fun stopWhileWaitingNeverStartsServiceOrReportsCancellationAsFailure() = runTest {
        val ready = CompletableDeferred<Unit>()
        var starts = 0
        val errors = mutableListOf<Exception>()
        val startup = ServiceStartTask(backgroundScope)
        startup.start({ ready.await() }, { starts++ }, errors::add)
        runCurrent()
        startup.cancel()?.join()
        ready.complete(Unit)
        runCurrent()
        assertEquals(0, starts)
        assertTrue(errors.isEmpty())
        assertTrue(startup.start({}, { starts++ }, errors::add))
        runCurrent()
        assertEquals(1, starts)
    }

    @Test
    fun destroyRejectsRestartAndIgnoresLateInitializationCompletion() = runTest {
        val ready = CompletableDeferred<Unit>()
        var starts = 0
        val startup = ServiceStartTask(backgroundScope)
        startup.start(
            { withContext(NonCancellable) { ready.await() } },
            { starts++ },
            { throw AssertionError(it) },
        )
        runCurrent()
        val oldJob = startup.destroy()
        assertFalse(startup.start({}, { starts++ }, { throw AssertionError(it) }))
        ready.complete(Unit)
        oldJob?.join()
        assertEquals(0, starts)
    }

    @Test
    fun cleanupWaitsForUninterruptibleResourceCreationToReturn() = runTest {
        val nativeReturned = CompletableDeferred<Unit>()
        var resourceCreated = false
        var resourceClosed = false
        val startup = ServiceStartTask(backgroundScope)
        startup.start(
            {},
            {
                withContext(NonCancellable) {
                    nativeReturned.await()
                    resourceCreated = true
                }
            },
            { throw AssertionError(it) },
        )
        runCurrent()
        val oldJob = startup.cancel()
        assertFalse(startup.start({}, {}, { throw AssertionError(it) }))
        val cleanup = launch {
            oldJob?.join()
            resourceClosed = resourceCreated
        }
        runCurrent()
        assertFalse(cleanup.isCompleted)
        assertFalse(resourceClosed)
        nativeReturned.complete(Unit)
        cleanup.join()
        assertTrue(resourceClosed)
    }

    @Test
    fun initializationFailureIsReportedAndLaterStartCanSucceed() = runTest {
        val failure = IOException("setup failed")
        val errors = mutableListOf<Exception>()
        var starts = 0
        val startup = ServiceStartTask(backgroundScope)
        startup.start({ throw failure }, { starts++ }, errors::add)
        runCurrent()
        assertEquals(0, starts)
        assertSame(failure, errors.single())
        startup.start({}, { starts++ }, errors::add)
        runCurrent()
        assertEquals(1, starts)
    }
}
