package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * Settings → Tools (decision 64): every tool, as tiles in two sections. BUILT IN holds what first-run setup
 * installs (Node.js and npm, Dockito); OPTIONAL what is installed from here. A tile opens the tool's page.
 */
@Composable
fun ToolsPage(onBack: () -> Unit, onOpen: (Tool) -> Unit) {
    val tools = rememberTools()
    MockPage {
        MockTopBar("Tools", MockIcons.Back, onBack, titleSize = 17.sp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("What Claude can use on this phone.", style = mockText(13.sp).copy(lineHeight = 19.5.sp), color = PrTheme.colors.textMuted)
            listOf(ToolKind.BuiltIn to "BUILT IN", ToolKind.Optional to "OPTIONAL").forEach { (kind, label) ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caps(label)
                    tools.filter { (tool, _) -> tool.spec.kind == kind }.forEach { (tool, state) ->
                        ToolTile(tool.spec.name, tool.icon, ToolModel.status(tool.spec, state)) { onOpen(tool) }
                    }
                }
            }
        }
    }
}
