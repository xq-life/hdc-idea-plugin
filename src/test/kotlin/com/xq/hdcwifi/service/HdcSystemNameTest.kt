package com.xq.hdcwifi.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HdcSystemNameTest {

    private val service = HdcService()

    @Test
    fun `huawei software version is presented as harmony os`() {
        val name = HdcServiceReflection.displaySystemName(
            service,
            mapOf(
                "Software version" to "BLK-AL80 6.1.0.135(SP9C00E120R5P6)",
                "System release" to "1"
            )
        )

        assertEquals("HarmonyOS 6.1.0.135", name)
    }

    @Test
    fun `open harmony keeps its product name`() {
        val name = HdcServiceReflection.displaySystemName(
            service,
            mapOf(
                "Software version" to "OpenHarmony",
                "System release" to "5.0.2"
            )
        )

        assertEquals("OpenHarmony 5.0.2", name)
    }

    @Test
    fun `system release is used when software version has no semantic version`() {
        val name = HdcServiceReflection.displaySystemName(
            service,
            mapOf(
                "Software version" to "unknown",
                "System release" to "5.0"
            )
        )

        assertEquals("HarmonyOS 5.0", name)
    }

    @Test
    fun `missing versions still identify the operating system family`() {
        val name = HdcServiceReflection.displaySystemName(
            service,
            mapOf("Software version" to "-", "System release" to "-")
        )

        assertEquals("HarmonyOS", name)
    }
}
