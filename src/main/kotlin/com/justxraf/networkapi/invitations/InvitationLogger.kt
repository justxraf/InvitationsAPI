package com.justxraf.networkapi.invitations

/**
 * A tiny logging seam so the core stays dependency-free (no SLF4J, no `java.util.logging` coupling)
 * while still letting hosts route the manager's diagnostics into their own logging stack. Pass an
 * implementation to the builder; adapt to SLF4J, `java.util.logging`, Bukkit's `Plugin.getLogger()`,
 * or anything else in a few lines.
 *
 * The manager logs at most one line per lifecycle transition (via the built-in [LoggingObserver]),
 * plus a [Level.ERROR] line whenever a handler hook, observer, or other callback throws (see
 * [LifecycleErrorPolicy]). Defaults to [Noop], which discards everything.
 */
fun interface InvitationLogger {
    /** Levels coarse enough to map onto any backend. */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** Emit one log record. [throwable] is non-null only for errors carrying a cause. */
    fun log(level: Level, message: String, throwable: Throwable?)

    fun debug(message: String) = log(Level.DEBUG, message, null)
    fun info(message: String) = log(Level.INFO, message, null)
    fun warn(message: String) = log(Level.WARN, message, null)
    fun error(message: String, throwable: Throwable? = null) = log(Level.ERROR, message, throwable)

    companion object {
        /** Discards every record. The manager's default when no logger is configured. */
        @JvmField
        val Noop: InvitationLogger = InvitationLogger { _, _, _ -> }

        /**
         * Routes records to a [java.util.logging.Logger], mapping [Level] onto JUL levels
         * (DEBUG→FINE, INFO→INFO, WARN→WARNING, ERROR→SEVERE). Convenient over a Bukkit
         * `plugin.getLogger()` or any JUL logger.
         */
        @JvmStatic
        fun fromJul(logger: java.util.logging.Logger): InvitationLogger =
            InvitationLogger { level, message, throwable ->
                val julLevel = when (level) {
                    Level.DEBUG -> java.util.logging.Level.FINE
                    Level.INFO -> java.util.logging.Level.INFO
                    Level.WARN -> java.util.logging.Level.WARNING
                    Level.ERROR -> java.util.logging.Level.SEVERE
                }
                if (throwable != null) logger.log(julLevel, message, throwable)
                else logger.log(julLevel, message)
            }
    }
}

/**
 * Built-in [InvitationObserver] that logs one [InvitationLogger.Level.INFO] line per lifecycle
 * transition. Registered automatically when a logger is configured on the manager; you normally
 * don't construct it yourself.
 */
class LoggingObserver<T : Invitation>(private val logger: InvitationLogger) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) {
        val inv = event.invitation
        val suffix = when {
            event.cancelReason != null -> " reason=${event.cancelReason}"
            event.replacedId != null -> " replaced=${event.replacedId}"
            else -> ""
        }
        logger.info("invitation ${event.action} id=${inv.id} inviter=${inv.inviterId} invited=${inv.invitedId}$suffix")
    }
}
