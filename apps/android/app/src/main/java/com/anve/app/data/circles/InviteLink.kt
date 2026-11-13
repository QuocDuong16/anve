package com.anve.app.data.circles

import java.net.URI

/** An invitation is an untrusted capability, never an instruction to join or enable GPS. */
object InviteLink {
    private val codePattern = Regex("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{10}")

    fun create(code: String): String? = if (codePattern.matches(code)) "anve://invite/$code" else null

    fun parse(raw: String?): String? {
        if (raw == null || raw.length > 180) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.isOpaque || uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.scheme != "anve" || uri.rawAuthority != "invite") return null
        val code = uri.rawPath?.removePrefix("/") ?: return null
        if (uri.rawPath != "/$code" || !codePattern.matches(code)) return null
        return code
    }
}
