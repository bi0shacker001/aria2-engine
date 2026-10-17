package io.github.bi0shacker001.aria2engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The one thread libaria2 runs on (it is not thread-safe). It alternates one turn of the session's event loop
 * ([EngineNative.runOnce], which waits at most about a second for network activity) with the commands queued from
 * binder threads, so a command waits at most one turn. Download events are raised on this thread.
 */
internal class EngineThread(private val session: Long) : Thread("aria2-engine") {
    private val commands = ConcurrentLinkedQueue<Runnable>()

    @Volatile
    private var stopping = false

    /** Runs [block] on the engine thread with the session and waits for its answer (at most [timeoutMs]). */
    fun <T> call(timeoutMs: Long = 30_000, block: (session: Long) -> T): T {
        if (currentThread() === this) return block(session)
        val answer = CompletableFuture<T>()
        commands.add(Runnable { runCatching { block(session) }.fold(answer::complete, answer::completeExceptionally) })
        return answer.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Asks the session to end; the thread finishes its turn, shuts libaria2 down and returns. */
    fun finish() {
        stopping = true
    }

    override fun run() {
        try {
            while (!stopping) {
                while (true) commands.poll()?.run() ?: break
                if (EngineNative.runOnce(session) < 0) break
            }
            EngineNative.shutdown(session, true)
            // A forced shutdown still needs the loop to wind its downloads down.
            while (EngineNative.runOnce(session) == 1) {
                commands.poll()?.run()
            }
        } finally {
            EngineNative.sessionFinal(session)
        }
    }
}
