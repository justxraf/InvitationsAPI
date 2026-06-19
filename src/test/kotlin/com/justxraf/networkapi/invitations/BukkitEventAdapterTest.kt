package com.justxraf.networkapi.invitations

import com.justxraf.networkapi.invitations.bukkit.EventFiringObserver
import com.justxraf.networkapi.invitations.bukkit.EventFiringVeto
import com.justxraf.networkapi.invitations.bukkit.InvitationAcceptEvent
import com.justxraf.networkapi.invitations.bukkit.InvitationCancelEvent
import com.justxraf.networkapi.invitations.bukkit.InvitationDenyEvent
import com.justxraf.networkapi.invitations.bukkit.InvitationExpireEvent
import com.justxraf.networkapi.invitations.bukkit.InvitationReplaceEvent
import com.justxraf.networkapi.invitations.bukkit.InvitationSendEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Verifies the `bukkit/` event-firing adapters on a real (mocked) Bukkit event bus: [EventFiringVeto]
 * fires the cancellable pre-events and reports cancellation back, and [EventFiringObserver] fires the
 * post-only expire/replace events. These exercise the Bukkit boundary that the server-free core can't.
 */
class BukkitEventAdapterTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Plugin
    private lateinit var captured: MutableList<Event>

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        captured = mutableListOf()
        // Bukkit keys listeners on concrete event types (each has its own static getHandlerList);
        // the abstract InvitationEvent base has none, so register one handler per concrete event.
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler fun on(e: InvitationSendEvent) { captured.add(e) }
                @EventHandler fun on(e: InvitationAcceptEvent) { captured.add(e) }
                @EventHandler fun on(e: InvitationDenyEvent) { captured.add(e) }
                @EventHandler fun on(e: InvitationCancelEvent) { captured.add(e) }
                @EventHandler fun on(e: InvitationExpireEvent) { captured.add(e) }
                @EventHandler fun on(e: InvitationReplaceEvent) { captured.add(e) }
            },
            plugin,
        )
    }

    @AfterEach
    fun tearDown() = MockBukkit.unmock()

    private fun invite() = Invitations.between(UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `veto fires the matching pre-event for each action`() {
        val veto = EventFiringVeto<BasicInvitation>()
        val inv = invite()

        veto.isVetoed(inv, InvitationAction.SENT, null)
        veto.isVetoed(inv, InvitationAction.ACCEPTED, null)
        veto.isVetoed(inv, InvitationAction.DENIED, null)
        veto.isVetoed(inv, InvitationAction.CANCELLED, CancelReason.REVOKED)

        assertEquals(4, captured.size)
        assertInstanceOf(InvitationSendEvent::class.java, captured[0])
        assertInstanceOf(InvitationAcceptEvent::class.java, captured[1])
        assertInstanceOf(InvitationDenyEvent::class.java, captured[2])
        val cancel = assertInstanceOf(InvitationCancelEvent::class.java, captured[3])
        assertEquals(CancelReason.REVOKED, cancel.reason)
    }

    @Test
    fun `cancelling a pre-event is reported back as vetoed`() {
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler fun on(e: InvitationSendEvent) { e.isCancelled = true }
            },
            plugin,
        )
        val veto = EventFiringVeto<BasicInvitation>()
        assertTrue(veto.isVetoed(invite(), InvitationAction.SENT, null))
    }

    @Test
    fun `uncancelled pre-event is not vetoed`() {
        val veto = EventFiringVeto<BasicInvitation>()
        assertFalse(veto.isVetoed(invite(), InvitationAction.ACCEPTED, null))
    }

    @Test
    fun `expired and replaced post-events are not vetoable`() {
        // Post actions have no pre-event; the veto must ignore them and never cancel.
        val veto = EventFiringVeto<BasicInvitation>()
        assertFalse(veto.isVetoed(invite(), InvitationAction.EXPIRED, null))
        assertFalse(veto.isVetoed(invite(), InvitationAction.REPLACED, null))
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `observer fires expire and replace post-events`() {
        val observer = EventFiringObserver<BasicInvitation>()
        val expired = invite()
        val replaced = invite()
        val replacedId = UUID.randomUUID()

        observer.onEvent(LifecycleEvent(expired, InvitationAction.EXPIRED, timestamp = 0L))
        observer.onEvent(
            LifecycleEvent(replaced, InvitationAction.REPLACED, timestamp = 0L, replacedId = replacedId),
        )

        assertEquals(2, captured.size)
        assertInstanceOf(InvitationExpireEvent::class.java, captured[0])
        val replace = assertInstanceOf(InvitationReplaceEvent::class.java, captured[1])
        assertEquals(replacedId, replace.replacedId)
    }

    @Test
    fun `observer ignores pre-transition actions to avoid duplicate events`() {
        val observer = EventFiringObserver<BasicInvitation>()
        observer.onEvent(LifecycleEvent(invite(), InvitationAction.SENT, timestamp = 0L))
        observer.onEvent(LifecycleEvent(invite(), InvitationAction.ACCEPTED, timestamp = 0L))
        assertTrue(captured.isEmpty())
    }
}
