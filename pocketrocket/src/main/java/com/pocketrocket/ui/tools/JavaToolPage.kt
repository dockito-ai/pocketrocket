package com.pocketrocket.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.pkgtools.PackageTools
import com.pocketrocket.pkgtools.PackageToolsState
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.ui.theme.PrTheme

/**
 * Settings → Tools → Java and Kotlin (decisions 98 and 101): Install puts in Java 21 and Kotlin; the JDKs card adds and
 * removes Java 17, 21 and 25 and Kotlin one at a time, and every Java job gives Gradle the list of JDKs on the phone.
 * Java 17 stays the default java while it's there.
 */
@Composable
fun JavaToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Java,
    purpose = "Java 17, 21 and 25 side by side, and Kotlin.",
    onBack = onBack,
    removeNote = "Java 17, if it is here, stays: Android builds use it. Your projects stay.",
) {
    val s by PackageTools.state.collectAsState()
    JdksCard(s)
    ToolCard("WHICH JAVA A PROJECT GETS") {
        ToolBullets(
            listOf(
                "Gradle projects get the JDK their toolchain asks for (languageVersion = 21): PocketRocket lists every JDK " +
                    "here in ~/.gradle/gradle.properties and turns off Gradle's JDK downloads, which don't run on Android.",
                "Everything else uses JAVA_HOME, or the default java: JAVA_HOME=\$PREFIX/lib/jvm/java-21-openjdk ./gradlew build",
                "Kotlin's version comes from each project's Gradle plugin; kotlinc here is for scripts and quick programs.",
            ),
        )
    }
    ToolCard("JAVA 17 STAYS THE DEFAULT") {
        ToolText(
            "Android and Flutter builds use Java 17 from the Android tool. While it is on the phone it stays the default java, " +
                "and no tool here removes it. The others are in \$PREFIX/lib/jvm/java-<version>-openjdk.",
        )
    }
}

/** One row per JDK and Kotlin, with Add or Remove; the running (or failed) one shows its steps and log. */
@Composable
private fun JdksCard(s: PackageToolsState) {
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf<PackageTool?>(null) }
    val part = s.tool?.takeIf { it in PackageTool.JAVA_PARTS }
    val job = s.job
    var logOpen by rememberSaveable(s.failed) { mutableStateOf(s.failed) }
    val default = PackageTool.javaDefault(s.disk)

    ToolCard("JDKS") {
        Column(Modifier.fillMaxWidth()) {
            PackageTool.JAVA_PARTS.forEachIndexed { i, t ->
                val installed = s.installed(t)
                val version = t.versions(s.packages).firstOrNull()?.second
                val isDefault = installed && t != PackageTool.Kotlin && default == t.main.removePrefix("openjdk-")
                PartRow(
                    name = t.title,
                    detail = version ?: "${t.downloadMb} MB download",
                    mono = version != null,
                    badge = "Default".takeIf { isDefault },
                ) {
                    when {
                        s.running && part == t -> Text(if (s.removing) "Removing…" else "Adding…", style = mockText(13.sp), color = PrTheme.colors.textMuted)
                        s.running || !s.checked -> Unit
                        installed && t.canRemove -> OutlineButton(
                            "Remove", { confirm = t }, Modifier.width(96.dp),
                            color = PrTheme.colors.redSoft, border = PrTheme.colors.redBorder,
                        )
                        installed -> Text("Stays", style = mockText(13.sp), color = PrTheme.colors.textMuted)
                        else -> OutlineButton("Add", { PackageTools.install(context, t) }, Modifier.width(96.dp))
                    }
                }
                if (i < PackageTool.JAVA_PARTS.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(PrTheme.colors.divider))
            }
        }
        if (part != null && job != null && (s.running || s.failed)) {
            StepList(job, style = StepListStyle.Compact, upcoming = if (s.removing) part.removeSteps else part.installSteps)
            if (s.failed) s.outcome?.let { Text(it, style = mockText(12.sp), color = PrTheme.colors.redSoft) }
            ToolLog(job.log, logOpen) { logOpen = !logOpen }
            if (s.failed) AccentButton("Retry", onClick = { PackageTools.retry(context) }, height = 44.dp, radius = 12.dp)
        }
        if (s.running && part == null && s.tool != PackageTool.Java) Note("Waiting for another install…")
        Note("Kotlin needs Java 21; adding it adds Java 21 too. A pkg install openjdk-25 in a terminal shows here as well.")
    }

    confirm?.let { t ->
        ConfirmSheet(
            title = "Remove ${t.title}?",
            body = removeBody(t, s),
            confirm = "Remove",
            onConfirm = {
                confirm = null
                PackageTools.remove(context, t)
            },
            onDismiss = { confirm = null },
        )
    }
}

/** What Remove takes: Java 21 takes Kotlin with it, since Kotlin needs it. */
private fun removeBody(t: PackageTool, s: PackageToolsState): String = when {
    t == PackageTool.Java21 && s.installed(PackageTool.Kotlin) ->
        "Kotlin needs Java 21, so it goes too. Gradle's JDK list is updated. Your projects stay."
    t == PackageTool.Kotlin -> "Java 21 stays. Your projects stay."
    else -> "About ${t.installedMb} MB is freed and Gradle's JDK list is updated. Your projects stay."
}

@Composable
private fun PartRow(name: String, detail: String, mono: Boolean, badge: String?, action: @Composable () -> Unit) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = mockText(14.sp), color = c.text)
                badge?.let {
                    Text(
                        it, style = mockText(11.sp, FontWeight.SemiBold), color = c.green,
                        modifier = Modifier.background(c.green.copy(alpha = 0.14f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(detail, style = mockText(12.sp, mono = mono), color = c.textMuted)
        }
        action()
    }
}
