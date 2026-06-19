package com.justxraf.invitations
/**
 * A structured, localizable reason a validation policy rejected a send. Callers should localize via
 * [messageKey] (looking it up in their own message bundle) and fall back to [renderFallback] when the
 * key is missing.
 *
 * @property code machine-readable category of the rejection.
 * @property messageKey translation key for the caller's localization system.
 * @property fallbackMessage human-readable default, with `{name}`-style placeholders.
 * @property args placeholder substitutions applied by [renderFallback].
 */
data class RejectionReason(
    val code: Code,
    val messageKey: String,
    val fallbackMessage: String,
    val args: Map<String, String> = emptyMap(),
) {
    /** Machine-readable category of a [RejectionReason]. */
    enum class Code {
        /** Inviter tried to invite themselves. */
        SELF_INVITE,
        /** The invited player is offline. */
        TARGET_OFFLINE,
        /** The invited player is ignoring the inviter. */
        TARGET_IGNORING_INVITER,
        /** Both players are already in the same party/group. */
        ALREADY_IN_SAME_PARTY,
        /** The target party/group is full. */
        PARTY_FULL,
        /** The inviter lacks the required permission. */
        INVITER_LACKS_PERMISSION,
        /** The invited player lacks the required permission. */
        INVITED_LACKS_PERMISSION,
        /** A world or server restriction forbids this invitation. */
        WORLD_OR_SERVER_RESTRICTED,
        /** A rate limit or cooldown blocked the send. */
        RATE_LIMITED,
        /** An application-specific reason; see [custom]. */
        CUSTOM,
    }

    /** Render [fallbackMessage] with [args] substituted into `{key}` placeholders. */
    fun renderFallback(): String =
        args.entries.fold(fallbackMessage) { acc, (k, v) -> acc.replace("{$k}", v) }

    companion object {
        /** Build a [Code.CUSTOM] rejection with the caller's own message key and fallback. */
        @JvmStatic
        @JvmOverloads
        fun custom(messageKey: String, fallbackMessage: String, args: Map<String, String> = emptyMap()): RejectionReason =
            RejectionReason(Code.CUSTOM, messageKey, fallbackMessage, args)
    }
}
