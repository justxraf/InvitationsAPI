package com.justxraf.networkapi.invitations.bukkit

import com.justxraf.networkapi.invitations.Scheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Production [Scheduler] backed by the real Bukkit scheduler. The only file in the API that touches
 * the server; everything else is pure Kotlin. This is the adapter you'd ship inside NetworkAPI.
 */
class BukkitScheduler(private val plugin: Plugin) : Scheduler {

    override fun runOnMainThread(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block()
        else Bukkit.getScheduler().runTask(plugin, Runnable { block() })
    }

    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable {
        // 1 tick = 50ms; round up, minimum 1 tick.
        val ticks = ((delayMillis + 49) / 50).coerceAtLeast(1)
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { block() }, ticks)
        return object : Scheduler.Cancellable {
            override fun cancel() = task.cancel()
        }
    }
}
