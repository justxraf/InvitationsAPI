package com.justxraf.invitations
fun interface InvitationLogger {
enum class Level { DEBUG, INFO, WARN, ERROR }
fun log(level: Level, message: String, throwable: Throwable?)

    fun debug(message: String) = log(Level.DEBUG, message, null)
    fun info(message: String) = log(Level.INFO, message, null)
    fun warn(message: String) = log(Level.WARN, message, null)
    fun error(message: String, throwable: Throwable? = null) = log(Level.ERROR, message, throwable)

    companion object {
@JvmField
        val Noop: InvitationLogger = InvitationLogger { _, _, _ -> }
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
