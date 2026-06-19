package com.justxraf.networkapi.invitations

/**
 * A structured, localizable reason a [ValidationPolicy] (or [InvitationManager.send]) rejected an
 * invitation, replacing free-form rejection strings. Each reason carries:
 *  - a stable [code] — a machine-readable enum the caller can `when`/`switch` over without string
 *    matching, and which never changes across translations or releases;
 *  - a [messageKey] — a dotted i18n key (e.g. `"invitation.reject.party_full"`) a caller can feed to
 *    its own message bundle to produce a localized line;
 *  - optional [args] — named placeholders for the message template (e.g. `"limit" -> "5"`), so the
 *    localized string can interpolate the specifics without the policy formatting text itself;
 *  - a [fallbackMessage] — a plain-English default for callers without a bundle, and the text used by
 *    the legacy free-form [InvitationHandler.validate] bridge.
 *
 * This is the "typed rejection model so callers can localize messages safely" from §6: a policy never
 * emits user-facing prose, only data; turning that data into a sentence is the caller's concern.
 */
data class RejectionReason(
    val code: Code,
    val messageKey: String,
    val fallbackMessage: String,
    val args: Map<String, String> = emptyMap(),
) {
    /**
     * Stable rejection codes. Plugins may carry their own custom reasons under [CUSTOM] and
     * disambiguate via [messageKey]; the rest correspond to the built-in [ValidationPolicy] checks.
     */
    enum class Code {
        /** The inviter and invited player are the same. */
        SELF_INVITE,
        /** The invited player is offline and the policy requires them online. */
        TARGET_OFFLINE,
        /** The invited player has the inviter on an ignore/block list. */
        TARGET_IGNORING_INVITER,
        /** The two players are already in the same party/team/island. */
        ALREADY_IN_SAME_PARTY,
        /** The destination party/team/island is already at capacity. */
        PARTY_FULL,
        /** The inviter lacks the permission required to send. */
        INVITER_LACKS_PERMISSION,
        /** The invited player lacks the permission required to be invited. */
        INVITED_LACKS_PERMISSION,
        /** The invite is disallowed in the current world or on the current server. */
        WORLD_OR_SERVER_RESTRICTED,
        /** A configured rate limit (per inviter, per invited, or per pair) was exceeded. */
        RATE_LIMITED,
        /** A plugin-specific reason; see [messageKey]. */
        CUSTOM,
    }

    /**
     * Render [fallbackMessage] with [args] interpolated into `{name}` placeholders — a last-resort
     * formatter for callers without an i18n bundle. Localizing callers should ignore this and resolve
     * [messageKey] + [args] through their own message system instead.
     */
    fun renderFallback(): String =
        args.entries.fold(fallbackMessage) { acc, (k, v) -> acc.replace("{$k}", v) }

    companion object {
        /** Convenience for a plugin-defined rejection that isn't one of the built-in [Code]s. */
        @JvmStatic
        @JvmOverloads
        fun custom(messageKey: String, fallbackMessage: String, args: Map<String, String> = emptyMap()): RejectionReason =
            RejectionReason(Code.CUSTOM, messageKey, fallbackMessage, args)
    }
}
