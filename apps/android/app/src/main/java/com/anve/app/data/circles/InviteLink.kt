package com.anve.app.data.circles

import java.net.URI

/** An invitation is an untrusted capability, never an instruction to join or enable GPS. */
object InviteLink {
    private val codePattern = Regex("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{10}")

    fun create(code: String): String? = if (codePattern.matches(code)) "anve://invite/$code" else null

    /**
     * Formats the user-shareable link. In HTTPS production/staging builds this
     * produces `https://<host>/invite#<CODE>` so chat apps make the link
     * clickable and keep the code in the fragment (never sent to the server).
     * Local HTTP development builds fall back to `anve://invite/<CODE>`.
     */
    fun shareUrl(code: String, apiBaseUrl: String): String? {
        if (!codePattern.matches(code)) return null
        val uri = runCatching { URI(apiBaseUrl.trim()) }.getOrNull()
        val host = uri?.host?.lowercase()
        val isPublicHttps = uri != null &&
            uri.scheme == "https" &&
            !host.isNullOrBlank() &&
            host != "localhost" &&
            host != "127.0.0.1" &&
            host != "10.0.2.2"
        return if (isPublicHttps) {
            "https://${uri.rawAuthority}/invite#$code"
        } else {
            "anve://invite/$code"
        }
    }

    /**
     * Parses a manual user-entered invite code or pasted share link (`anve://invite/<CODE>`
     * or `https://<host>/invite#<CODE>`) for the join input field.
     */
    fun extractCode(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 256) return null
        val upper = trimmed.uppercase()
        if (codePattern.matches(upper)) return upper
        parse(trimmed)?.let { return it }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme == "https" && uri.rawPath == "/invite") {
            val fragment = uri.rawFragment?.trim()?.uppercase().orEmpty()
            if (codePattern.matches(fragment)) return fragment
        }
        return null
    }

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
