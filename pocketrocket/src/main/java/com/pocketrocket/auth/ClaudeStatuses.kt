package com.pocketrocket.auth

import android.content.Context
import com.pocketrocket.profiles.ClaudeAccountEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Whether a Claude account is signed in, as `claude auth status` last said. */
sealed interface SignInStatus {
    data object Checking : SignInStatus
    data object SignedOut : SignInStatus
    data class SignedIn(val account: ClaudeAccount) : SignInStatus
}

/**
 * The sign-in status of every Claude account, by account id, checked with Claude Code's own
 * `claude auth status` for that account's config dir. Screens read it and ask for a [check] when shown.
 */
object ClaudeStatuses {
    private val _all = MutableStateFlow<Map<String, SignInStatus>>(emptyMap())
    val all: StateFlow<Map<String, SignInStatus>> = _all.asStateFlow()

    fun of(id: String): SignInStatus = _all.value[id] ?: SignInStatus.Checking

    /** Re-checks [accounts] in parallel. Each takes a second or two (it starts Claude Code). */
    suspend fun check(context: Context, accounts: List<ClaudeAccountEntry>) = coroutineScope {
        accounts.map { entry ->
            async(Dispatchers.IO) {
                val account = ClaudeCli.status(context, entry.configDir)
                set(entry.id, if (account != null) SignInStatus.SignedIn(account) else SignInStatus.SignedOut)
            }
        }.awaitAll()
    }

    suspend fun signOut(context: Context, entry: ClaudeAccountEntry) {
        set(entry.id, SignInStatus.Checking)
        withContext(Dispatchers.IO) { ClaudeCli.logout(context, entry.configDir) }
        check(context, listOf(entry))
    }

    fun set(id: String, status: SignInStatus) = _all.update { it + (id to status) }
}
