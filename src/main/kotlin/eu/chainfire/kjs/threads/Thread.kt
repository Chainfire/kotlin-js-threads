@file:Suppress(
    "NOTHING_TO_INLINE", // Says no performance gain, actual performance gain 50-100%
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import org.w3c.dom.*
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.reflect.KMutableProperty0

/**
 * Base *Thread* class. Most users should subclass [SyncThread], [AsyncThread]
 * or [ComplexThread].
 */
abstract class Thread<Args, Result> {
    companion object {
        private const val MESSAGE_INIT = "__init"
        private const val MESSAGE_STATE_CALLBACK = "__state_callback"
        private const val MESSAGE_THROWABLE_CALLBACK = "__throwable_callback"
        private const val MESSAGE_CLOSE_REQUEST = "__close_request"
        private const val MESSAGE_RESULT_CALLBACK = "__result_callback"

        /**
         * Run at the next event loop iteration
         * @param callback Code to run at next iteration
         */
        inline fun nextTick(noinline callback: () -> Unit) {
            setImmediate(callback)
        }

        /**
         * Await the next event loop iteration
         */
        suspend fun yield() {
            Promise<Unit> { resolve, _ ->
                setImmediate(resolve)
            }.await()
        }

        /**
         * Suspend coroutine execution
         * @param ms Milliseconds to suspend
         */
        suspend fun sleep(ms: Int) {
            delay(ms.toLong())
        }

        /**
         * Executing on main thread ?
         * @param scopedEval Register scoped evaluation callback "{ eval(it) }", should be omitted except in the first call from your module's *main*
         * @return Executing on main thread ?
         */
        fun isMainThread(scopedEval: ((String) -> dynamic)? = null): Boolean {
            return ThreadManager.isMainThread(scopedEval)
        }

        /**
         * See [ThreadManager.threadId]
         */
        val id: Int
            inline get() = ThreadManager.threadId
    }

    /**
     * Helper class to reconstruct thread argument objects in other thread
     */
    abstract class Args : ThreadManager.AfterCopyCast {
        /**
         * Register an object var property for reconstruction, call from
         * [register] only
         */
        protected inline fun <reified T> obj(property: KMutableProperty0<T>) {
            val o = property.get().asDynamic() ?: return
            if (o is T) return
            val t = ThreadManager.copyCast<T>(ThreadManager.copyCastClassName(T::class), o)
            property.set(t!!)
            if (t is ThreadManager.AfterCopyCast) {
                t.afterCopyCast()
            }
        }

        /**
         * Register an array-of-object var property for reconstruction, call
         * from [register] only
         */
        protected inline fun <reified T> array(property: KMutableProperty0<Array<T>>) {
            val o = property.get().asDynamic()
            for (i in 0 until o.length.unsafeCast<Int>()) {
                val t = ThreadManager.copyCast<T>(ThreadManager.copyCastClassName(T::class), o[i])
                o[i] = t
                if (t is ThreadManager.AfterCopyCast) {
                    t.afterCopyCast()
                }
            }
        }

        final override fun afterCopyCast() {
            register()
        }

        /**
         * Call [obj] or [array] here with each var property that needs reconstructing
         */
        protected abstract fun register()
    }

    /**
     * Thrown on invalid state transition attempt
     */
    class StateException(message: String?) : Exception(message)

    /**
     * Currents thread state
     */
    enum class State(val intValue: Int) {
        /** Initial state */
        NONE(0),
        /** Thread is starting */
        STARTING(1),
        /** Thread is running */
        RUNNING(2),
        /** Thread is closing or a close has been requested */
        CLOSING(3),
        /** Thread execution complete */
        COMPLETE(4),
        /** An error occurred during thread execution, see [throwable] */
        ERROR(5)
    }

    /**
     * Class representing messages exchanged between parent and worker
     * threads, and the functionality to post them
     *
     * @constructor Create [ThreadMessage] object
     * @param message Message string
     * @param args Argument object to be recreated in the other thread
     * @param transfers Array of objects that should be transferred rather than copied
     * @param source Source [MessageEvent] this [ThreadMessage] is created from
     */
    protected class ThreadMessage(
        val message: String,
        val args: dynamic = null,
        val transfers: Array<dynamic>? = null,
        val source: MessageEvent? = null
    ) {
        companion object {
            /**
             * Convert [MessageEvent] to [ThreadMessage]
             * @param event [MessageEvent]
             * @return [ThreadMessage]
             */
            inline fun from(event: MessageEvent): ThreadMessage {
                val o = event.data.asDynamic()
                return ThreadMessage(
                    o["message"].unsafeCast<String>(),
                    o["args"],
                    null,
                    event
                )
            }

            /**
             * Post to thread, to be interpreted as [ThreadMessage] in receiver
             * @param target Receiver (worker/self/port)
             * @param message Message string
             * @param args Argument object to be recreated in the other thread
             * @param transfers Array of objects that should be transferred rather than copied
             */
            inline fun post(target: dynamic, message: String, args: dynamic, transfers: Array<dynamic>? = null) {
                val o = js("{}")
                o["message"] = message
                o["args"] = args
                target.postMessage(o, transfers)
            }
        }

        /**
         * Post to parent or worker thread, or [MessagePort]
         * @param target Receiver (worker/self/port)
         */
        inline fun post(target: dynamic) {
            post(target, message, args, transfers)
        }
    }

    /**
     * Helper class to manage *onmessage* and *onerror*
     * @constructor Should not be called directly, use [Thread.createReceiver] instead
     */
    protected class EventReceiver<EventType>(
        private val interceptor: ((EventType) -> Boolean)? = null,
        private val converter: ((Event) -> EventType)? = null
    ) {
        private var queue: ArrayList<EventType> = ArrayList()
        private var resolvers: ArrayList<((EventType?) -> Unit)> = ArrayList()
        private var closed: Boolean = false

        /**
         * Handler to be assigned to *onmessage* or *onerror*
         */
        val handler = { event: Event ->
            val e = converter?.invoke(event) ?: event.unsafeCast<EventType>()
            if (interceptor?.invoke(e) != false) {
                val cb = callback
                if (cb != null) {
                    cb(e)
                } else {
                    if (resolvers.size > 0) {
                        resolvers.removeAt(0)(e)
                    } else {
                        queue.add(e)
                    }
                }
            }
        }

        /**
         * Callback for events as they are received
         *
         * If set, [handler] does not queue received events and [waitFor] does
         * not return. Setting the callback does not automatically drain the
         * queue into the supplied callback. Receives null on close request.
         */
        var callback: ((EventType?) -> Unit)? = null

        /**
         * Wait for next event and return it. Note this is about twice as
         * slow as using [callback] directly.
         * @return queued event (or null if closed), waiting for a new event if queue empty
         */
        suspend fun waitFor(): EventType? {
            return when {
                queue.size > 0 -> queue.removeAt(0)
                closed -> null
                else -> Promise<EventType?> { resolve, _ ->
                            resolvers.add(resolve)
                        }.await()
            }
        }

        /**
         * Retrieve queued event if any or null
         * @return queued event or null if queue empty
         */
        fun get(): EventType? {
            return if (queue.size > 0) queue.removeAt(0) else null
        }

        /**
         * Retrieve number of queued events
         * @return number of queued events
         */
        fun queued(): Int {
            return queue.size
        }

        internal fun close() {
            if (!closed) {
                closed = true
                val cb = callback
                if (cb != null) {
                    cb(null)
                } else {
                    while (resolvers.size > 0) {
                        resolvers.removeAt(0)(null)
                    }
                }
            }
        }
    }

    private val receivers: ArrayList<EventReceiver<*>> = arrayListOf()

    private fun createReceiver(interceptor: ((ThreadMessage) -> Boolean)? = null): EventReceiver<ThreadMessage> {
        val receiver = EventReceiver(interceptor, {
            ThreadMessage.from(it.unsafeCast<MessageEvent>())
        })
        receivers.add(receiver)
        return receiver
    }

    /**
     * Create an [EventReceiver] for the specified [MessagePort]
     * @param port [MessagePort]
     * @return [EventReceiver] returning [ThreadMessage] objects
     */
    protected fun createReceiver(port: MessagePort): EventReceiver<ThreadMessage> {
        val receiver = EventReceiver(null, {
            ThreadMessage.from(it.unsafeCast<MessageEvent>())
        })
        port.onmessage = receiver.handler
        receivers.add(receiver)
        return receiver
    }

    private fun closeReceivers() {
        for (receiver in receivers) {
            receiver.close()
        }
    }

    /**
     * Current [Thread] state
     */
    val state: State
        get() = _state
    private var _state = State.NONE
        set(value) {
            val old = field
            if (old != value && old != State.ERROR) {
                field = value
                if (worker != null || (self == null && port != null)) {
                    if (old.intValue < State.RUNNING.intValue && value.intValue >= State.RUNNING.intValue) {
                        for (callback in runningCallbacks) {
                            callback.invoke(Unit)
                        }
                        runningCallbacks.clear()
                    }

                    if (value == State.COMPLETE) {
                        for (callback in completeCallbacks) {
                            callback.invoke(result)
                        }
                        completeCallbacks.clear()
                        errorCallbacks.clear()
                    } else if (value == State.ERROR) {
                        val throwable = this.throwable ?: RuntimeException("Error in thread")
                        for (callback in errorCallbacks) {
                            callback.invoke(throwable)
                        }
                        completeCallbacks.clear()
                        errorCallbacks.clear()
                    }

                    onStateChange(old, value)
                } else {
                    post(MESSAGE_STATE_CALLBACK, _state.name)

                    // Hack to make ThreadScheduler work; we don't properly support these
                    // callbacks on the worker thread side
                    if (value == State.COMPLETE) {
                        for (callback in completeCallbacks) {
                            callback.invoke(result)
                        }
                        completeCallbacks.clear()
                        errorCallbacks.clear()
                    } else if (value == State.ERROR) {
                        for (callback in completeCallbacks) {
                            callback.invoke(null)
                        }
                        completeCallbacks.clear()
                        errorCallbacks.clear()
                    }
                }
            }
        }

    /**
     * Current [Throwable] (parent), use [postThrowable] (worker) in subclass to set
     */
    protected var throwable: Throwable? = null
        private set

    internal var argsClassName: String? = null
    internal var resultClassName: String? = null

    /**
     * Current result (parent), use [postResult] or return from *run* (worker) in subclass to set
     */
    protected var result: Result? = null
        private set

    /**
     * Result already posted to parent thread? (worker)
     */
    protected var resultPosted: Boolean = false
        private set

    private var runningCallbacks: ArrayList<((Unit) -> Unit)> = ArrayList()
    private var completeCallbacks: ArrayList<((dynamic) -> Unit)> = ArrayList()
    private var errorCallbacks: ArrayList<((Throwable) -> Unit)> = ArrayList()

    private val messageInterceptor = { event: ThreadMessage ->
        when {
            (worker != null || (self == null && port != null)) -> {
                when (event.message) {
                    MESSAGE_STATE_CALLBACK -> {
                        _state = State.valueOf(event.args.unsafeCast<String>())
                        if ((worker != null) && (_state == State.ERROR || _state == State.COMPLETE)) {
                            worker?.terminate()
                            worker = null
                        }
                        false
                    }
                    MESSAGE_THROWABLE_CALLBACK -> {
                        val clazz = event.args[0].unsafeCast<String?>()
                        val message = event.args[1].unsafeCast<String?>() ?: ""
                        if (throwable == null) throwable = ThreadManager.scopedEval("new $clazz(\"${message.replace("\"", "\\\"")}\")").unsafeCast<Throwable?>()
                        if (throwable == null) throwable = ThreadManager.scopedEval("${clazz}_init(\"${message.replace("\"", "\\\"")}\")").unsafeCast<Throwable?>()
                        if (throwable == null) {
                            throwable = when {
                                clazz?.endsWith("Exception") == true -> {
                                    RuntimeException("$clazz: $message")
                                }
                                clazz?.endsWith("Error") == true -> {
                                    Error("$clazz: $message")
                                }
                                else -> {
                                    Throwable("$clazz: $message")
                                }
                            }
                        }
                        false
                    }
                    MESSAGE_RESULT_CALLBACK -> {
                        result = ThreadManager.copyCast(resultClassName, event.args)
                        false
                    }
                    else -> {
                        true
                    }
                }
            }
            (self != null) -> {
                when (event.message) {
                    MESSAGE_INIT -> {
                        if (state == State.NONE) {
                            _state = State.RUNNING
                            val args = event.args["args"]
                            argsClassName = event.args["argsClassName"].unsafeCast<String?>()
                            resultClassName = event.args["resultClassName"].unsafeCast<String?>()
                            runWrapper(args)
                        }
                        false
                    }
                    MESSAGE_CLOSE_REQUEST -> {
                        if (state != State.CLOSING) {
                            _state = State.CLOSING
                            closeReceivers()
                            onCloseRequest()
                        }
                        false
                    }
                    else -> {
                        true
                    }
                }
            }
            else -> {
                true
            }
        }
    }

    /**
     * Incoming message queue, assign [EventReceiver.callback] or use [EventReceiver.get] or [EventReceiver.waitFor]
     */
    protected val messageReceiver: EventReceiver<ThreadMessage> = createReceiver(messageInterceptor)
    private val errorReceiver: EventReceiver<Event> = EventReceiver()

    /**
     * [Worker] object on parent thread, null on worker thread or when running in [ThreadScheduler]
     */
    protected var worker: Worker? = null

    /**
     * *DedicatedWorkerGlobalScope* on worker thread, null on parent thread or when running in [ThreadScheduler]
     */
    protected var self: DedicatedWorkerGlobalScope? = null

    /**
     * *MessagePort* on worker thread if running inside [ThreadScheduler]
     */
    protected var port: MessagePort? = null

    /**
     * Default target for [post]
     */
    protected var postTarget: dynamic = null

    /**
     * Override to catch state changes (on parent thread)
     * @param oldState Previous state
     * @param newState Next state (already set)
     */
    protected open fun onStateChange(oldState: State, newState: State) { }

    /**
     * Called on worker thread when [close] is called on parent thread
     *
     * Override when using custom messaging, async loops, have cleanup
     * requirements, etc. [state] is set to [State.CLOSING] before this is
     * called, and can be checked instead of calling [close] here.
     */
    protected open fun onCloseRequest() {
        close()
    }

    /**
     * Post [ThreadMessage] to [MessagePort] if specified, or to the other
     * thread if not
     * @param message [ThreadMessage] to post
     * @param port Optional target [MessagePort]
     */
    protected fun post(message: ThreadMessage, port: MessagePort? = null) {
        message.post(port ?: postTarget ?: return)
    }

    /**
     * Post to [MessagePort] if specified, or to the other thread if not;
     * to be interpreted as [ThreadMessage] in receiver
     * @param message Message string
     * @param args Argument object to be recreated in the other thread
     * @param transfers Array of objects that should be transferred rather than copied
     * @param port Optional target [MessagePort]
     */
    protected fun post(message: String, args: dynamic = null, transfers: Array<dynamic>? = null, port: MessagePort? = null) {
        ThreadMessage.post(port ?: postTarget ?: return, message, args, transfers)
    }

    /**
     * Post [Throwable] from worker to parent thread, becomes [Thread.throwable]
     * field. An attempt is made to reconstruct the same class and message,
     * but cause and stacktrace are garbage.
     */
    protected fun postThrowable(throwable: Throwable) {
        post(MESSAGE_THROWABLE_CALLBACK, arrayOf(throwable::class.js.name, throwable.message))
    }

    /**
     * Post *Result?* from worker to parent thread, becomes [Thread.result] field
     *
     * @param result Result to post
     * @param andClose [close] thread?
     * @param transfers Array of objects that should be transferred rather than copied
     */
    protected fun postResult(result: Result?, andClose: Boolean = true, transfers: Array<dynamic>? = null) {
        if (!resultPosted) {
            resultPosted = true
            post(MESSAGE_RESULT_CALLBACK, result, transfers)
        }
        if (andClose) close()
    }

    /**
     * Internal. Called by [ThreadManager] and [ThreadScheduler] to initiate thread run.
     */
    internal fun workerMain(self: DedicatedWorkerGlobalScope, port: MessagePort?) {
        this.self = self
        if (port != null) {
            this.port = port
            postTarget = port
            port.onmessage = messageReceiver.handler
        } else {
            postTarget = self
            self.onmessage = messageReceiver.handler
        }
    }

    /**
     * Starts worker thread. You should normally call [this version][eu.chainfire.kjs.threads.start] instead.
     */
    fun start(argsClassName: String?, resultClassName: String?, args: Args?, transfers: Array<dynamic>?, threadScheduler: ThreadScheduler?, schedulePriority: Int): Thread<Args, Result> {
        this.argsClassName = argsClassName
        this.resultClassName = resultClassName

        if (_state != State.NONE) throw StateException("Can't start a previously started thread")
        _state = State.STARTING

        if (threadScheduler == null) {
            val w = ThreadManager.spawn(this)
            w.onmessage = messageReceiver.handler
            w.onerror = errorReceiver.handler
            worker = w
            postTarget = w
            errorReceiver.callback = {
                worker?.terminate()
                worker = null
                if (throwable == null) throwable = RuntimeException(it?.asDynamic().message.unsafeCast<String?>())
                _state = State.ERROR
            }
        } else {
            val channel = MessageChannel()
            channel.port1.onmessage = messageReceiver.handler
            port = channel.port1
            postTarget = channel.port1
            threadScheduler.queue(this, schedulePriority, channel.port2)
        }
        val o = js("{}")
        o["args"] = args?.asDynamic()
        o["argsClassName"] = argsClassName
        o["resultClassName"] = resultClassName
        post(MESSAGE_INIT, o, transfers)
        return this
    }

    /**
     * Close thread (in child) or request close / terminate immediately (in parent)
     * @param immediate Terminate thread immediately rather than requesting to stop (from parent thread only)
     */
    fun close(immediate: Boolean = false) {
        if (worker != null) {
            if (immediate) {
                if (_state !in arrayOf(State.STARTING, State.RUNNING, State.CLOSING))
                    throw StateException("Can't close an inactive thread")
                _state = State.COMPLETE
                worker?.terminate()
                worker = null
            } else {
                if (_state == State.CLOSING) return
                if (_state !in arrayOf(State.STARTING, State.RUNNING))
                    throw StateException("Can't close an inactive thread")
                _state = State.CLOSING
                post(MESSAGE_CLOSE_REQUEST)
            }
        } else if (self != null) {
            if (_state != State.COMPLETE) {
                _state = State.COMPLETE
                closeReceivers()
                if (port == null) {
                    self?.close()
                }
            }
        }
    }

    /** Subclass helper */
    internal fun coroutineExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            @Suppress("USELESS_IS_CHECK")
            if (throwable is Throwable) {  // this is *not* a useless check!
                postThrowable(throwable)
            } else {
                postThrowable(Exception(js("JSON.stringify(d)").unsafeCast<String>()))
            }
            _state = State.ERROR
            console.log("${this::class.js.name} EXCEPTION")
            console.log(throwable)
            close()
        }
    }

    /** Subclass helper */
    internal inline fun runHelper(args: dynamic, postResult: Boolean = true, close: Boolean = true, runExecutor: (args: Args?) -> Result?) {
        try {
            val ret = runExecutor(ThreadManager.copyCast(argsClassName, args))
            if (postResult) {
                this.postResult(ret, close)
            } else if (close) {
                this.close()
            }
        } catch (t: Throwable) {
            postThrowable(t)
            _state = State.ERROR
            console.log("${this::class.js.name} EXCEPTION")
            console.log(t)
            close()
        } catch (d: dynamic) {
            postThrowable(Exception(js("JSON.stringify(d)").unsafeCast<String>()))
            _state = State.ERROR
            console.log("${this::class.js.name} EXCEPTION")
            @Suppress("UnsafeCastFromDynamic")
            console.log(d)
            close()
        }
    }

    /** Subclass helper */
    internal abstract fun runWrapper(args: dynamic)

    /**
     * Returns a [Promise] resolving when the thread has entered [State.RUNNING] or rejecting on [State.ERROR]
     * @return [Promise]
     */
    fun runningPromise(): Promise<Unit> {
        return Promise { resolve, reject ->
            when {
                state == State.ERROR -> {
                    reject(throwable ?: RuntimeException("Missing throwable"))
                }
                state.intValue >= State.RUNNING.intValue -> {
                    resolve(Unit)
                }
                else -> {
                    runningCallbacks.add(resolve)
                }
            }
        }
    }

    /**
     * Awaits [runningPromise] and returns this [Thread] afterwards
     * @return This [Thread]
     */
    suspend fun awaitRunning(): Thread<Args, Result> {
        runningPromise().await()
        return this
    }

    /**
     * Returns a [Promise] resolving when the thread has entered [State.COMPLETE] or rejecting on [State.ERROR]
     * @return [Promise]
     */
    fun completionPromise(): Promise<Result?> {
        return Promise { resolve, reject ->
            when (state) {
                State.COMPLETE -> {
                    resolve(result)
                }
                State.ERROR -> {
                    reject(throwable ?: RuntimeException("Missing throwable"))
                }
                else -> {
                    completeCallbacks.add(resolve)
                    errorCallbacks.add(reject)
                }
            }
        }
    }

    /**
     * Awaits [completionPromise] and returns [result] afterwards
     * @return [result]
     */
    suspend fun awaitCompletion(): Result? {
        completionPromise().await()
        return result
    }

    /**
     * Suspend until [Thread] execution completed
     */
    suspend fun join() {
        awaitCompletion()
    }
}

/**
 * Start [Thread]
 * @receiver [Thread]
 * @param args Argument object to be recreated in the other thread
 * @param transfers Array of objects that should be transferred rather than copied
 * @param threadScheduler [ThreadScheduler] to run [Thread] on or null for stand-alone
 * @param schedulePriority Priority for [Thread] if using [ThreadScheduler]
 * @return This [Thread]
 */
inline fun <reified T : Thread<Args, Result>, reified Args, reified Result> T.start(args: Args? = null, transfers: Array<dynamic>? = null, threadScheduler: ThreadScheduler? = null, schedulePriority: Int = 10): T {
    return start(CopyCast.classNameFor(Args::class), CopyCast.classNameFor(Result::class), args, transfers, threadScheduler, schedulePriority).unsafeCast<T>()
}
