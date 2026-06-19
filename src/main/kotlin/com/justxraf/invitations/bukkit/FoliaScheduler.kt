package com.justxraf.invitations.bukkit

import com.justxraf.invitations.Scheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
/**
 * [Scheduler] for Folia, using region/global scheduling instead of the classic Bukkit scheduler.
 *
 * Invitations are cross-entity state owned by no single region, so all engine work — expiry timers and
 * post-transition dispatch — runs on the **global** region scheduler. Handlers that need to touch a
 * specific player/entity must re-dispatch onto that entity's scheduler themselves. Paper is
 * `compileOnly`; the core never references this class.
 */
class FoliaScheduler(private val plugin: Plugin) : Scheduler {

    override fun runOnMainThread(block: () -> Unit) {
        val global = Bukkit.getGlobalRegionScheduler()
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
