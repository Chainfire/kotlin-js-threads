@file:Suppress(
    "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "unused" // This is a library class
)

package eu.chainfire.kjs.threads

import kotlinx.coroutines.await
import kotlin.js.Promise

// from "setimmediate" npm module
external fun setImmediate(callback: dynamic)

/* we couldn't use @JsModule with "setimmediate" on the external
   declaration, as the module does not export the function but
   rather polyfills global; still seems like there should be
   a better call or annotation to do this? */
private var requireSetImmediateCalled = js("(function() { require('setimmediate'); return true })()")
