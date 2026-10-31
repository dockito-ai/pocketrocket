package com.pocketrocket.assistant

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What a tool sends back. [isError]: it didn't happen, and the text says why. */
data class ToolResult(val text: String, val isError: Boolean = false)

/** The tools, as the server needs them. */
interface ToolCatalog {
    /** Each tool as MCP lists it: name, description, inputSchema. */
    fun list(): JSONArray
    suspend fun call(name: String, arguments: JSONObject): ToolResult
}

/** A line of a request, already checked: what the server answers with. */
data class HttpAnswer(val status: Int, val body: String = "", val type: String = "application/json")

/**
 * MCP's HTTP transport on 127.0.0.1, for the brain's one session, plus the `/hook` Claude Code posts `PreToolUse` to.
 * Two tokens, random for each start: [mcpToken] only reaches the tools and [hookToken], which every agent's settings carry,
 * only reaches the hook. No answer ever streams, so each request is one JSON reply and the connection closes. Other
 * processes on the phone run as the same user and could read a token, so no tool grants anything on its own (`docs/assistant.md`).
 * Nothing read here is logged.
 */
class McpServer(
    private val tools: ToolCatalog,
    private val hook: (JSONObject) -> JSONObject,
    private val scope: CoroutineScope,
    val mcpToken: String = randomToken(),
    val hookToken: String = randomToken(),
    /** Rocket's own session posts to [ROCKET_HOOK_PATH], with the same token: it can only be stricter than [hook]. */
    private val rocketHook: (JSONObject) -> JSONObject = hook,
) {
    private var socket: ServerSocket? = null

    /** The port it listens on; 0 until [start]. */
    val port: Int get() = socket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (socket != null) return
        // Off the main thread, which may be what's starting a session.
        val server = runBlocking(Dispatchers.IO) { ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        socket = server
        scope.launch(Dispatchers.IO) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                // A tool can wait on an approval for a minute or more, so each request has its own coroutine.
                launch(Dispatchers.IO) { client.use { serve(it) } }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val request = runCatching { HttpRequest.read(BufferedInputStream(client.getInputStream())) }.getOrNull() ?: return
        val answer = handle(request)
        val body = answer.body.toByteArray()
        val head = "HTTP/1.1 ${answer.status} ${reason(answer.status)}\r\nContent-Type: ${answer.type}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        }
    }

    /** One request, start to finish. The token comes before anything else is looked at. */
    internal suspend fun handle(request: HttpRequest): HttpAnswer {
        // A browser page can reach 127.0.0.1 too: it always sends an Origin, or a Host of its own, and Claude Code does neither.
        if (request.header("origin") != null || !hostIsLocal(request.header("host"))) return HttpAnswer(403)
        return when (request.path.substringBefore('?')) {
            MCP_PATH -> when {
                !tokenMatches(request, mcpToken) -> HttpAnswer(401)
                request.method != "POST" -> HttpAnswer(405)
                else -> rpc(request.body)
            }
            HOOK_PATH, ROCKET_HOOK_PATH -> when {
                !tokenMatches(request, hookToken) -> HttpAnswer(401)
                request.method != "POST" -> HttpAnswer(405)
                else -> {
                    val decide = if (request.path.substringBefore('?') == ROCKET_HOOK_PATH) rocketHook else hook
                    runCatching { HttpAnswer(200, decide(JSONObject(request.body)).toString()) }.getOrElse { HttpAnswer(400) }
                }
            }
            else -> HttpAnswer(404)
        }
    }

    private suspend fun rpc(body: String): HttpAnswer {
        val message = try { JSONObject(body) } catch (_: JSONException) { return HttpAnswer(400, failure(JSONObject.NULL, -32700, "Parse error")) }
        val id = message.opt("id") ?: return HttpAnswer(202) // A notification: nothing to say.
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifBlank { DEFAULT_PROTOCOL })
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", "1"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", tools.list())
            "tools/call" -> {
                val name = params.optString("name")
                if (name.isBlank()) return HttpAnswer(200, failure(id, -32602, "No tool named"))
                val done = runCatching { tools.call(name, params.optJSONObject("arguments") ?: JSONObject()) }
                    .getOrElse { ToolResult("That didn't work: ${it.message ?: it.javaClass.simpleName}", isError = true) }
                JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", done.text)))
                    .put("isError", done.isError)
            }
            else -> return HttpAnswer(200, failure(id, -32601, "Method not found"))
        }
        return HttpAnswer(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    private fun failure(id: Any, code: Int, text: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", text)).toString()

    private fun tokenMatches(request: HttpRequest, token: String): Boolean {
        val given = request.header("authorization")?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun hostIsLocal(host: String?): Boolean = host?.substringBeforeLast(':') in LOCAL_HOSTS

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    companion object {
        const val SERVER_NAME = "rocket"
        const val MCP_PATH = "/mcp"
        const val HOOK_PATH = "/hook"
        const val ROCKET_HOOK_PATH = "/hook/rocket"
        const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 30_000
        private const val DEFAULT_PROTOCOL = "2025-06-18"
        private val LOCAL_HOSTS = setOf(LOOPBACK, "localhost")

        fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

/** One HTTP request. Bodies have a limit: nothing Rocket's tools take is long. */
class HttpRequest(val method: String, val path: String, private val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_BODY = 1024 * 1024
        private const val MAX_LINE = 16 * 1024

        /** Null for anything that isn't a whole, sensible request. */
        fun read(input: InputStream): HttpRequest? {
            val first = line(input) ?: return null
            val parts = first.split(' ')
            if (parts.size < 2) return null
            val headers = HashMap<String, String>()
            while (true) {
                val l = line(input) ?: return null
                if (l.isEmpty()) break
                val colon = l.indexOf(':')
                if (colon > 0) headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..MAX_BODY) return null
            val body = ByteArray(length)
            var at = 0
            while (at < length) {
                val n = input.read(body, at, length - at)
                if (n < 0) return null
                at += n
            }
            return HttpRequest(parts[0], parts[1], headers, String(body))
        }

        private fun line(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
                if (out.size() > MAX_LINE) return null
            }
            return out.toString()
        }
    }
}
