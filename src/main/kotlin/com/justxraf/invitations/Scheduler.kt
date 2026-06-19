package com.justxraf.invitations
interface Scheduler {
interface Cancellable {
        fun cancel()
    }
fun now(): Long = System.currentTimeMillis()
fun runOnMainThread(block: () -> Unit)
fun runLater(delayMillis: Long, block: () -> Unit): Cancellable
}
