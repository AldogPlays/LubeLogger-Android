package app.lubelogger.wrapper

internal fun interface Cancellation { fun cancel() }

internal interface ConnectionScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellation
}

internal interface ConnectionProbe : AutoCloseable {
    // Deliver exactly one result asynchronously on the main thread; cancellation may still race delivery.
    fun check(address: ServerAddress, timeoutMs: Int, result: (Boolean) -> Unit): Cancellation
}

internal sealed class ConnectionDecision {
    data object Checking : ConnectionDecision()
    data object Unavailable : ConnectionDecision()
    data class Selected(val address: ServerAddress, val previous: ServerAddress?) : ConnectionDecision()
}

/** All operations and decisions are serialized on the main thread. Pause invalidates delivery immediately. */
internal class ConnectionSession(
    private val addresses: ServerAddresses,
    private val scheduler: ConnectionScheduler,
    private val probe: ConnectionProbe,
    restored: ServerAddress? = null,
    private val display: (ConnectionDecision) -> Unit
) : AutoCloseable {
    private var active = restored?.takeIf { it in addresses.ordered }
    private var foreground = false
    private var closed = false
    private var generation = 0L
    private var running = false
    private var hinted = false
    private var work: Cancellation? = null
    private var delayed: Cancellation? = null

    fun resume() {
        if (closed || foreground) return
        foreground = true
        generation++
        if (active == null) selectInitial() else scheduleCheck(1200)
    }

    fun pause() {
        foreground = false
        invalidateWork()
    }

    fun retry() {
        if (!foreground || closed) return
        invalidateWork()
        active = null
        selectInitial()
    }

    fun connectionChanged() {
        if (!foreground || closed || active == null) return
        if (running) hinted = true else scheduleCheck(1200)
    }

    override fun close() {
        if (closed) return
        pause()
        closed = true
        probe.close()
    }

    private fun invalidateWork() {
        generation++
        delayed?.cancel()
        delayed = null
        work?.cancel()
        work = null
        running = false
        hinted = false
    }

    private fun current(token: Long) = foreground && !closed && generation == token

    private fun selectInitial() {
        val token = generation
        running = true
        display(ConnectionDecision.Checking)
        probeInOrder(addresses.ordered, 2500, token) { selected ->
            active = selected
            display(if (selected == null) ConnectionDecision.Unavailable else ConnectionDecision.Selected(selected, null))
        }
    }

    private fun scheduleCheck(delayMs: Long) {
        if (!foreground || closed) return
        delayed?.cancel()
        val token = generation
        delayed = scheduler.schedule(delayMs) {
            if (!current(token)) return@schedule
            delayed = null
            checkActive()
        }
    }

    private fun checkActive() {
        val previous = active ?: return
        if (running) { hinted = true; return }
        running = true
        val token = generation
        val candidates = listOf(previous) + addresses.ordered.filterNot { it == previous }
        probeInOrder(candidates, 1500, token) { selected ->
            if (selected == null) {
                hinted = false
                scheduleCheck(4000)
            } else {
                active = selected
                if (selected != previous) display(ConnectionDecision.Selected(selected, previous))
                if (hinted && current(token)) {
                    hinted = false
                    scheduleCheck(1200)
                }
            }
        }
    }

    private fun probeInOrder(candidates: List<ServerAddress>, timeoutMs: Int, token: Long, complete: (ServerAddress?) -> Unit) {
        fun tryAddress(index: Int) {
            if (!current(token)) return
            val address = candidates.getOrNull(index)
            if (address == null) {
                running = false
                work = null
                complete(null)
                return
            }
            work = probe.check(address, timeoutMs) { works ->
                if (!current(token)) return@check
                if (works) {
                    running = false
                    work = null
                    complete(address)
                } else tryAddress(index + 1)
            }
        }
        tryAddress(0)
    }
}
