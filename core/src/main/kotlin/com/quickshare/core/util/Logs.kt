package com.quickshare.core.util

/**
 * Logging facade for the whole app.
 *
 * The default sink is a no-op, so a release build emits **nothing** unless the application
 * explicitly installs a sink (which it only does when `BuildConfig.DEBUG` is true). That makes
 * "no debug logging in the production APK" a property of the code rather than something a
 * ProGuard rule has to get right, and it is directly unit-testable.
 */
object Logs {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, throwable: Throwable?)
    }

    private val noop = Sink { _, _, _, _ -> }

    @Volatile
    var sink: Sink = noop

    /** True when logging is discarded. Release builds keep this true by never installing a sink. */
    val isSilent: Boolean get() = sink === noop

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        sink.log(Level.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        sink.log(Level.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        sink.log(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        sink.log(Level.ERROR, tag, message, throwable)

    /** Restores silence. Used by tests and by the application on shutdown. */
    fun reset() {
        sink = noop
    }
}
