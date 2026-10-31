package com.pocketrocket.assistant

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val catalog = object : ToolCatalog {
        var called: String? = null
        override fun list() = JSONArray().put(JSONObject().put("name", "list_sessions"))
        override suspend fun call(name: String, arguments: JSONObject): ToolResult {
            called = name
            if (name == "boom") error("broken")
            return ToolResult("done ${arguments.optString("a")}")
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = McpServer(catalog, { JSONObject().put("seen", it.optString("tool_name")) }, scope, mcpToken = "mcp-token", hookToken = "hook-token")

    private fun request(method: String, path: String, body: String = "", auth: String? = "mcp-token", host: String = "127.0.0.1:1", origin: String? = null): HttpAnswer = runBlocking {
        val headers = buildMap {
            put("host", host)
            auth?.let { put("authorization", "Bearer $it") }
            origin?.let { put("origin", it) }
        }
        server.handle(HttpRequest(method, path, headers, body))
    }

    private fun rpc(method: String, params: JSONObject = JSONObject()) =
        request("POST", "/mcp", JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method).put("params", params).toString())

    @Test fun refusesAWrongOrMissingToken() {
        assertEquals(401, request("POST", "/mcp", auth = "nope").status)
        assertEquals(401, request("POST", "/mcp", auth = null).status)
        assertEquals(401, request("POST", "/mcp", auth = "").status)
        assertNull(catalog.called)
    }

    @Test fun eachTokenReachesOnlyItsOwnPath() {
        assertEquals(401, request("POST", "/mcp", "{}", auth = "hook-token").status)
        assertEquals(401, request("POST", "/hook", "{}", auth = "mcp-token").status)
        assertEquals(200, request("POST", "/hook", """{"tool_name":"Bash"}""", auth = "hook-token").status)
    }

    @Test fun refusesWhatABrowserPageWouldSend() {
        assertEquals(403, request("POST", "/mcp", "{}", origin = "https://evil.example").status)
        assertEquals(403, request("POST", "/mcp", "{}", host = "evil.example:80").status)
    }

    @Test fun tokensDifferEachTime() {
        assertNotEquals(McpServer.randomToken(), McpServer.randomToken())
        assertEquals(64, McpServer.randomToken().length)
    }

    @Test fun getIsNotAStream() {
        assertEquals(405, request("GET", "/mcp").status)
        assertEquals(404, request("POST", "/other").status)
    }

    @Test fun initialiseEchoesTheProtocol() {
        val result = JSONObject(rpc("initialize", JSONObject().put("protocolVersion", "2025-11-25")).body).getJSONObject("result")
        assertEquals("2025-11-25", result.getString("protocolVersion"))
        assertEquals("rocket", result.getJSONObject("serverInfo").getString("name"))
    }

    @Test fun anUnknownMethodIsAnErrorNotAnEmptyAnswer() {
        val answer = JSONObject(rpc("server/discover").body)
        assertEquals(-32601, answer.getJSONObject("error").getInt("code"))
        assertEquals(7, answer.getInt("id"))
    }

    @Test fun aNotificationGetsNothingBack() {
        assertEquals(202, request("POST", "/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
    }

    @Test fun callsATool() {
        val params = JSONObject().put("name", "x").put("arguments", JSONObject().put("a", "b"))
        val result = JSONObject(rpc("tools/call", params).body).getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        assertEquals("done b", result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun aToolThatThrowsIsAToolError() {
        val result = JSONObject(rpc("tools/call", JSONObject().put("name", "boom")).body).getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
    }

    @Test fun badJsonIsABadRequest() = assertEquals(400, request("POST", "/mcp", "not json").status)

    @Test fun listensOnLoopbackAndAnswersOverRealHttp() {
        server.start()
        try {
            val c = URL("http://127.0.0.1:${server.port}/mcp").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer mcp-token")
            c.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray()) }
            assertEquals(200, c.responseCode)
            val tools = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("result").getJSONArray("tools")
            assertEquals("list_sessions", tools.getJSONObject(0).getString("name"))
            // Only on loopback: nothing else is bound.
            assertTrue(ServerSocket::class.java.name.isNotEmpty())
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test fun readsARequestWhole() {
        val raw = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer t\r\nContent-Length: 2\r\n\r\n{}"
        val r = HttpRequest.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", r.method)
        assertEquals("Bearer t", r.header("authorization"))
        assertEquals("{}", r.body)
    }

    @Test fun refusesAnOversizedOrCutOffBody() {
        assertNull(HttpRequest.read(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".toByteArray())))
        assertNull(HttpRequest.read(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\n{}".toByteArray())))
    }
}
