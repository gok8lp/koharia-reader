package koharia.komga.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Flugel: one cancelable retry at a time, including repeated short-lived HTTP 200 streams.
internal class KomgaSseReconnectController(
    private val scopeProvider: () -> CoroutineScope?,
    private val elapsedRealtime: () -> Long,
    private val reconnect: () -> Unit,
) {
    private val lock = Any()
    private var retryJob: Job? = null
    private var generation = 0L
    private var connectedAt: Long? = null
    private var nextDelayMillis = INITIAL_DELAY_MILLIS

    val isScheduled: Boolean
        get() = synchronized(lock) { retryJob?.isActive == true }

    fun onOpen() = synchronized(lock) {
        connectedAt = elapsedRealtime()
    }

    fun schedule(): KomgaSseRetry? = synchronized(lock) {
        val scope = scopeProvider() ?: return null
        if (retryJob?.isActive == true) return null
        val wasStable = connectedAt?.let { elapsedRealtime() - it >= STABLE_STREAM_MILLIS } == true
        connectedAt = null
        if (wasStable) nextDelayMillis = INITIAL_DELAY_MILLIS
        val retry = KomgaSseRetry(nextDelayMillis, wasStable)
        nextDelayMillis = (nextDelayMillis * 2).coerceAtMost(MAX_DELAY_MILLIS)
        val scheduledGeneration = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(retry.delayMillis)
            val isCurrent = synchronized(lock) {
                if (generation != scheduledGeneration) {
                    false
                } else {
                    retryJob = null
                    true
                }
            }
            if (isCurrent) reconnect()
        }
        retryJob = job
        job.start()
        retry
    }

    fun cancel() {
        val job = synchronized(lock) {
            generation++
            connectedAt = null
            nextDelayMillis = INITIAL_DELAY_MILLIS
            retryJob.also { retryJob = null }
        }
        job?.cancel()
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 60_000L
        const val STABLE_STREAM_MILLIS = 30_000L
    }
}

internal data class KomgaSseRetry(val delayMillis: Long, val wasStable: Boolean)
