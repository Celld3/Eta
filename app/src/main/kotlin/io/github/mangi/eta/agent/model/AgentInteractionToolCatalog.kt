package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 与用户就地交互的工具 schema。
 *
 * 设计取自成熟实现（Roo-Code / Cline 的 `ask_followup_question`）：一次提问只带 2~4 个
 * 完整、可直接采纳的候选答案，模型不写占位符；区别是这里的候选答案会渲染成系统浮窗卡片，
 * 用户点一下就把该选项原样回传为工具结果，避免模型为了一个二选一问题结束整轮。
 */
internal object AgentInteractionToolCatalog {
    const val ASK_CHOICE = "ask_choice"
    const val COPY_TO_CLIPBOARD = "copy_to_clipboard"

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = ASK_CHOICE,
                    description = "需要用户当场拍板时，弹出一张小卡片让用户点选，不要用文字提问后结束本轮。" +
                        "卡片标题一句话说清要定什么；选项 2~4 个，每个都是完整、可直接采纳的答案，不写占位符或“其他”；" +
                        "把最有把握的一项标为推荐。用户点选后工具直接返回所选内容，然后按它继续执行。" +
                        "只用于真的取决于用户偏好的分叉（选哪个、要不要、用哪一种）；能从上下文或文件确认的、能自己决定的，不要问。" +
                        "超时或用户取消会返回 CANCELLED / TIMED_OUT，此时按推荐的默认项继续推进并在最终答复里说明。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "title",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "卡片标题，一句话说清要决定的事。"),
                                )
                                .put(
                                    "subtitle",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 240)
                                        .put("description", "可选的一句补充说明，讲清影响或代价。"),
                                )
                                .put(
                                    "options",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("minItems", 2)
                                        .put("maxItems", 4)
                                        .put("description", "2~4 个完整、可直接采纳的候选答案。")
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "object")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put("title", JSONObject().put("type", "string").put("maxLength", 80).put("description", "选项文字，用户看到的就是它。"))
                                                        .put("description", JSONObject().put("type", "string").put("maxLength", 160).put("description", "可选的补充说明，一般填影响或后果。"))
                                                        .put("recommended", JSONObject().put("type", "boolean").put("description", "是否推荐，最多一项为 true。")),
                                                )
                                                .put("required", JSONArray().put("title")),
                                        ),
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选，等待用户点选的秒数，默认 120，范围 10~600。"),
                                ),
                        )
                        .put("required", JSONArray().put("title").put("options")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = COPY_TO_CLIPBOARD,
                    description = "把一段要落地的正文弹成带复制按钮的小卡片，用户点一下即写入剪贴板；" +
                        "不阻塞后续工作、不占全屏、不切应用。适合较长的粘贴目标（地址、命令、配置、代码、成段文字），" +
                        "短到一两个词直接用 type_text 或 set_clipboard。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("maxLength", 20_000).put("description", "要复制的内容，原样写入剪贴板。"))
                                .put("title", JSONObject().put("type", "string").put("maxLength", 80).put("description", "可选，卡片标题，默认“复制这段”。")),
                        )
                        .put("required", JSONArray().put("text")),
                ),
            )
    }
}
