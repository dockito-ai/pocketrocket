package com.pocketrocket.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class ChromiumMcpTest {
    @Test fun addLaunchesTheTermuxBrowserHeadlessAndIsolated() {
        val args = ChromiumMcp.addArguments("/data/data/app.pocket/files/usr/bin").toList()
        assertEquals(
            listOf(
                "mcp", "add", "--scope", "user", "chrome-devtools", "--",
                "/data/data/app.pocket/files/usr/bin/node",
                "/data/data/app.pocket/files/usr/lib/node_modules/chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js",
                "--headless=true",
                "--isolated=true",
                "--executablePath=/data/data/app.pocket/files/usr/bin/chromium-browser",
                "--chrome-arg=--no-sandbox",
                "--chrome-arg=--disable-gpu",
            ),
            args,
        )
    }

    @Test fun removeTargetsTheSameServer() {
        assertEquals(
            listOf("mcp", "remove", "chrome-devtools", "--scope", "user"),
            ChromiumMcp.removeArguments().toList(),
        )
    }

    /** The tool page lists these before the script reaches them, so they must be the script's own step titles. */
    @Test fun stepTitlesMatchTheSetupScript() {
        val script = listOf("../setup/pocketrocket-setup.sh", "setup/pocketrocket-setup.sh").map(::File).first { it.isFile }.readText()
        (ChromiumMcp.INSTALL_STEPS + ChromiumMcp.REMOVE_STEPS).forEach { title ->
            assertTrue("missing step \"$title\"", script.contains("step \"$title\""))
        }
    }
}
