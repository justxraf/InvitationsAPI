package com.justxraf.invitations.bukkit

import com.justxraf.invitations.Scheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
class BukkitScheduler(private val plugin: Plugin) : Scheduler {

    override fun runOnMainThread(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block()
        else Bukkit.getScheduler().runTask(plugin, Runnable { block() })
    }

    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable {
        val ticks = ((delayMillis + 49) / 50).coerceAtLeast(1)
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { block() }, ticks)
        return object : Scheduler.Cancellable {
            override fun cancel() = task.cancel()
        }
    }
}
