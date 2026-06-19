package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
class AsyncStore<T : Invitation> @JvmOverloads constructor(
    private val delegate: InvitationStore<T>,
    private val onError: (Throwable) -> Unit = {},
    threadName: String = "invitations-async-store",
) : InvitationStore<T> {

    private val queue = LinkedBlockingQueue<Op<T>>()

    @Volatile private var running = true

    private val worker = Thread({ runLoop() }, threadName).apply {
        isDaemon = true
        start()
    }

    override fun load(): List<T> {
        flush()
        return delegate.load()
    }

    override fun save(invitation: T) { enqueue(Save(invitation)) }
    override fun remove(id: UUID) { enqueue(Remove(id)) }
    override fun removeAll(ids: Collection<UUID>) { if (ids.isNotEmpty()) enqueue(RemoveAll(ids.toList())) }
    override fun replace(old: UUID, new: T) { enqueue(Replace(old, new)) }
fun flush() {
        if (!running) return
        val done = CountDownLatch(1)
        queue.add(Flush(done))
        done.await()
    }

    override fun close() {
        if (!running) return
        running = false
        queue.add(Stop)
        worker.join(TimeUnit.SECONDS.toMillis(30))
        delegate.close()
    }

    private fun enqueue(op: Op<T>) {
        check(running) { "AsyncStore is closed" }
        queue.add(op)
    }

    private fun runLoop() {
        while (true) {
            val op = queue.take()
            if (op is Stop) { drainRemaining(); return }
            applyOp(op)
        }
    }
private fun drainRemaining() {
        val rest = ArrayList<Op<T>>()
        queue.drainTo(rest)
        for (op in rest) if (op !is Stop) applyOp(op)
    }

    private fun applyOp(op: Op<T>) {
        when (op) {
            is Save -> apply { delegate.save(op.invitation) }
            is Remove -> apply { delegate.remove(op.id) }
            is RemoveAll -> apply { delegate.removeAll(op.ids) }
            is Replace -> apply { delegate.replace(op.old, op.new) }
            is Flush -> op.done.countDown()
            is Stop -> {}
        }
    }

    private inline fun apply(write: () -> Unit) {
        try {
            write()
        } catch (t: Throwable) {
            try { onError(t) } catch (_: Throwable) {}
        }
    }

    private sealed interface Op<out T : Invitation>
    private class Save<T : Invitation>(val invitation: T) : Op<T>
    private class Remove(val id: UUID) : Op<Nothing>
    private class RemoveAll(val ids: Collection<UUID>) : Op<Nothing>
    private class Replace<T : Invitation>(val old: UUID, val new: T) : Op<T>
    private class Flush(val done: CountDownLatch) : Op<Nothing>
    private object Stop : Op<Nothing>
}
