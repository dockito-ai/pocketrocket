package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.sessions.protocol.StreamEvent
import com.pocketrocket.sessions.protocol.StreamJsonParser
import com.pocketrocket.sessions.protocol.StreamJsonWriter
import com.pocketrocket.sessions.transport.ClaudeTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Control requests over a process of its own. Sends nothing else, so there is no user message and no model call.
 * Ended by [close], by the process exiting, or after [capMs] whatever else happens: the watchdog is a thread of its
 * own, so a stuck coroutine can't leave the process running. Reply lines are parsed here and never kept or logged.
 */
class ProbeChannel(private val transport: ClaudeTransport, private val capMs: Long) : ControlChannel, AutoCloseable {
    val startedAt: Long = System.currentTimeMillis()

    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<ControlReply>>()
    private val initialized = CompletableDeferred<ClaudeIdentity?>()
    private val closed = AtomicBoolean(false)
    @Volatile private var ended: String? = null
    private var watchdog: Thread? = null

    fun start() {
        transport.start(object : ClaudeTransport.Listener {
            override fun onLine(line: String) {
                StreamJsonParser.parse(line).forEach { event -> if (event is StreamEvent.ControlResponse) onReply(event) }
            }

            override fun onExit(code: Int, message: String?) = end(STOPPED)
        })
        watchdog = thread(name = "claude-config-watchdog", isDaemon = true) {
            try {
                Thread.sleep(capMs)
                close()
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun onReply(event: StreamEvent.ControlResponse) {
        if (event.requestId == SessionReducer.INIT_ID) {
            initialized.complete(event.response?.let { ClaudeIdentity.fromInitialize(it) })
            return
        }
        val body = event.response
        waiting.remove(event.requestId)?.complete(if (body != null) ControlReply.Success(body) else ControlReply.Refused(event.error ?: "failed"))
    }

    /** The account and version from the handshake the transport sends. Null if it didn't come. */
    suspend fun identity(timeoutMs: Long = ControlRequests.TIMEOUT_MS): ClaudeIdentity? =
        withTimeoutOrNull(timeoutMs) { initialized.await() }

    override suspend fun request(subtype: String, fields: Map<String, Any>, timeoutMs: Long): ControlReply {
        ended?.let { return ControlReply.NoAnswer(it) }
        val id = SessionReducer.CONFIG_ID + ids.incrementAndGet()
        val answer = CompletableDeferred<ControlReply>()
        waiting[id] = answer
        return try {
            // It may have ended just before the waiter went in, with nothing left to wake it.
            ended?.let { return ControlReply.NoAnswer(it) }
            transport.send(StreamJsonWriter.controlRequest(id, subtype, fields))
            withTimeoutOrNull(timeoutMs) { answer.await() } ?: ControlReply.NoAnswer(NO_ANSWER)
        } finally {
            waiting.remove(id)
        }
    }

    private fun end(reason: String) {
        ended = reason
        initialized.complete(null)
        waiting.values.forEach { it.complete(ControlReply.NoAnswer(reason)) }
        waiting.clear()
    }

    /** Ends the process: stdin closes, which alone ends it (measured on 2.1.314), and it is told to stop. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        end(STOPPED)
        transport.close()
        watchdog?.interrupt()
    }

    private companion object {
        const val STOPPED = "Claude Code stopped."
        const val NO_ANSWER = "Claude Code didn't answer."
    }
}
