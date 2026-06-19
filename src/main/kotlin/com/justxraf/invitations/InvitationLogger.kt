package com.justxraf.invitations
/**
 * Tiny logging SPI accepted by [InvitationManager.Builder.logger], so the core does not depend on
 * SLF4J or any concrete logging framework. Adapt your platform logger via [fromJul] or a lambda.
 */
fun interface InvitationLogger {
    /** Severity of a log record. */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** The single primitive every logger implements; the level/convenience methods delegate here. */
    fun log(level: Level, message: String, throwable: Throwable?)

    /** Log at [Level.DEBUG]. */
    fun debug(message: String) = log(Level.DEBUG, message, null)
    /** Log at [Level.INFO]. */
    fun info(message: String) = log(Level.INFO, message, null)
    /** Log at [Level.WARN]. */
    fun warn(message: String) = log(Level.WARN, message, null)
    /** Log at [Level.ERROR], optionally with a [throwable]. */
    fun error(message: String, throwable: Throwable? = null) = log(Level.ERROR, message, throwable)

    companion object {
        /** A logger that discards everything. The default when none is configured. */
        @JvmField
        val Noop: InvitationLogger = InvitationLogger { _, _, _ -> }

        /** Adapt a `java.util.logging.Logger`, mapping levels to JUL's `FINE`/`INFO`/`WARNING`/`SEVERE`. */
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
/** [InvitationObserver] that logs every lifecycle transition at INFO via an [InvitationLogger]. */
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
