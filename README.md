# Kotlin/JS: Threads

Worker-based threads  

## License

Copyright &copy; 2020 Jorrit *Chainfire* Jongma

This code is released under the [Apache License version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Disclaimer

At the time of writing I've used Kotlin for about 3 weeks (and started
only a few days in), so there may be room for improvement. This was written 
for a hobby project and shared because "why not", updates are dependent 
on my own needs and PR's.

The way I use it and the resulting design may not fit your use-case.

## Documentation

Public and protected members have inline KDoc available, but basic
usage should be learned from this document.

Online Dokka-generated reference can be found [here](https://chainfire.github.io/kotlin-js-threads/-threads/index.html).

IDEA regularly doesn't automatically include the sources jar even though it
does download it, and as a javadoc jar isn't provided, this means you don't 
get inline documentation. To solve this, after adding the dependency on this
library and loading gradle changes, go to *File*, *Project Structure*, 
*Libraries*, and add the *\*-sources.jar* file manually. This will enable 
popup documentation (Ctrl+Q) and allow you to click through declarations to their source.

## Browser support

Not all browsers may support threads. The code is tested against the
latest (desktop, Windows) versions of Chrome and Firefox.

This package was created to use (Dedicated) Workers in the browser.
SharedWorkers, ServiceWorkers and node.js have not been tested or
intended.

## Browser thread basics

A [Worker](https://developer.mozilla.org/en-US/docs/Web/API/Worker) thread is
loaded from a URL and shares neither code nor state with the creating page.

Data is exchanged between threads using messages. The browser uses the 
[structured clone algorithm](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm)
to *copy* these messages between threads. Changing the data in one thread
does not modify the data in another. Only primitive types, (typed) arrays, 
and basic objects consisting of those elements can be copied this way.

[ArrayBuffers](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/ArrayBuffer)
and [TypedArrays](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/TypedArray)
are special cases. These *can* be *transferred* rather than copied, which
changes the ownership of the array from one thread to another rather than
copying its contents.

Additionally, [SharedArrayBuffers](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer)
and [TypedArrays](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/TypedArray)
created on them *do* share the same backing memory between threads. However,
these are not supported in all browsers and contexts, and special care 
needs to be taken to access this memory. See my [kotlin-js-sharedmemory](https://github.com/Chainfire/kotlin-js-sharedmemory)
package for declarations. `TODO link to sharedobjects package when released`

Due to how the JavaScript loop works, even disregarding copying objects,
message passing is not particularly fast. It's fast enough for most generic
purposes, but when writing high performance code you should keep in mind
that even on a very fast modern CPU you can only pass a handful of messages
per millisecond at best.

### How the basics apply to this library

This library attempts abstract workers away into the `Thread<Args, Result>`
class.

Under the hood, it attemps to determine which scripts are needed, and loads
these and itself into the worker thread (which scripts to load can be 
manually overridden using [ThreadManager.configureImports](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread-manager/configure-imports.html)).
It then arrives at your module's *main* just as it would in the browser. To
switch to thread processing a single call is needed:

```
fun main() {
    // If needed, call ThreadManager.configureImports() here

    // Due to the presence of the callback, this call will
    // automatically switch to thread processing if called from
    // a worker
    if (!Thread.isMainThread { eval(it) }) return

    // your normal code here
}
```

The *eval* callback is needed to let the library execute code in your module's
context. After that first call you do *not* need to supply this callback again
elsewhere when checking if executing on the main thread or not.

Messages are used to update thread state between the objects in the parent
and child threads, as well as to pass along arguments, the result, and
possibly exceptions. 

Various methods accept a *transfer* list for objects that need to be
transferred rather than copied. This is always an array, and the objects
passed may be fields of the passed argument object rather than the argument
object itself. In practise, you will only use this with [MessagePorts](https://developer.mozilla.org/en-US/docs/Web/API/MessagePort),
[ArrayBuffers](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/ArrayBuffer)
and [TypedArrays](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/TypedArray).
Note that you do *not* need to *transfer* [SharedArrayBuffers](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer)
nor [TypedArrays](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/TypedArray)
that have a [SharedArrayBuffer](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer)
as backing memory.

The library attempts to recreate basic Kotlin classes on both ends using an
algorithm called *CopyCast*, which basically takes a JavaScript object and
tries to shoehorn its data into an empty Kotlin object of the requested type.
This is *not* proper serialization and depends on implementation details of
Kotlin, in an attempt to keep things reasonably performant for high throughput
applications.

Before the new Kotlin JS-IR compiler *CopyCast* wasn't even needed
as we could cast Kotlin objects directly as long as the underlying JavaScript
object had the same structure and that mostly worked. The JS-IR compiler
however sprinkles additional type checks all over the place, and sooner or
later you'd hit one causing an exception. The new method is only half as fast, 
but both more correct and more compatible.

## Getting started

### Declarations

The easiest way to import everything is:

```
import eu.chainfire.kjs.threads.*
```

This library provides three similar basic thread building blocks, all based on `Thread<Args, Result>`:

* [SyncThread<Args, Result>](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-sync-thread/index.html), the simplest variant, [run](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-sync-thread/run.html) is called with *Args* parameters and returns *Result*
* [AsyncThread<Args, Result>](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-async-thread/index.html), supports suspending calls, but still ends the thread when [run](Thttps://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-async-thread/run.html) returns
* [ComplexThread<Args, Result>](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-complex-thread/index.html), supports all constructs inside [run](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-complex-thread/run.html), but [postResult](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/post-result.html) and/or [close](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/close.html) must be called manually

#### Args and Result

The `Args` and `Result` types can be `Unit` or anything that translates 
directly to a JavaScript primitive (`Int`, `Array<String>`, `Int32Array`, etc)
*excepting* `dynamic`, or a (single, *not* Array<\*>) basic Kotlin class. Such
a basic Kotlin class should in turn only contain *public var* of the same
primitives *including* `dynamic`, and basic Kotlin classes (single *or* 
(small) Array<\*>). However, these sub-objects and sub-array-of-objects need
to be registered for reconstruction.

Avoid object hierarchies for `Args` and `Result`, do not use delegates or 
declare functions without extensive testing (with the less forgiving IR
compiler).

Under the hood everything is `dynamic`, and the `Args` and `Result` classes
primarily exist to provide code completion and prevent fat-finger errors.
Unfortunately due to limitations of Kotlin itself you cannot use `dynamic`
as type for `Args` or `Result`, so if you want to use your own plain
JavaScript objects you have to use a wrapper such as `DynamicArgs` in the
example below.

Examples:

```
class BasicArgs(
    var i: Int,
    var s: String,
    var f: Array<Float> 
)

class ComplexArgs(
    var i: Int,
    var o: BasicArgs,
    var a: Array<BasicArgs>
) : Thread.Args() {
    override fun register() {
        obj(::o)
        array(::a)
    }
}

class DynamicArgs (
    var d: dynamic
)
```

#### Threads

The three variants are very similar, these examples should show their differences:

```
class MySyncThread : SyncThread<Int, Int>() {
    override fun run(args: Int?): Int? {
        if (args == null) return null

        // thread ends at return
        return args + 1
    }
}
```

```
class MyAsyncThread : AsyncThread<Int, Int>() {
    override suspend fun run(args: Int?): Int? {
        if (args == null) return null
        
        // suspending function, but control flow does not escape this block
        Thread.sleep(1000)
 
        // thread ends at return
        return args + 1
    }
}
```

```
class MyComplexThread : ComplexThread<Int, Int>() {
    override suspend fun run(args: Int?) {
        if (args == null) {
            postResult(null, true)
            return
        }

        // control flow escapes this block
        self?.setTimeout({
            // call postResult (or close) manually to end thread
            postResult(args + 1, true)
        })

        // thread does not end here
    }
}
```

Note that both `Args` and `Result` can both always be null.

### Starting threads

After creating a thread object, you launch it by calling the [start](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/start.html)
extension function, with the arguments of class `Args` (or null) as the
first parameter.

Starting threads can take a while - it generally takes several iterations
of the JavaScript loop. Don't be surprised if it takes a
second or more. There's nothing we can do about that, it's just how
the browser works. The design of your code should take this into account.

Additionally, some browsers have limits on the amount of threads you can
create, so don't go creating three dozen threads that run simultaneously.

Promises are provided that resolve either when the work has started or when
it has been completed.

```
fun main() {
    if (!Thread.isMainThread { eval(it) }) return

    // normal
    val t = MySyncThread()
    t.start(1)
    t.runningPromise().then {
        console.log("work started")
    }
    t.completionPromise().then {
        console.log("work complete:", it)
    }

    // coroutines for a different flow
    GlobalScope.launch {
        val t2 = MySyncThread()
        t2.start(3)
        t2.awaitRunning()
        console.log("work started")
        console.log("work complete:", t2.awaitCompletion())
    }
}
```

### ThreadScheduler

As starting threads is slow, [ThreadScheduler](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread-scheduler/index.html)
is provided to manage your threads as if they were tasks. You can create one 
(or multiple) to setup thread pools. 

To use the scheduler, pass it to [start](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/start.html)
with the `threadScheduler` argument. A `schedulePriority` argument is also
available, lower values execute first. 

```
fun main() {
    if (!Thread.isMainThread { eval(it) }) return

    GlobalScope.launch {
        val sched = ThreadScheduler(4)
        sched.start().then {
            console.log("all scheduler threads started")
        }.await()

        for (counter in 1..100) {
            val t = MySyncThread()
            t.start(counter, threadScheduler = sched)
            t.completionPromise().then {
                console.log("task $i result: $it")
            }
        }
    }
}
```

While this construct allows you to launch tasks much quicker than creating
individual threads as need demands, for a large number of very short tasks
the throughput is still limited by messaging speed, and using more than 3 or
4 threads to run them often does not actually make anything any faster.

Note that you can create a pool of 0 threads, which will run all tasks on the
main thread. With advanced thread usage (atomics/synchronization/mutexes) this
can easily lead to deadlocks and is obviously not a great way to do things,
but it does occasionally come in handy for debugging purposes.

### Exceptions

Uncaught exceptions in your threads are logged to console inside the thread,
and passed back to the parent thread by message, [state](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/index.html#eu.chainfire.kjs.threads/Thread/state/#/PointingToDeclaration/) 
is set to `State.ERROR`, and [throwable](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/index.html#eu.chainfire.kjs.threads/Thread/throwable/#/PointingToDeclaration/)
is set with the exception.

Any unresolved promises will reject with the exception. You can use the 
`reject` parameter or `catch` handler on the promises, or if you're using
`await` wrap it inside `try..catch`.  

The main thread attempts to recreate the original exception class and set its
message, but if it fails it may fall back to `RuntimeException`, `Error` or
`Throwable` class. Cause and stacktrace are always lost, which can make
debugging a bit of an effort.

## Further reading

This document does not cover all possible functionality, reading the source
(which isn't all that long) is always recommended.

You may want to pass your own messages between the parent and child thread.
Use [messageReceiver](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/index.html#eu.chainfire.kjs.threads/Thread/messageReceiver/#/PointingToDeclaration/)
to read incoming messages, and [post](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/post.html) 
to send them. Keep in mind that due to how the JavaScript loop works you may
not receive messages until the thread becomes idle, and may need to
call [yield](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/-companion/yield.html) 
or [sleep](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/-companion/sleep.html)
to circumvent this. 

When [close](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/close.html)
is called in the parent thread (without `immediate = true` argument), a
[SyncThread<Args, Result>](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-sync-thread/index.html)
will close as soon as possible. The other variants depend on you to detect
`State.CLOSING`. You will also receive a null message from [messageReceiver](https://chainfire.github.io/kotlin-js-threads/-threads/eu.chainfire.kjs.threads/-thread/index.html#eu.chainfire.kjs.threads/Thread/messageReceiver/#/PointingToDeclaration/)
when that state is set.

## Related packages

[kotlin-js-sharedmemory](https://github.com/Chainfire/kotlin-js-sharedmemory): Kotlin/JS: External declarations related to shared memory

`TODO link to sharedobjects package when released`

## Kotlin DSL

```
repositories {
    maven("https://dl.bintray.com/chainfire/maven")
}

dependencies {
    implementation("eu.chainfire:kotlin-js-threads:1.0.0")
}
```

This package also uses the npm `setimmediate` polyfill module, which should
be used automatically. In case you need to include it manually:

```
dependencies {
    implementation(npm("setimmediate", "^1.0.5"))
}
```