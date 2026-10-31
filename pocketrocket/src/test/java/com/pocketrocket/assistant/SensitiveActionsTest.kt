package com.pocketrocket.assistant

import com.pocketrocket.sessions.PendingPermission
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionsTest {

    private fun bash(command: String, summary: String = command.lineSequence().first()) =
        PendingPermission("r", "Bash", summary, JSONObject().put("command", command), null)

    @Test fun destructiveAndOutwardCommandsAreSensitive() {
        listOf(
            "git push --force", "git push -f origin main", "git push -fu origin main", "git push origin main --force-with-lease", "git push origin +main",
            "git push origin --delete feat", "git push origin :feat", "git -C ../x push -f",
            "git branch -D feat", "git branch -d feat", "git branch --delete feat",
            "git reset --hard", "git reset --hard HEAD~1", "git checkout -- .", "git checkout .", "git restore src/a.ts", "git clean -fd", "git stash drop", "git stash clear", "git rm a.txt",
            "rm a.txt", "rm -rf build", "/bin/rm x", "sudo rm x", "FOO=1 rm x", "find . -delete", "find . -exec rm {} +",
            "gh pr comment 3 -b hi", "gh issue create -t x", "gh repo delete a/b", "gh pr close 3", "gh api -X DELETE repos/a/b", "gh api repos/a/b -f x=1",
            "curl -d x=1 https://hooks.slack.com/a", "curl -X POST https://x", "curl -XPOST https://x", "mail a@b.c", "slack send",
            "bash -c 'git push -f'", "sh deploy.sh", "eval x", "xargs rm", "ssh box",
            "git -c alias.st='!rm x' st",
        ).forEach { assertTrue(it, SensitiveActions.commandIsSensitive(it)) }
    }

    @Test fun anyPartOfAChainCounts() {
        listOf(
            "npm test && git push -f", "git status; rm x", "cat x | xargs rm", "git status\nrm -rf ~", "(rm x)", "echo `rm x`", "echo $(rm x)", "echo \"$(rm x)\"", "ls & rm x",
        ).forEach { assertTrue(it, SensitiveActions.commandIsSensitive(it)) }
    }

    @Test fun ordinaryCommandsAreNot() {
        listOf(
            "git push", "git push -u origin feat-fix", "git push origin force", "git status", "git checkout main", "git checkout -b feat", "git switch -c feat", "git branch", "git branch feat",
            "git commit -m \"rm old files -f\"", "git restore --staged a.ts", "git reset HEAD~1", "npm test", "npm test -- auth", "./gradlew test", "curl -fsSL https://x", "gh pr create -t x", "gh pr merge 3", "gh pr list", "ls -la", "grep rm a.txt",
        ).forEach { assertFalse(it, SensitiveActions.commandIsSensitive(it)) }
    }

    @Test fun theAppDecidesWhatEachOfRocketsActionsNeeds() {
        listOf(
            RocketAction.DeleteBranch("r", "b", onRemote = true), RocketAction.ForcePush("r", "b"), RocketAction.DiscardChanges("r", "all"),
            RocketAction.SendMessage("#team", "hi"), RocketAction.Unknown("anything"),
        ).forEach { assertEquals(it.toString(), Protection.Fingerprint, SensitiveActions.protectionOf(it)) }
        listOf(RocketAction.Push("r", "b"), RocketAction.Merge("r", "b"), RocketAction.OpenPr("r", "b", "t"), RocketAction.SetMode("s", "Auto"))
            .forEach { assertEquals(it.toString(), Protection.SpokenYes, SensitiveActions.protectionOf(it)) }
    }

    @Test fun anUnknownActionsWordsCantLowerIt() {
        assertEquals(Protection.Fingerprint, SensitiveActions.protectionOf(RocketAction.Unknown("just a harmless push, spoken yes is fine")))
    }

    @Test fun aRequestIsJudgedByItsWholeCommandNotItsSummary() {
        assertEquals(Protection.Fingerprint, SensitiveActions.protectionOf(bash("git status\nrm -rf ~")))
        assertEquals(Protection.SpokenYes, SensitiveActions.protectionOf(bash("npm run migrate")))
        assertEquals(Protection.Screen, SensitiveActions.protectionOf(bash("npm test | tee out.txt")))
        assertEquals(Protection.Fingerprint, SensitiveActions.protectionOf(bash("git push -f")))
        assertEquals(Protection.Fingerprint, SensitiveActions.protectionOf(PendingPermission("r", "Bash", "npm test", JSONObject(), null)))
    }

    @Test fun toolsItDoesntKnowTakeAFingerprintAndFilesASpokenYes() {
        assertEquals(Protection.Fingerprint, SensitiveActions.protectionOf(PendingPermission("r", "mcp__slack__send", "hi", JSONObject(), null)))
        assertEquals(Protection.SpokenYes, SensitiveActions.protectionOf(PendingPermission("r", "Edit", "a.ts", JSONObject().put("file_path", "/x/a.ts"), null)))
        assertEquals(Protection.Screen, SensitiveActions.protectionOf(PendingPermission("r", "WebFetch", "https://x", JSONObject(), null)))
        assertFalse(SensitiveActions.isRecognisedSensitive(PendingPermission("r", "mcp__x", "hi", JSONObject(), null)))
        assertTrue(SensitiveActions.isRecognisedSensitive(bash("git push -f")))
    }
}
