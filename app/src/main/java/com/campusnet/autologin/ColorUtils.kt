package com.campusnet.autologin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** 颜色工具：HEX 文本与 Compose Color 互转（供主题自定义与单元测试使用）。 */
internal fun toHex6(c: Color): String =
    (c.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

internal fun parseHex(s: String): Color? {
    val clean = s.trim().removePrefix("#")
    if (clean.length != 6) return null
    val v = clean.toLongOrNull(16) ?: return null
    return Color((0xFF000000L or v).toInt())
}
