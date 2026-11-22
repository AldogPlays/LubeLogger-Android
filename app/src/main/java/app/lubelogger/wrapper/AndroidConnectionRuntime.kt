package app.lubelogger.wrapper

import android.os.Handler
import java.util.concurrent.Executors

internal class AndroidConnectionScheduler(private val main: Handler) : ConnectionScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
        val task = Runnable(action)
        main.postDelayed(task, delayMs)
        return Cancellation { main.removeCallbacks(task) }
    }
}

internal class ExecutorConnectionProbe(private val main: Handler) : ConnectionProbe {
    private val executor = Executors.newSingleThreadExecutor()
    private val probe = HttpServerProbe()

    override fun check(address: ServerAddress, timeoutMs: Int, result: (Boolean) -> Unit): Cancellation {
        val task = executor.submit {
            val works = probe.validates(address, timeoutMs)
            main.post { result(works) }
        }
        return Cancellation { task.cancel(true) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
