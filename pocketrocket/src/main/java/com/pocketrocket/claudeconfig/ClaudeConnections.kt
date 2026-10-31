package com.pocketrocket.claudeconfig

import com.pocketrocket.auth.ClaudeAccount
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.writeAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What an account's last check found. Built from parsed values only, so it holds nothing secret. */
data class ConnectionsSnapshot(val servers: List<McpServer>, val identity: ClaudeIdentity?, val checkedAt: Long) {
    fun toPanelState() = McpPanelState(identity = identity, servers = servers, loaded = true, settled = true, checkedAt = checkedAt)
}

/** [ConnectionsSnapshot] to and from JSON. Pure. */
object ConnectionsCodec {
    fun encode(snapshot: ConnectionsSnapshot): String = JSONObject()
        .put("checkedAt", snapshot.checkedAt)
        .put("email", snapshot.identity?.account?.email ?: JSONObject.NULL)
        .put("organization", snapshot.identity?.account?.organization ?: JSONObject.NULL)
        .put("subscription", snapshot.identity?.account?.subscription ?: JSONObject.NULL)
        .put("version", snapshot.identity?.version ?: JSONObject.NULL)
        .put("servers", JSONArray(snapshot.servers.map { s ->
            JSONObject().put("name", s.name).put("status", s.state.cli)
                .put("scope", s.scope ?: JSONObject.NULL).put("transport", s.transport ?: JSONObject.NULL)
                .put("urlHost", s.urlHost ?: JSONObject.NULL).put("error", s.error ?: JSONObject.NULL)
                .put("errorCode", s.errorCode ?: JSONObject.NULL).put("toolCount", s.toolCount)
                .put("serverVersion", s.serverVersion ?: JSONObject.NULL)
        }))
        .toString()

    /** Null when [text] isn't a saved snapshot. */
    fun decode(text: String): ConnectionsSnapshot? = runCatching {
        val o = JSONObject(text)
        val list = o.getJSONArray("servers")
        val servers = (0 until list.length()).map { i ->
            val s = list.getJSONObject(i)
            McpServer(
                name = s.getString("name"), state = McpState.of(s.optString("status")),
                scope = s.text("scope"), transport = s.text("transport"), urlHost = s.text("urlHost"),
                error = s.text("error"), errorCode = s.text("errorCode"), toolCount = s.optInt("toolCount"),
                serverVersion = s.text("serverVersion"),
            )
        }
        val email = o.text("email")
        val organization = o.text("organization")
        val subscription = o.text("subscription")
        val account = if (email == null && organization == null && subscription == null) null else ClaudeAccount(email, organization, subscription)
        val version = o.text("version")
        val identity = if (account == null && version == null) null else ClaudeIdentity(account, version)
        ConnectionsSnapshot(servers, identity, o.getLong("checkedAt"))
    }.getOrNull()

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}

/** The last check per account, in memory and in ~/.pocketrocket/accounts/connections/<id>.json. Checks run only when asked. */
object ClaudeConnections {
    private val memory = mutableMapOf<String, ConnectionsSnapshot>()

    private fun file(accountId: String) = File(AccountPaths.root, "connections/$accountId.json")

    @Synchronized
    fun load(accountId: String): ConnectionsSnapshot? = memory[accountId]
        ?: runCatching { ConnectionsCodec.decode(file(accountId).readText()) }.getOrNull()?.also { memory[accountId] = it }

    @Synchronized
    fun save(accountId: String, snapshot: ConnectionsSnapshot) {
        memory[accountId] = snapshot
        runCatching { writeAtomically(file(accountId), ConnectionsCodec.encode(snapshot)) }
    }

    /** After a sign-out or removal: what was seen belongs to that login. */
    @Synchronized
    fun forget(accountId: String) {
        memory.remove(accountId)
        file(accountId).delete()
    }
}
