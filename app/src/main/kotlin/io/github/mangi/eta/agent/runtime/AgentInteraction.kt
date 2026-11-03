package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.overlay.AgentChoiceRequest
import io.github.mangi.eta.agent.overlay.AgentChoiceResult

/**
 * 运行时对用户就地交互的唯一入口。
 *
 * 卡片按会话归属：每个会话最多一张选项卡片与一张复制卡片，切走时隐藏并暂停计时。
 *
 * 工具线程需要阻塞等待用户点选，而浮窗只能在主线程创建；这层薄接口把「阻塞的调用方」和
 * 「主线程窗口宿主」解耦，服务未启动或浮窗不可用时返回 [AgentChoiceResult.Unavailable]，
 * 调用方退化成文字提问，不把缺少 UI 变成任务失败。
 */
internal interface AgentInteractionHost {
    /**
     * 阻塞到用户点选、取消或超时；由 IO 线程调用。
     *
     * [isCancelled] 让调用方在整轮被停止时立刻收回卡片，不必陪跑到超时。
     */
    fun requestChoice(request: AgentChoiceRequest, isCancelled: () -> Boolean): AgentChoiceResult

    /** 非阻塞地弹出复制卡片；每个会话只保留最新一张。返回是否真的显示出来。 */
    fun showCopyCard(title: String, content: String, conversationKey: String): Boolean

    /** 把内嵌选择面板的结论回传给正在阻塞的调用方；由 UI 层在主线程调用。 */
    fun resolveChoice(result: AgentChoiceResult, conversationKey: String)

    /** 用户收起复制卡片后清掉该会话的请求；不清理的话，别的会话再弹卡片时它会被一并复原。 */
    fun dismissCopyCard(conversationKey: String)
}

internal object AgentInteraction {
    @Volatile
    private var host: AgentInteractionHost? = null

    fun attach(target: AgentInteractionHost) {
        host = target
    }

    fun detach(target: AgentInteractionHost) {
        if (host === target) host = null
    }

    fun requestChoice(request: AgentChoiceRequest, isCancelled: () -> Boolean): AgentChoiceResult =
        host?.requestChoice(request, isCancelled) ?: AgentChoiceResult.Unavailable

    fun showCopyCard(title: String, content: String, conversationKey: String): Boolean =
        host?.showCopyCard(title, content, conversationKey) ?: false

    fun resolveChoice(result: AgentChoiceResult, conversationKey: String) {
        host?.resolveChoice(result, conversationKey)
    }

    fun dismissCopyCard(conversationKey: String) {
        host?.dismissCopyCard(conversationKey)
    }
}
