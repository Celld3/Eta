package io.github.mangi.eta.agent.voice.local

import java.io.File

/**
 * 本地离线识别的模型清单。
 *
 * 体积与摘要写死在代码里：下载器只按这里的大小和 SHA-256 验收，任何不符的文件都不会被采用，
 * 因此模型来源即便换镜像也不影响可信度。摘要取自官方 release 资产的 digest 字段。
 */
internal data class LocalAsrModel(
    val id: String,
    /** 目录名，也是解压后的落点。 */
    val directoryName: String,
    val fileName: String,
    /** 官方地址；镜像只改变传输路径。 */
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** 归档解压后模型目录内必须存在的文件，缺一即视为未就绪。 */
    val requiredFiles: List<String>,
) {
    /** 解压后各文件相对模型目录的固定名字。 */
    val encoderFile: String get() = "encoder.int8.onnx"
    val decoderFile: String get() = "decoder.onnx"
    val joinerFile: String get() = "joiner.int8.onnx"
    val tokensFile: String get() = "tokens.txt"

    fun encoder(root: File): File = File(root, encoderFile)
    fun decoder(root: File): File = File(root, decoderFile)
    fun joiner(root: File): File = File(root, joinerFile)
    fun tokens(root: File): File = File(root, tokensFile)
}

internal object LocalAsrModels {
    /**
     * X-ASR 160ms 流式 Zipformer，中文 + 英文混说，模型自带标点。
     * 160ms 是四档延迟里出字最快的一档，体积与其余几档几乎相同（约 128 MB）。
     */
    val X_ASR_160MS = LocalAsrModel(
        id = "x-asr-160ms-int8",
        directoryName = "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
        fileName = "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
        sizeBytes = 133_898_007L,
        sha256 = "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399",
        requiredFiles = listOf(
            "encoder.int8.onnx",
            "decoder.onnx",
            "joiner.int8.onnx",
            "tokens.txt",
        ),
    )

    /** 当前启用的模型。切换档位只改这里，界面与下载逻辑都按它走。 */
    val active: LocalAsrModel = X_ASR_160MS
}
