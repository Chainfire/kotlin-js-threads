@file:Suppress(
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

import kotlinx.browser.document
import org.w3c.dom.DedicatedWorkerGlobalScope
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.Worker
import org.w3c.dom.asList
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Helper class that manages [Thread] creation and execution, aside from
 * optionally configuring non-default imports using [configureImports],
 * you would normally not use this class yourself.
 */
object ThreadManager {
    private var importPrevious: Boolean = false
    private var importScripts: Array<String>
    private var importSelf: Boolean = true

    private val scriptPrevious: Array<String>
    private val scriptSelf: String

    private var ranWorker: Boolean = false

    private val scopedEvals: ArrayList<(String) -> dynamic> = arrayListOf({ eval(it) })

    /**
     * Current thread id. Unique for 2^20 threads, with 2^10 sub-threads each
     */
    var threadId: Int = -1

    private var nextThreadId: Int = -1
    private var nextThreadIdInc: Int = -1
    private var nextThreadIdMax: Int = -1

    init {
        if (js("typeof document != 'undefined'").unsafeCast<Boolean>()) {
            scriptSelf = if (document.currentScript != null) document.currentScript.unsafeCast<HTMLScriptElement>().src else ""

            val scripts = ArrayList<String>()
            for (item in document.scripts.asList()) {
                val src = item.unsafeCast<HTMLScriptElement>().src
                if (src.isNotEmpty() && src != scriptSelf) scripts.add(src)
            }
            scriptPrevious = scripts.toTypedArray()
            
            importScripts = arrayOf()
        } else {
            scriptPrevious = js("THREAD_MANAGER_SCRIPT_PREVIOUS").unsafeCast<Array<String>>()
            importScripts = js("THREAD_MANAGER_SCRIPT_EXTRA").unsafeCast<Array<String>>()
            scriptSelf = js("THREAD_MANAGER_SCRIPT_SELF").unsafeCast<String>()
        }

        if (isMainThread()) {
            threadId = 1
            nextThreadIdInc = 1
            nextThreadId = threadId + nextThreadIdInc
            nextThreadIdMax = (1 shl 20) - 1
        }
    }

    /**
     * Configure imports on worker thread side. This is normally auto-detected
     * but you may want to set the imports manually for special cases. For full
     * manual control pass false to [importPrevious] and [importSelf], and pass
     * your selected scripts to [importScripts].
     * @param importPrevious Import all <script>s attached to the document at time if [Thread] start ?
     * @param importScripts Array of specific scripts to import
     * @param importSelf Import script calling this code ?
     */
    fun configureImports(importPrevious: Boolean = false, importScripts: Array<String>? = null, importSelf: Boolean = true) {
        ThreadManager.importPrevious = importPrevious
        if (!importScripts.isNullOrEmpty()) {
            ThreadManager.importScripts = importScripts
        } else {
            ThreadManager.importScripts = arrayOf()
        }
        ThreadManager.importSelf = importSelf
    }

    /**
     * Use [Thread.isMainThread] instead
     */
    fun isMainThread(scopedEval: ((String) -> dynamic)? = null): Boolean {
        if (scopedEval != null && scopedEval !in scopedEvals) scopedEvals.add(0, scopedEval)
        if (js("typeof WorkerGlobalScope != 'undefined'").unsafeCast<Boolean>()) {
            if (scopedEval != null) {
                autoRun()
            }
            return false
        }
        return true
    }

    /**
     * Run [Thread] if needed. You should normally use [Thread.isMainThread] instead.
     */
    fun autoRun(scopedEval: ((String) -> dynamic)? = null) {
        if (scopedEval != null && scopedEval !in scopedEvals) scopedEvals.add(0, scopedEval)
        if (!isMainThread()) {
            if (!ranWorker) {
                ranWorker = true
                workerMain()
            }
        }
    }

    /**
     * Execute [js] in all registered evaluation scopes, returning the first
     * result that didn't throw and isn't null
     * @param js JavaScript string to execute
     * @return Dynamic result or null
     */
    internal fun scopedEval(js: String): dynamic {
        for (scopedEval in scopedEvals) {
            try {
                return scopedEval(js)
            } catch (d: dynamic) {
            }
        }
        return null
    }

    /**
     * Internal. Used to initiate thread run.
     */
    private fun workerMain() {
        threadId = js("THREAD_MANAGER_ID").unsafeCast<Int>()
        if (threadId shr 20 == 0) {
            nextThreadIdInc = 1 shl 20
            nextThreadId = threadId + nextThreadIdInc
            nextThreadIdMax = threadId + ((1 shl 30) - 1)
        } else {
            nextThreadIdInc = 0
            nextThreadId = 0
            nextThreadIdMax = 0
        }
        val self: DedicatedWorkerGlobalScope = js("workerScope").unsafeCast<DedicatedWorkerGlobalScope>()
        val t: Thread<*,*>? = scopedEval("eval(\"new \" + THREAD_MANAGER_CTOR + \"()\")").unsafeCast<Thread<*,*>?>()
        if (t == null) {
            val message = "ThreadManager: Could not instantiate ${eval("THREAD_MANAGER_CTOR").unsafeCast<String>()}"
            console.log(message)  // sometimes the throw below isn't logged to console
            throw RuntimeException(message)
        } else {
            t.workerMain(self, null)
        }
    }

    /**
     * Internal. Spawns worker thread to run [Thread] in, returning the
     * [Worker]. Note that *window* is defined in the worker thread to
     * bypass a number of loading issues, but it is not a valid window
     * object!
     * @param thread [Thread] to spawn for
     * @return [Worker] spawned
     */
    internal fun spawn(thread: Thread<*,*>): Worker {
        var workerJs = "let window = self;\n"
        workerJs += "let workerScope = self;\n"
        workerJs += "let THREAD_MANAGER_CTOR = \"${thread::class.js.name}\";\n"
        workerJs += "let THREAD_MANAGER_CLASS = \"${thread::class.simpleName}\";\n"
        workerJs += "let THREAD_MANAGER_ID = $nextThreadId;\n"

        if (nextThreadId == 0) throw RuntimeException("Run out of threadIds")
        nextThreadId += nextThreadIdInc
        if (nextThreadId == nextThreadIdMax) throw RuntimeException("Run out of threadIds")

        workerJs += "let THREAD_MANAGER_SCRIPT_PREVIOUS = ["
        if (scriptPrevious.isNotEmpty()) {
            workerJs += "\"" + scriptPrevious.joinToString("\", \"") + "\""
        }
        workerJs += "];\n"
        workerJs += "let THREAD_MANAGER_SCRIPT_EXTRA = ["
        if (importScripts.isNotEmpty()) {
            workerJs += "\"" + importScripts.joinToString("\", \"") + "\""
        }
        workerJs += "];\n"
        workerJs += "let THREAD_MANAGER_SCRIPT_SELF = \"$scriptSelf\";\n"

        val scripts = ArrayList<String>()
        if (importPrevious) {
            for (src in scriptPrevious) {
                scripts.add(src)
            }
        }
        if (importScripts.isNotEmpty()) {
            for (src in importScripts) {
                scripts.add(src)
            }
        }
        if (importSelf) {
            if (scriptSelf.isNotEmpty()) {
                scripts.add(scriptSelf)
            }
        }
        for (src in scripts) {
            workerJs += "importScripts(\"${src}\");\n"
        }

        val url = URL.createObjectURL(Blob(arrayOf(workerJs), BlobPropertyBag("application/javascript")))
        val worker = Worker(url)
        URL.revokeObjectURL(url)
        return worker
    }
}