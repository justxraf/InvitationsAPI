package com.justxraf.invitations
data class RejectionReason(
    val code: Code,
    val messageKey: String,
    val fallbackMessage: String,
    val args: Map<String, String> = emptyMap(),
) {
enum class Code {
SELF_INVITE,
TARGET_OFFLINE,
TARGET_IGNORING_INVITER,
ALREADY_IN_SAME_PARTY,
PARTY_FULL,
INVITER_LACKS_PERMISSION,
INVITED_LACKS_PERMISSION,
WORLD_OR_SERVER_RESTRICTED,
RATE_LIMITED,
CUSTOM,
    }
fun renderFallback(): String =
        args.entries.fold(fallbackMessage) { acc, (k, v) -> acc.replace("{$k}", v) }

    companion object {
@JvmStatic
        @JvmOverloads
        fun custom(messageKey: String, fallbackMessage: String, args: Map<String, String> = emptyMap()): RejectionReason =
            RejectionReason(Code.CUSTOM, messageKey, fallbackMessage, args)
    }
}
