@file:Suppress(
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

import kotlinx.browser.window
import org.w3c.dom.DedicatedWorkerGlobalScope
import org.w3c.dom.MessagePort
import kotlin.js.Promise
import kotlin.math.max

/**
 * Helper class that schedules and executes [Thread]s in a predefined
 * pool. Using the [ThreadScheduler] is done by passed it as an argument
 * to [Thread.start][eu.chainfire.kjs.threads.start].
 * @constructor Creates a [ThreadScheduler]
 * @param slots Number of threads to create for the pool. When 0, executes on the current thread (dangerous!)
 */
class ThreadScheduler(
    private val slots: Int
) {
    companion object {
        private var subthreadCounter: Int = -1
    }

    private class Subthread(
        val thread: Thread<*,*>,
        val priority: Int,
        val port: MessagePort,
        val subthreadId: Int
    )

    private class NextSubthreadMessage(
        val className: String,
        val port: MessagePort,
        val subthreadId: Int
    )

    private class SubthreadRunner() : ComplexThread<Int, Unit>() {
        companion object {
            private const val MESSAGE_NEXT_SUBTHREAD: String = "__next"
        }

        private var onComplete: ((Subthread?) -> Unit)? = null
        private var onError: ((Subthread?, Throwable?) -> Unit)? = null

        constructor (onComplete: ((Subthread?) -> Unit), onError: ((Subthread?, Throwable?) -> Unit)) : this() {
            this.onComplete = onComplete
            this.onError = onError
        }

        var subthread: Subthread? = null

        private var threadId: Int = -1

        override suspend fun run(args: Int?) {
            threadId = args ?: -1
            messageReceiver.callback = { m ->
                if (m == null) {
                    close()
                } else if (m.message == MESSAGE_NEXT_SUBTHREAD) {
                    val subthread = m.args.unsafeCast<NextSubthreadMessage>()
                    val self = this.self
                    if (self != null) {
                        val thread = ThreadManager.scopedEval("new ${subthread.className}()").unsafeCast<Thread<*,*>?>()
                        if (thread == null) {
                            val message = "SubthreadRunner#$threadId: Could not instantiate ${subthread.className} #${subthread.subthreadId}"
                            console.log(message)
                        } else {
                            thread.workerMain(self, subthread.port)
                        }
                    }
                }
            }
        }

        fun schedule(subthread: Subthread) {
            this.subthread = subthread
            subthread.thread.completionPromise().then {
                val t = this.subthread
                this.subthread = null
                onComplete?.invoke(t)
            }.catch {
                val t = this.subthread
                this.subthread = null
                onError?.invoke(t, it)
            }
            post(MESSAGE_NEXT_SUBTHREAD, NextSubthreadMessage(subthread.thread::class.js.name, subthread.port, subthread.subthreadId), arrayOf(subthread.port))
        }
    }

    /**
     * Thrown on invalid state transition attempt
     */
    class StateException(message: String?) : Exception(message)

    enum class State(val intValue: Int) {
        /** Not running */
        CLOSED(0),
        /** Threads are starting */
        STARTING(1),
        /** Threads are running */
        RUNNING(2),
        /** Threads are closing */
        CLOSING(3),
    }

    /**
     * Currents [ThreadScheduler] state
     */
    var state: State = State.CLOSED
        private set

    /**
     * Number of currently executing [Thread]s
     */
    val running: Int
        get() {
            if (state != State.RUNNING) return 0
            return threads.filter {
                it.subthread != null
            }.size
        }

    /**
     * Number of queued [Thread]s
     */
    val queued: Int
        get() = subthreads.size

    /**
     * Number of [Thread]s either queued or executing
     */
    val runningAndQueued: Int
        get() = running + queued

    init {
        if (slots < 0) throw IllegalArgumentException("slots ($slots) must be >= 0")
    }

    private val threads = ArrayList<SubthreadRunner>()
    private val subthreads = ArrayList<Subthread>()
    private var localExecutor: SubthreadRunner? = null

    private fun setupLocalExecutor(port: MessagePort) {
        val t = SubthreadRunner()
        t.workerMain(window.unsafeCast<DedicatedWorkerGlobalScope>(), port)
        localExecutor = t
    }

    private fun next() {
        if (state != State.STARTING && state != State.RUNNING) return
        if (subthreads.size == 0) return
        for (thread in threads) {
            if (thread.state == Thread.State.RUNNING && thread.subthread == null) {
                thread.schedule(subthreads.removeAt(0))
                return
            }
        }
    }

    /**
     * Called from [start][eu.chainfire.kjs.threads.start] to queue [Thread]
     */
    internal fun queue(thread: Thread<*,*>, priority: Int, port: MessagePort) {
        if (slots == 0 && localExecutor == null) {
            setupLocalExecutor(port)
            return
        }
        if (state != State.STARTING && state != State.RUNNING) throw StateException("Can't queue on an inactive scheduler ")
        var index: Int? = null
        for (i in 0 until subthreads.size) {
            if (subthreads[i].priority > priority) {
                index = i
                break
            }
        }
        val subthread = Subthread(thread, priority, port, ++subthreadCounter)
        if (index == null) {
            subthreads.add(subthread)
        } else {
            subthreads.add(index, subthread)
        }
        next()
    }

    /**
     * Start pool of threads, returning a [Promise] resolving when all threads have started
     * @return [Promise] resolving when all threads are up
     */
    fun start(): Promise<*> {
        if (state != State.CLOSED) throw StateException("Can't start an already running scheduler")
        state = State.STARTING
        val a = arrayListOf<Promise<Unit>>()
        threads.clear()
        subthreads.clear()
        for (i in 0 until max(1, slots)) {
            val t = SubthreadRunner({ next() }, { _, _ -> next() })
            threads.add(t)
            t.start(i, threadScheduler = if (slots == 0) this else null)
            a.add(t.runningPromise())
        }
        return Promise.all(a.toTypedArray()).then {
            state = State.RUNNING
            next()
        }
    }

    /**
     * Close pool of threads, returning a [Promise] resolving when all threads have closed
     * @return [Promise] resolving when all threads have closed
     */
    fun close(immediate: Boolean = false): Promise<*> {
        if (state != State.RUNNING) throw StateException("Can't close an inactive scheduler")
        val a = arrayListOf<Promise<Unit?>>()
        if (slots > 0) {
            for (thread in threads) {
                thread.close(immediate)
                a.add(thread.completionPromise())
            }
        }
        subthreads.clear()
        threads.clear()
        state = State.CLOSING
        return (if (slots > 0)
            Promise.all(a.toTypedArray())
        else
            Promise<Unit> { resolve, _ -> resolve(Unit) }
        ).then {
            state = State.CLOSED
        }
    }

    /**
     * Remove [Thread] from queue
     * @param thread [Thread] to unqueue
     * @return True if [Thread] was unqueued, false if not found, already complete, or already running
     */
    fun unqueue(thread: Thread<*,*>): Boolean {
        val subthread = subthreads.find {
            it.thread == thread
        }
        return if (subthread != null) {
            subthreads.remove(subthread)
            true
        } else {
            false
        }
    }
}
