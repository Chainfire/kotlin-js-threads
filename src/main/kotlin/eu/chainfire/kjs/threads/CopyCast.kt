package eu.chainfire.kjs.threads

import kotlin.reflect.KClass

/**
 * CopyCast helper class. This is used to copy [Thread.Args] based objects
 * between [Thread]s. See *Args and Result* section of the [README](https://github.com/Chainfire/kotlin-js-threads/blob/master/README.md)
 * for notes on compatibility.
 */
object CopyCast {
    /**
     * Callback interface for reconstruction
     */
    interface OnCopyCast {
        /**
         * Called during [wrap]
         */
        fun beforeCopyCast()

        /**
         * Called during [unwrap] ([copyCast])
         */
        fun afterCopyCast()
    }

    /**
     * Determines JavaScript class constructor name for [copyCast] purposes
     * @param clazz Class to determine name for
     * @return JavaScript class constructor name
     */
    fun classNameFor(clazz: KClass<*>): String? {
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
     * object, and calls [OnCopyCast.afterCopyCast] on it if available
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
        val ret = ThreadManager.scopedEval("""(function(className, source) {
            var o = eval("new " + className + "()");
            var k = Object.keys(source);
            for (var i = 0; i < k.length; i++) {
                o[k[i]] = source[k[i]];
            } 
            return o;
        })(window._copyCast_className, window._copyCast_source)""").unsafeCast<T?>()
        if (ret is OnCopyCast) {
            ret.afterCopyCast()
        }
        return ret
    }

    /**
     * Prepares an object wrapper for [CopyCast] purposes. Calls
     * [OnCopyCast.beforeCopyCast] on members if available
     * @param obj Object to wrap
     * @return Wrapped object
     */
    inline fun <reified T> wrap(obj: T): dynamic {
        return wrap(obj, classNameFor(T::class))
    }

    /**
     * Internal. Use the other overload.
     */
    fun wrap(obj: dynamic, className: String?): dynamic {
        if (obj == null) return null
        if (className == null) return obj
        val o = js("{}")
        o["_copyCast_className"] = className
        if (obj !is OnCopyCast) {
            o["_copyCast_object"] = obj
        } else {
            val keys = js("Object.keys(obj)").unsafeCast<Array<Any>>()
            val copy = js("{}")
            for (k in keys) {
                copy[k] = obj[k]
            }
            obj.beforeCopyCast()
            for (k in keys) {
                val d = copy[k]
                copy[k] = obj[k]
                obj[k] = d
            }
            o["_copyCast_object"] = copy
        }
        return o
    }

    /**
     * Reconstructs a [wrap]ped object using [copyCast]
     */
    fun <T> unwrap(obj: dynamic): T? {
        if (obj == null) return null
        if (obj["_copyCast_className"] === undefined || obj["_copyCast_className"] === null) return obj.unsafeCast<T>()
        if (obj["_copyCast_object"] === undefined || obj["_copyCast_object"] === null) return obj.unsafeCast<T>()
        return copyCast(obj["_copyCast_className"].unsafeCast<String>(), obj["_copyCast_object"])
    }
}

