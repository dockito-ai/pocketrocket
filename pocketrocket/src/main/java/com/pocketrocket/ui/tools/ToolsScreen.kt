package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.Segmented
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * Settings → Tools (decisions 64 and 87): a search on top, then two tabs. Included holds what comes with PocketRocket
 * and stays (first-run setup installs it); Add-ons what is installed and removed from here. Each tab lists its tools
 * whose name matches the search, A to Z; a tile opens the tool's page. Claude and GitHub logins stay in Accounts.
 */
@Composable
fun ToolsPage(onBack: () -> Unit, onOpen: (Tool) -> Unit) {
    val tools = rememberTools()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ToolKind.BuiltIn) }
    val c = PrTheme.colors
    fun shown(k: ToolKind) = ToolModel.tab(tools, k, query) { it.first.spec }
    MockPage {
        MockTopBar("Tools", MockIcons.Back, onBack, titleSize = 17.sp)
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MockTextField(
                query, { query = it }, placeholder = "Search tools",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Segmented(ToolKind.entries, kind, { k -> if (query.isBlank()) k.tab else "${k.tab} · ${shown(k).size}" }) { kind = it }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (kind == ToolKind.BuiltIn) "Comes with PocketRocket and stays." else "Install what Claude needs; remove it any time.",
                style = mockText(13.sp).copy(lineHeight = 19.5.sp), color = c.textMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val list = shown(kind)
            if (list.isEmpty()) {
                Text("No tool here matches \"${query.trim()}\".", style = mockText(13.sp), color = c.textMuted)
            }
            list.forEach { (tool, state) ->
                ToolTile(tool.spec.name, tool.icon, ToolModel.status(tool.spec, state)) { onOpen(tool) }
            }
        }
    }
}
