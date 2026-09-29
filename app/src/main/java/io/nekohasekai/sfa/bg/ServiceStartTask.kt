package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

// Start, cancel and destroy are called on the service's main thread.
internal class ServiceStartTask(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var destroyed = false

    fun start(initialize: suspend () -> Unit, startService: suspend () -> Unit, onFailure: (Exception) -> Unit): Boolean {
        if (destroyed || job?.isCompleted == false) return false
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                initialize()
                currentCoroutineContext().ensureActive()
                startService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                onFailure(e)
            }
        }
        job?.start()
        return true
    }

    fun cancel(): Job? = job?.also { it.cancel() }

    fun destroy(): Job? {
        destroyed = true
        return cancel()
    }
}
