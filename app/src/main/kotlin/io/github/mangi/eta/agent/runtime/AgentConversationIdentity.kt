package io.github.mangi.eta.agent.runtime

import java.security.MessageDigest

/** 入口、归档与浏览器共同使用的会话身份；外部归档标识保持已发布算法不变。 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = "archive-"

    /**
     * 没有会话归属的运行的兜底前缀（终端页、外部入口）。
     *
     * 这类键在聊天界面里没有对应会话，卡片放进去也永远显示不出来，
     * 交互层据此退化成文字提问，而不是挂一张用户点不到、只能等超时的卡片。
     */
    const val RUN_FALLBACK_PREFIX = "run:"

    fun fromHandoff(handoff: AgentRuntimeWire.EntryHandoff?, fallbackRunId: String): String {
        if (handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentUiHandoffPayload.from(handoff.payload).conversationId.takeIf(String::isNotBlank)?.let { return it }
        } else if (handoff != null) {
            AgentExternalArchivePayload.from(handoff.payload)?.let { payload ->
                return archiveConversationId(handoff.source, payload.conversationKey)
            }
        }
        return RUN_FALLBACK_PREFIX + fallbackRunId
    }

    /** 是否为「没有会话归属」的兜底键；空键按同一类处理。 */
    fun isRunFallbackKey(key: String): Boolean =
        key.isBlank() || key.startsWith(RUN_FALLBACK_PREFIX)

    fun archiveConversationId(source: String, conversationKey: String): String {
        val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) "assistant-" else EXTERNAL_ARCHIVE_PREFIX
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return prefix + digest
    }
}
