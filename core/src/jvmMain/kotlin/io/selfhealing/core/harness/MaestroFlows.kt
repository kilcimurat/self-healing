package io.selfhealing.core.harness

/** Fixes known cross-platform pitfalls in harness-written Maestro flows before they run. */
object MaestroFlows {
    private val bareHideKeyboard = Regex("""(?m)^(\s*)- hideKeyboard\s*$""")

    /**
     * `hideKeyboard` fails on iOS for Compose Multiplatform screens (the keyboard can't be dismissed that way),
     * which aborted the whole flow. Made optional, Maestro warns and the next tap still works.
     */
    fun normalize(flow: String): String =
        bareHideKeyboard.replace(flow) { m -> "${m.groupValues[1]}- hideKeyboard:\n${m.groupValues[1]}    optional: true" }
}
