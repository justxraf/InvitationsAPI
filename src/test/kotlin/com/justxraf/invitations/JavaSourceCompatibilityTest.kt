package com.justxraf.invitations

import com.justxraf.invitations.compat.JavaConsumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Java source-compatibility check. The `JavaConsumer` Java class under `src/test/java` is compiled
 * against the public API and *executed* here, so this guards both source compatibility (it must keep
 * compiling) and runtime Java→Kotlin interop (sealed-result matching, `INSTANCE` singletons,
 * `@JvmOverloads` defaults, the Java handler base class).
 *
 * The companion `src/main/java/.../JavaExamples.java` is a compile-only documentation sample; this is
 * the one that actually runs the API from Java.
 */
class JavaSourceCompatibilityTest {

    private val inlineScheduler = object : Scheduler {
        override fun now() = 0L
        override fun runOnMainThread(block: () -> Unit) = block()
        override fun runLater(delayMillis: Long, block: () -> Unit) =
            object : Scheduler.Cancellable { override fun cancel() {} }
    }

    @Test
    fun `a java consumer drives the full lifecycle through the public api`() {
        val summary = JavaConsumer.exercise(inlineScheduler)
        val fields = summary.split(",").associate { it.split("=").let { (k, v) -> k to v } }

        assertEquals("accepted", fields["first"], "first send should be accepted")
        assertEquals("true", fields["dup"], "second send to same pair should be a Duplicate")
        assertEquals("true", fields["self"], "self-invite should match the SelfInvite singleton")
        assertEquals("true", fields["accepted"], "acceptDetailed should return Accepted")
        assertEquals("true", fields["denyNotFound"], "denyDetailed on a random id should be NotFound")
        assertEquals("true", fields["cancelled"], "cancelDetailed should return Cancelled")
        assertEquals("0", fields["pending"], "everything terminated, nothing pending")
        // Hooks fired synchronously under the inline scheduler.
        assertEquals("1", fields["accepts"])
        assertEquals("1", fields["cancels"])
        assertTrue((fields["sends"]?.toInt() ?: 0) >= 2, "onSend fired for the two distinct accepted invites")
    }
}
