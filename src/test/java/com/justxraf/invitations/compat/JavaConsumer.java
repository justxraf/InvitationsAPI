package com.justxraf.invitations.compat;

import com.justxraf.invitations.AcceptResult;
import com.justxraf.invitations.BasicInvitation;
import com.justxraf.invitations.CancelReason;
import com.justxraf.invitations.CancelResult;
import com.justxraf.invitations.DenyResult;
import com.justxraf.invitations.DuplicatePolicy;
import com.justxraf.invitations.InvitationHandler;
import com.justxraf.invitations.InvitationManager;
import com.justxraf.invitations.Invitations;
import com.justxraf.invitations.JavaInvitationHandler;
import com.justxraf.invitations.Scheduler;
import com.justxraf.invitations.SelfInvitePolicy;
import com.justxraf.invitations.SortOrder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real Java consumer of the public API, compiled and <b>executed</b> from a JUnit test
 * ({@code JavaSourceCompatibilityTest}). Unlike the source-only examples under {@code src/main/java},
 * this verifies Java→Kotlin interop at runtime: sealed-result pattern matching, {@code @JvmOverloads}
 * defaults, {@code INSTANCE} singletons, generic wildcards, and the Java-friendly handler base class.
 *
 * If a future API change breaks Java callers (e.g. a Kotlin default that doesn't surface an overload,
 * or a result type that stops being Java-pattern-matchable), this stops compiling or fails at runtime.
 */
public final class JavaConsumer {

    private JavaConsumer() {}

    /** A handler written against the Java-friendly base class, counting the hooks it receives. */
    public static final class CountingHandler extends JavaInvitationHandler<BasicInvitation> {
        public final AtomicInteger sends = new AtomicInteger();
        public final AtomicInteger accepts = new AtomicInteger();
        public final AtomicInteger denies = new AtomicInteger();
        public final AtomicInteger cancels = new AtomicInteger();

        @Override public void onSend(BasicInvitation invitation) { sends.incrementAndGet(); }
        @Override public void onAccept(BasicInvitation invitation) { accepts.incrementAndGet(); }
        @Override public void onDeny(BasicInvitation invitation) { denies.incrementAndGet(); }
        @Override public void onCancel(BasicInvitation invitation, CancelReason reason) { cancels.incrementAndGet(); }

        @Override
        public String validate(BasicInvitation invitation, List<? extends BasicInvitation> existing) {
            return null; // accept everything
        }
    }

    public static InvitationManager<BasicInvitation> build(Scheduler scheduler, CountingHandler handler) {
        return InvitationManager.<BasicInvitation>builder(handler, scheduler)
                .maxPerInviter(5)
                .maxPerInvited(10)
                .duplicatePolicy(DuplicatePolicy.REJECT_EXISTING)
                .selfInvitePolicy(SelfInvitePolicy.REJECT)
                .expiryWarningOffsetsMillis(5_000L)
                .build();
    }

    /** Drives a full lifecycle and returns a compact summary the Kotlin test asserts against. */
    public static String exercise(Scheduler scheduler) {
        CountingHandler handler = new CountingHandler();
        InvitationManager<BasicInvitation> manager = build(scheduler, handler);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        // Send and pattern-match the sealed result.
        BasicInvitation first = Invitations.between(a, b, Duration.ofMinutes(2));
        InvitationManager.SendResult sent = manager.send(first);
        String firstKind = sent instanceof InvitationManager.SendResult.Accepted ? "accepted" : "other";

        // Duplicate of the same pair is rejected.
        InvitationManager.SendResult dup = manager.send(Invitations.between(a, b, Duration.ofMinutes(2)));
        boolean isDuplicate = dup instanceof InvitationManager.SendResult.Duplicate;

        // Self-invite singleton comparison.
        boolean selfRejected = manager.send(Invitations.between(a, a, Duration.ofMinutes(1)))
                .equals(InvitationManager.SendResult.SelfInvite.INSTANCE);

        // A second, distinct invite to terminate via detailed results.
        BasicInvitation second = Invitations.between(a, c, Duration.ofMinutes(2));
        manager.send(second);

        // @JvmOverloads: call without the optional actor argument.
        AcceptResult accept = manager.acceptDetailed(first.getId());
        boolean accepted = accept instanceof AcceptResult.Accepted;

        DenyResult deny = manager.denyDetailed(UUID.randomUUID());
        boolean denyNotFound = deny.equals(DenyResult.NotFound.INSTANCE);

        CancelResult cancel = manager.cancelDetailed(second.getId());
        boolean cancelled = cancel instanceof CancelResult.Cancelled;

        // Generic wildcard queries and count helpers.
        List<? extends BasicInvitation> forB = manager.getInvitesFor(b, SortOrder.NEWEST_FIRST);
        int pending = manager.pendingCount();

        manager.shutdown();

        return "first=" + firstKind
                + ",dup=" + isDuplicate
                + ",self=" + selfRejected
                + ",accepted=" + accepted
                + ",denyNotFound=" + denyNotFound
                + ",cancelled=" + cancelled
                + ",sends=" + handler.sends.get()
                + ",accepts=" + handler.accepts.get()
                + ",cancels=" + handler.cancels.get()
                + ",forB=" + forB.size()
                + ",pending=" + pending;
    }
}
