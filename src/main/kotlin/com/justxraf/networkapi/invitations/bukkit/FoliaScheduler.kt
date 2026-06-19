package com.justxraf.networkapi.invitations.bukkit

import com.justxraf.networkapi.invitations.Scheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Production [Scheduler] backed by Folia's region-threaded scheduler instead of Bukkit's classic
 * single main-thread scheduler. Like [BukkitScheduler] this is `compileOnly` glue — the core API
 * never references Folia (or Bukkit) types.
 *
 * **Region context decision.** Folia partitions the world into independently-ticking regions, so
 * "the main thread" no longer exists; every task must declare which region owns it. An invitation is
 * a piece of *cross-entity* state (it links an inviter and an invited player who may be in different
 * regions, or offline entirely), so it does not belong to any single region's thread. We therefore
 * run **all** invitation work — both the expiry/warning timers ([runLater]) and the post-transition
 * handler/observer dispatch ([runOnMainThread]) — on the **global region scheduler**
 * (`Server.getGlobalRegionScheduler`), which owns global game state and is the natural home for data
 * that isn't pinned to one region. Handlers that subsequently need to touch a *specific* player or
 * entity (teleport, send a message, modify inventory) must re-dispatch onto that entity's scheduler
 * themselves via `entity.getScheduler().run(plugin, …)`; doing so from inside the handler keeps the
 * region hop explicit and visible at the call site rather than hidden in this adapter.
 *
 * Folia's `GlobalRegionScheduler.runDelayed` uses ticks; we convert from millis the same way
 * [BukkitScheduler] does (1 tick = 50ms, rounded up, minimum 1 tick).
 *
 * This class only needs the Folia API surface present in modern Paper (`Bukkit.getGlobalRegionScheduler`,
 * `isOwnedByCurrentRegion`). On a non-Folia Paper server those still exist and behave like the classic
 * scheduler, so this adapter is safe there too — but prefer [BukkitScheduler] unless you actually run
 * Folia.
 */
class FoliaScheduler(private val plugin: Plugin) : Scheduler {

    override fun runOnMainThread(block: () -> Unit) {
        val global = Bukkit.getGlobalRegionScheduler()
        // If we're already on a region thread that owns global state, run inline to preserve the
        // "run immediately when already on-thread" contract callers rely on for vetoes/ordering.
        if (Bukkit.getServer().isGlobalTickThread) block()
        else global.run(plugin) { block() }
    }

    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable {
        val ticks = ((delayMillis + 49) / 50).coerceAtLeast(1)
        val task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { block() }, ticks)
        return object : Scheduler.Cancellable {
            override fun cancel() {
                task.cancel()
            }
        }
    }
}
