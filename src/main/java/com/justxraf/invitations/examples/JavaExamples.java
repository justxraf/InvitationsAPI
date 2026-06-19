package com.justxraf.invitations.examples;

import com.justxraf.invitations.AcceptResult;
import com.justxraf.invitations.BasicInvitation;
import com.justxraf.invitations.CancelReason;
import com.justxraf.invitations.DuplicatePolicy;
import com.justxraf.invitations.InvitationManager;
import com.justxraf.invitations.Invitations;
import com.justxraf.invitations.JavaInvitationHandler;
import com.justxraf.invitations.Scheduler;
import com.justxraf.invitations.SelfInvitePolicy;
import com.justxraf.invitations.SortOrder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
@SuppressWarnings("unused")
public final class JavaExamples {

    private JavaExamples() {}
static final class Handler extends JavaInvitationHandler<BasicInvitation> {
        @Override public void onSend(BasicInvitation invitation) { /* message both parties */ }
        @Override public void onAccept(BasicInvitation invitation) { /* apply the effect */ }
        @Override public void onCancel(BasicInvitation invitation, CancelReason reason) { /* log reason */ }
        @Override public void onExpiryWarning(BasicInvitation invitation, long remainingMillis) {
        }
        @Override public String validate(BasicInvitation invitation, List<? extends BasicInvitation> existing) {
            for (BasicInvitation e : existing) {
                if (e.getInvitedId().equals(invitation.getInvitedId())) return "already.invited";
            }
            return null;
        }
    }
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
        }
    }
public static void accept(InvitationManager<BasicInvitation> manager, UUID invitationId) {
        boolean ok = manager.accept(invitationId);

        AcceptResult result = manager.acceptDetailed(invitationId);
        if (result instanceof AcceptResult.Accepted accepted) {
            UUID id = accepted.getInvitationId();
        } else if (result.equals(AcceptResult.NotFound.INSTANCE)) {
        }
    }
public static void terminal(InvitationManager<BasicInvitation> manager, UUID inviter, UUID invited, UUID id) {
        manager.deny(id);
        manager.cancel(id);
        manager.accept(inviter, invited);
    }
public static void bulkAndClear(InvitationManager<BasicInvitation> manager, UUID player) {
        manager.denyAll(player);
        manager.cancelAllFrom(player);
        manager.clearAllFor(player, CancelReason.PLAYER_QUIT);
        manager.clearAsInviter(player, CancelReason.ADMIN_CLEARED);
        manager.clearAsInvited(player);
    }
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
public static void lifecycle(InvitationManager<BasicInvitation> manager) {
        int stillPending = manager.rehydrate();
        manager.shutdown();
    }
}
