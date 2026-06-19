package com.justxraf.networkapi.invitations.examples;

import com.justxraf.networkapi.invitations.AcceptResult;
import com.justxraf.networkapi.invitations.BasicInvitation;
import com.justxraf.networkapi.invitations.CancelReason;
import com.justxraf.networkapi.invitations.DuplicatePolicy;
import com.justxraf.networkapi.invitations.InvitationManager;
import com.justxraf.networkapi.invitations.Invitations;
import com.justxraf.networkapi.invitations.JavaInvitationHandler;
import com.justxraf.networkapi.invitations.Scheduler;
import com.justxraf.networkapi.invitations.SelfInvitePolicy;
import com.justxraf.networkapi.invitations.SortOrder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Copy-paste Java examples for every primary operation, mirroring {@code KotlinExamples}. Compiling
 * this file is also the Java source-compatibility smoke test: it exercises the {@code @JvmStatic}
 * factory, {@code @JvmOverloads} query variants, the {@link JavaInvitationHandler} base, and
 * pattern-matching over the sealed result types from Java.
 */
@SuppressWarnings("unused")
public final class JavaExamples {

    private JavaExamples() {}

    /** A handler that overrides only the hooks it needs (the rest are no-ops via the base class). */
    static final class Handler extends JavaInvitationHandler<BasicInvitation> {
        @Override public void onSend(BasicInvitation invitation) { /* message both parties */ }
        @Override public void onAccept(BasicInvitation invitation) { /* apply the effect */ }
        @Override public void onCancel(BasicInvitation invitation, CancelReason reason) { /* log reason */ }
        @Override public void onExpiryWarning(BasicInvitation invitation, long remainingMillis) {
            // "your invite expires in " + (remainingMillis / 1000) + "s"
        }
        @Override public String validate(BasicInvitation invitation, List<? extends BasicInvitation> existing) {
            for (BasicInvitation e : existing) {
                if (e.getInvitedId().equals(invitation.getInvitedId())) return "already.invited";
            }
            return null;
        }
    }

    /** Build a manager with the common production options. */
    public static InvitationManager<BasicInvitation> buildManager(Scheduler scheduler) {
        return InvitationManager.<BasicInvitation>builder(new Handler(), scheduler)
                .maxPerInviter(5)
                .maxPerInvited(10)
                .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
                .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
                .selfInvitePolicy(SelfInvitePolicy.REJECT)
                .expiryWarningOffsetsMillis(10_000L, 5_000L)
                .build();
    }

    /** send — build an invitation with a Duration TTL and branch on the typed result. */
    public static void send(InvitationManager<BasicInvitation> manager, UUID inviter, UUID invited) {
        BasicInvitation invite = Invitations.between(inviter, invited, Duration.ofMinutes(2));
        InvitationManager.SendResult result = manager.send(invite);
        if (result instanceof InvitationManager.SendResult.Accepted accepted) {
            UUID id = accepted.getInvitationId();
        } else if (result instanceof InvitationManager.SendResult.Duplicate duplicate) {
            UUID existing = duplicate.getExistingId();
        } else if (result instanceof InvitationManager.SendResult.LimitReached limit) {
            int cap = limit.getLimit();
        } else if (result instanceof InvitationManager.SendResult.CooldownActive cooldown) {
            long remaining = cooldown.getRemainingMillis();
        } else if (result.equals(InvitationManager.SendResult.SelfInvite.INSTANCE)) {
            // inviter invited themselves
        }
    }

    /** accept by id (boolean) and the idempotent variant. */
    public static void accept(InvitationManager<BasicInvitation> manager, UUID invitationId) {
        boolean ok = manager.accept(invitationId);

        AcceptResult result = manager.acceptDetailed(invitationId);
        if (result instanceof AcceptResult.Accepted accepted) {
            UUID id = accepted.getInvitationId();
        } else if (result.equals(AcceptResult.NotFound.INSTANCE)) {
            // unknown or already consumed — safe to ignore
        }
    }

    /** deny, cancel, and accept-from-a-name. */
    public static void terminal(InvitationManager<BasicInvitation> manager, UUID inviter, UUID invited, UUID id) {
        manager.deny(id);
        manager.cancel(id);
        manager.accept(inviter, invited); // accept "from a name"
    }

    /** Bulk and clear operations. */
    public static void bulkAndClear(InvitationManager<BasicInvitation> manager, UUID player) {
        manager.denyAll(player);
        manager.cancelAllFrom(player);
        manager.clearAllFor(player, CancelReason.PLAYER_QUIT);
        manager.clearAsInviter(player, CancelReason.ADMIN_CLEARED);
        manager.clearAsInvited(player); // default reason
    }

    /** Queries and counts. */
    public static void queries(InvitationManager<BasicInvitation> manager, UUID inviter, UUID invited) {
        List<? extends BasicInvitation> forInvited = manager.getInvitesFor(invited, SortOrder.NEWEST_FIRST);
        List<? extends BasicInvitation> fromInviter = manager.getInvitesFrom(inviter, SortOrder.OLDEST_FIRST);
        BasicInvitation newest = manager.getMostRecentFor(invited);
        BasicInvitation specific = manager.getInvite(inviter, invited);
        List<? extends BasicInvitation> between = manager.getInvitesBetween(inviter, invited);
        List<? extends BasicInvitation> all = manager.all(SortOrder.NEWEST_FIRST);

        int forCount = manager.countFor(invited);
        int fromCount = manager.countFrom(inviter);
        int pending = manager.pendingCount();
    }

    /** Lifecycle: rehydrate on enable, shutdown on disable. */
    public static void lifecycle(InvitationManager<BasicInvitation> manager) {
        int stillPending = manager.rehydrate();
        manager.shutdown();
    }
}
