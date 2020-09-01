@file:Suppress(
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

/**
 * Synchronous [Thread]. Subclass and implement [run] to use.
 */
abstract class SyncThread<Args, Result> : Thread<Args, Result>() {
    override fun runWrapper(args: dynamic) {
        runHelper(args) {
            run(it)
        }
    }

    /**
     * Implement to run the [Thread]s main code block. Do *not* use coroutines
     * inside. The thread ends at return. If you need to *transfer* objects
     * inside the return object, use [postResult][eu.chainfire.kjs.threads.Thread.postResult].
     * @param args Argument object passed to [start][eu.chainfire.kjs.threads.start]
     * @return Result object returned to parent thread
     */
    protected abstract fun run(args: Args?): Result?
}