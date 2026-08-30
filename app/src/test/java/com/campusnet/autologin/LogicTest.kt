package com.campusnet.autologin

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑单元测试：不依赖 Android 框架，可直接在 JVM 上运行。 */
class LogicTest {

    // ---- ConnectivityChecker.resolve：302 Location 解析 ----

    @Test
    fun `resolve - 绝对地址直接返回`() {
        assertEquals(
            "http://portal.example.edu/login",
            ConnectivityChecker.resolve("http://probe.example.com/gen", "http://portal.example.edu/login")
        )
    }

    @Test
    fun `resolve - 相对地址拼接到探测点主机`() {
        assertEquals(
            "http://portal.example.edu/login",
            ConnectivityChecker.resolve("http://portal.example.edu/gen", "/login")
        )
    }

    @Test
    fun `resolve - 带路径的相对地址按目录解析`() {
        assertEquals(
            "http://portal.example.edu/portal/webauth",
            ConnectivityChecker.resolve("http://portal.example.edu/portal/", "webauth")
        )
    }

    // ---- SSID 关键词匹配（用户可配置规则） ----

    @Test
    fun `ssid 规则 - 包含关键词即命中`() {
        val rules = listOf("campus", "dorm-5g")
        assertTrue(ssidMatchesRules("MyCampus-WiFi", rules))
        assertTrue(ssidMatchesRules("dorm-5g-618", rules))
    }

    @Test
    fun `ssid 规则 - 不含关键词不命中`() {
        val rules = listOf("campus", "dorm-5g")
        assertFalse(ssidMatchesRules("HomeWiFi", rules))
        assertFalse(ssidMatchesRules("CoffeeShop-Free", rules))
    }

    @Test
    fun `ssid 规则 - 空白规则被忽略`() {
        assertFalse(ssidMatchesRules("Anything", listOf("  ", "")))
    }

    // ---- parseHex / toHex6：主题颜色互转 ----

    @Test
    fun `parseHex - 合法输入`() {
        assertEquals(Color(0xFF1565C0.toInt()), parseHex("#1565C0"))
        assertEquals(Color(0xFF1565C0.toInt()), parseHex("1565c0"))
        assertEquals(Color(0xFFC2185B.toInt()), parseHex("C2185B"))
    }

    @Test
    fun `parseHex - 非法输入返回 null`() {
        assertNull(parseHex("12345"))
        assertNull(parseHex("1234567"))
        assertNull(parseHex("ZZZZZZ"))
        assertNull(parseHex(""))
    }

    @Test
    fun `toHex6 与 parseHex 往返一致`() {
        val c = Color(0xFFC2185B.toInt())
        assertEquals("C2185B", toHex6(c))
        assertEquals(c, parseHex(toHex6(c)))
    }
}
