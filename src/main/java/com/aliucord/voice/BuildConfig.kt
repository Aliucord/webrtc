package com.aliucord.voice

/**
 * Voice fork ABI version. Read via reflection by the VoiceChatFix core plugin's version gate
 * ([com.aliucord.coreplugins.voice] EXPECTED_LIB_VERSION). Bump together with the bundled
 * libdiscord.so / webrtc dex pairing.
 */
object BuildConfig {
    const val VERSION = "90.0.19-codec-api.b2"
}
