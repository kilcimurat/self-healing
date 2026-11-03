package io.selfhealing.core.harness

import kotlin.test.Test
import kotlin.test.assertEquals

class MaestroFlowsTest {
    @Test
    fun makesBareHideKeyboardOptional() {
        val flow = "appId: a\n---\n- inputText: \"x\"\n- hideKeyboard\n- tapOn: \"Sign in\"\n"
        assertEquals(
            "appId: a\n---\n- inputText: \"x\"\n- hideKeyboard:\n    optional: true\n- tapOn: \"Sign in\"\n",
            MaestroFlows.normalize(flow),
        )
    }

    @Test
    fun leavesOtherStepsAlone() {
        val flow = "- hideKeyboard:\n    optional: true\n- tapOn: \"hideKeyboard\"\n"
        assertEquals(flow, MaestroFlows.normalize(flow))
    }
}
