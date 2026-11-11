package pl.umamusume.tiket.backend.application

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** No job queue: a lane claims inside its running task and accepts at most one task. */
class SerialDispatchCoordinator(private val lanes: List<Set<Int>>, private val dispatch: (Set<Int>) -> Unit) : AutoCloseable {
    private val busy = lanes.map { AtomicBoolean() }
    private var stopping = false
    private val executor = ThreadPoolExecutor(lanes.size, lanes.size, 0, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), ThreadFactory { task ->
            Thread(task, "serial-print-lane").apply { isDaemon = true }
        })

    @Synchronized fun poll() {
        if (stopping) return
        lanes.forEachIndexed { index, pos ->
            if (busy[index].compareAndSet(false, true)) {
                try { executor.execute { try { dispatch(pos) } finally { busy[index].set(false) } } }
                catch (e: RejectedExecutionException) { busy[index].set(false) }
            }
        }
    }

    override fun close() {
        synchronized(this) { stopping = true; executor.shutdown() }
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow() }
        catch (e: InterruptedException) { executor.shutdownNow(); Thread.currentThread().interrupt() }
    }
}
