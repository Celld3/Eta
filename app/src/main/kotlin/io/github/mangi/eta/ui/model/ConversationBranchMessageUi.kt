package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ConversationBranchMessage

internal fun List<AgentChatMessageUi>.branchMessageOutline(): List<ConversationBranchMessage> = mapIndexed { index, message ->
    ConversationBranchMessage(
        id = message.id,
        sortIndex = index,
        type = when (message) {
            is UserMessageUi -> "user"
            is AgentMessageUi -> "assistant"
            is SystemNoticeMessageUi -> "system_notice"
            is ThinkingMessageUi -> "thinking"
            is ToolActivityMessageUi -> "tool"
            is ToolSummaryMessageUi -> "tool_summary"
            is ChoiceReceiptMessageUi -> "choice_receipt"
            else -> ""
        },
        hasContent = message is AgentMessageUi && message.content.isNotBlank() && !message.isStreaming,
        noticeCode = (message as? SystemNoticeMessageUi)?.code?.wireValue.orEmpty(),
    )
}
