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
import kotlin.reflect.KClass

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
     * Helper interface for [copyCast]
     */
    interface AfterCopyCast {
        /**
         * Called after [ThreadManager.copyCast] performed on this object
         */
        fun afterCopyCast()
    }

    /**
     * Determines JavaScript class constructor name for [copyCast] purposes
     * @param clazz Class to determine name for
     * @return JavaScript class constructor name
     */
    fun copyCastClassName(clazz: KClass<*>): String? {
        try {
            return when {
                js("clazz.constructor.name").unsafeCast<String>().startsWith("PrimitiveKClass") -> {
                    // primitives can be cast directly
                    null
                }
                clazz.simpleName == null -> {
                    // external classes can be cast directly
                    null
                }
                else -> {
                    clazz.unsafeCast<KClass<Any>>().js.name
                }
            }
        } catch (t: Throwable) {
            return null
        } catch (d: dynamic) {
            return null
        }
    }

    /**
     * Instantiates class [className], copies [source] members into the new
     * object, and calls [AfterCopyCast.afterCopyCast] on it if provided
     * @param className Name of class to instantiate
     * @param source JavaScript object with members to copy
     * @return Instantiated class with members copied
     */
    fun <T> copyCast(className: String?, source: dynamic): T? {
        if (className == null) return source.unsafeCast<T?>()
        if (className == "Unit") return Unit.unsafeCast<T?>()
        if (source == null) return null
        eval("""
            window._copyCast_className = className;
            window._copyCast_source = source;
        """)
        val ret = scopedEval("""(function(className, source) {
            var o = eval("new " + className + "()");
            var k = Object.keys(source);
            for (var i = 0; i < k.length; i++) {
                o[k[i]] = source[k[i]];
            } 
            return o;
        })(window._copyCast_className, window._copyCast_source)""").unsafeCast<T?>()
        if (ret is AfterCopyCast) {
            ret.afterCopyCast()
        }
        return ret
    }

    /**
     * Internal. Used to initiate thread run.
     */
    private fun workerMain() {
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