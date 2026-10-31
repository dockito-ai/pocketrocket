package com.pocketrocket.sessions.transport

/**
 * A line-based pipe to one Claude Code conversation in stream-json mode. [ClaudeProcessTransport] runs
 * the real CLI; [FakeClaudeTransport] replays a script so the UI works without a login.
 */
interface ClaudeTransport {

    interface Listener {
        /** One stdout line (one JSON message). Called from a background thread. */
        fun onLine(line: String)

        /** One stderr line, for diagnostics only. */
        fun onStderr(line: String) {}

        /** The process ended. [code] is its exit code; -1 when it could not start. */
        fun onExit(code: Int, message: String?)
    }

    fun start(listener: Listener)

    /** Writes one line to stdin. */
    fun send(line: String)

    /** Ends the conversation; no [Listener.onExit] is promised after this. */
    fun close()
}
