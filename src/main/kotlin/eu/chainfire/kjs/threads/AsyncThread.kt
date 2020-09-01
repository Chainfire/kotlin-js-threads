@file:Suppress(
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Asynchronous [Thread]. Subclass and implement [run] to use.
 */
abstract class AsyncThread<Args, Result> : Thread<Args, Result>() {
    override fun onCloseRequest() { }

    override fun runWrapper(args: dynamic) {
        GlobalScope.launch(coroutineExceptionHandler()) {
            runHelper(args) {
                run(it)
            }
        }
    }

    /**
     * Implement to run the [Thread]s main code block. You *may* use
     * coroutines inside, but they all need to be awaited/complete before
     * the function returns. The thread ends at return. If you need to
     * *transfer* objects inside the return object, use [postResult][eu.chainfire.kjs.threads.Thread.postResult].
     * @param args Argument object passed to [start][eu.chainfire.kjs.threads.start]
     * @return Result object returned to parent thread
     */
    protected abstract suspend fun run(args: Args?): Result?
}