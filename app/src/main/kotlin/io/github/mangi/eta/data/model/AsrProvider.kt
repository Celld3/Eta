package io.github.mangi.eta.data.model

import io.github.mangi.eta.BuildConfig
import kotlinx.serialization.Serializable

@Serializable
enum class AsrProvider {
    QWEN_REALTIME, QWEN_FLASH, QWEN_FILE, DOUBAO,

    /** 本地离线识别，模型按需下载，识别过程不联网。 */
    LOCAL,
    ;

    companion object {
        /** 设置页与下拉框可选的项；本地识别未启用时整条链路不出现。 */
        val selectable: List<AsrProvider> =
            entries.filter { it != LOCAL || BuildConfig.LOCAL_ASR_ENABLED }

        fun fromName(name: String): AsrProvider? = entries.firstOrNull { it.name == name }
    }
}
