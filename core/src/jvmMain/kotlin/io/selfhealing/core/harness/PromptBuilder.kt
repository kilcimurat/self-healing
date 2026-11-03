package io.selfhealing.core.harness

import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.model.CrashReport
import java.io.File

object PromptBuilder {
    /** Must match AndroidVerifier.REPRO_FLOW / where the verifier looks for it. */
    const val REPRO_FLOW = "repro.yaml"

    fun build(crash: CrashReport, repo: RepoConfig, resultFile: File): String = buildString {
        val appId = repo.android?.applicationId ?: repo.ios?.bundleId
        appendLine("# Fix a production crash")
        appendLine()
        val platforms = repo.platforms.joinToString(" and ") { it.name.lowercase() }
        appendLine("You are fixing a production crash in this repository (`${repo.id}`). It was reported on ${crash.platform.name.lowercase()}; the repository ships $platforms.")
        if (repo.platforms.size > 1) appendLine("Shared (multiplatform) code may crash on every platform: fix the root cause where it lives, and the fix will be verified on $platforms.")
        appendLine("The crash was reported by Sentry. Find the root cause in the code and apply a minimal, safe fix.")
        appendLine()
        appendLine("## Crash")
        appendLine("- Title: ${crash.title}")
        crash.culprit?.let { appendLine("- Culprit: $it") }
        crash.release?.let { appendLine("- Release: $it") }
        crash.level?.let { appendLine("- Level: $it") }
        crash.permalink?.let { appendLine("- Sentry: $it") }
        appendLine()
        appendLine("## Stack trace (innermost frame first)")
        appendLine("```")
        appendLine(crash.stacktrace.trim())
        appendLine("```")
        if (crash.context.isNotBlank()) {
            appendLine()
            appendLine("## Context (breadcrumbs, tags, device)")
            appendLine("```")
            appendLine(crash.context.trim())
            appendLine("```")
        }
        appendLine()
        appendLine("## Steps")
        appendLine("1. Locate the root cause in the code, starting from the in-app frames of the stack trace (ignore framework/library frames).")
        appendLine("2. Make the minimal safe fix. Do not refactor unrelated code.")
        appendLine("3. Add a regression unit test when feasible, and run only the fast, relevant unit tests.")
        appendLine("4. Do NOT commit, push or create branches. Do NOT build or launch the app: the orchestrator does that afterwards.")
        if (appId != null) {
            val flow = File(resultFile.parentFile, REPRO_FLOW).absolutePath
            appendLine("5. Write a Maestro flow (https://maestro.mobile.dev) that reproduces the crash from a fresh app launch to this absolute path: $flow")
            appendLine("   Start it with `appId: $appId`, then `---`, then `- launchApp`, and follow the shortest UI path that triggers the crash,")
            appendLine("   using the real texts/ids you find in the UI code (e.g. `- tapOn: \"Settings\"`). End with an assertion on the screen")
            appendLine("   that should be visible after the trigger, so the flow passes on the fixed build and fails on the crashing one.")
            appendLine("   The orchestrator runs it against the released build (expects the crash) and the fixed build (expects no crash).")
            if (repo.platforms.size > 1) appendLine("   The same flow runs on Android and iOS: use visible texts (not platform-specific ids) so it works on both.")
            appendLine("   Write `hideKeyboard` as `- hideKeyboard: {optional: true}`: it cannot dismiss the keyboard on iOS Compose screens.")
            val account = repo.testAccount
            if (account?.isSet == true) {
                appendLine("   A test account is available. Start with `- launchApp: {clearState: true}`, then sign in with it before")
                appendLine("   navigating to the crash: type `\${TEST_USERNAME}` and `\${TEST_PASSWORD}` exactly like that (Maestro injects them;")
                appendLine("   never write real credentials). Find the sign-in screen's texts in the UI code.")
                if (account.loginHint.isNotBlank()) appendLine("   Sign-in path: ${account.loginHint}")
            } else {
                appendLine("   No test account is configured: if the crash is only reachable when signed in, say so in `rootCause`.")
            }
            appendLine("   Skip this step only if the crash cannot be reached through the UI; do not put the flow inside the repository.")
        }
        appendLine("${if (appId != null) 6 else 5}. Finally write a JSON file at this absolute path: ${resultFile.absolutePath}")
        appendLine()
        appendLine("The JSON must have exactly this schema:")
        appendLine("```json")
        appendLine("""{"fixed": true, "rootCause": "string", "fix": "string", "filesChanged": ["path"], "testsAdded": ["path"], "reproSteps": ["step"], "confidence": "high|medium|low"}""")
        appendLine("```")
        appendLine("If you cannot find the cause or cannot fix it, still write the file with `\"fixed\": false` and explain the reasons in `rootCause`.")
    }
}
