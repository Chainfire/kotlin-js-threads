@file:Suppress(
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Complex asynchronous [Thread]. Subclass and implement [run] to use.
 */
abstract class ComplexThread<Args, Result> : Thread<Args, Result>() {
    override fun onCloseRequest() { }

    override fun runWrapper(args: dynamic) {
        GlobalScope.launch(coroutineExceptionHandler()) {
            runHelper(args, postResult = false, close = false) {
                run(it)
                null
            }
        }
    }

    /**
     * Implement to run the [Thread]s main code block. *All* coroutine
     * constructs are permitted inside. The thread *does not* end at return,
     * you *must* call [close][eu.chainfire.kjs.threads.Thread.close] or
     * [postResult][eu.chainfire.kjs.threads.Thread.postResult] manually.
     * If you need to *transfer* objects inside the return object,
     * use [postResult][eu.chainfire.kjs.threads.Thread.postResult].
     * @param args Argument object passed to [start][eu.chainfire.kjs.threads.start]
     * @return Result object returned to parent thread
     */
    protected abstract suspend fun run(args: Args?)
}