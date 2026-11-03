package io.github.mangi.eta.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SharedContentIntakeParserTest {
    @Test
    fun `纯文本分享保留正文与来源`() {
        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello world")
            },
            sourceAppLabel = "WeChat",
            nonce = 7L,
        )

        assertEquals("hello world", intake?.text)
        assertEquals("WeChat", intake?.sourceAppLabel)
        assertEquals(7L, intake?.nonce)
        assertTrue(intake!!.imageUris.isEmpty())
    }

    @Test
    fun `划词只取选中文本`() {
        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, "selected sentence")
                putExtra(Intent.EXTRA_TEXT, "should be ignored")
            },
            sourceAppLabel = "Browser",
            nonce = 1L,
        )

        assertEquals("selected sentence", intake?.text)
    }

    @Test
    fun `单图分享接受 content 方案并拒绝不可读方案`() {
        val accepted = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/media/1"))
            },
            sourceAppLabel = null,
            nonce = 1L,
        )
        val rejected = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.com/a.png"))
            },
            sourceAppLabel = null,
            nonce = 1L,
        )

        assertEquals(listOf("content://media/external/images/media/1"), accepted?.imageUris)
        assertNull(rejected)
    }

    @Test
    fun `多图分享不限数量全部接收`() {
        val uris = ArrayList<Uri>()
        repeat(12) { index -> uris.add(Uri.parse("content://media/images/$index")) }

        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            },
            sourceAppLabel = null,
            nonce = 1L,
        )

        assertEquals(12, intake?.imageUris?.size)
        assertEquals("content://media/images/0", intake?.imageUris?.first())
        assertEquals("content://media/images/11", intake?.imageUris?.last())
    }

    @Test
    fun `空内容与无关 Action 都不产生预填`() {
        assertNull(
            SharedContentIntakeParser.parse(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   "),
                null,
                1L,
            ),
        )
        assertNull(SharedContentIntakeParser.parse(Intent(Intent.ACTION_VIEW), null, 1L))
        assertNull(SharedContentIntakeParser.parse(null, null, 1L))
    }

    @Test
    fun `草稿把正文和来源分行并保留补指令的位置`() {
        val draft = SharedContentIntake(
            text = "  hello  ",
            sourceAppLabel = "WeChat",
        ).composeDraft(sourceLine = "From WeChat")

        assertEquals("hello\n\nFrom WeChat\n\n", draft)
    }

    @Test
    fun `没有来源时不写来源行`() {
        val draft = SharedContentIntake(text = "hello").composeDraft(sourceLine = null)

        assertEquals("hello\n\n", draft)
        assertFalse(draft.contains("From"))
    }

    @Test
    fun `超长正文按上限截断`() {
        val draft = SharedContentIntake(text = "x".repeat(MAX_DRAFT_SOURCE_CHARS + 500))
            .composeDraft(sourceLine = null)

        assertEquals(MAX_DRAFT_SOURCE_CHARS + 2, draft.length)
    }

    @Test
    fun `冷启动解析分享内容而不是丢弃`() {
        val parsed = SharedContentIntake(text = "from wechat", sourceAppLabel = "WeChat", nonce = 3L)

        val intake = initialSharedIntake(
            savedInstanceState = null,
            parseFromIntent = { parsed },
            restoreFromState = { error("重建才走还原分支") },
        )

        assertEquals(parsed, intake)
    }

    @Test
    fun `重建时只还原已保存内容不重复解析`() {
        val restored = SharedContentIntake(text = "kept", sourceAppLabel = null, nonce = 1L)
        val state = Bundle()

        val intake = initialSharedIntake(
            savedInstanceState = state,
            parseFromIntent = { error("重建不该再解析 Intent") },
            restoreFromState = { bundle ->
                assertEquals(state, bundle)
                restored
            },
        )

        assertEquals(restored, intake)
    }

    @Test
    fun `解析不出内容时预填为空而不是报错`() {
        val intake = initialSharedIntake(
            savedInstanceState = null,
            parseFromIntent = { null },
            restoreFromState = { error("重建才走还原分支") },
        )

        assertNull(intake)
    }

    @Test
    fun `非图片附件走文件通道而不是被丢弃`() {
        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.tencent.mm/file/1"))
            },
            sourceAppLabel = null,
            nonce = 1L,
        )

        assertTrue(intake!!.imageUris.isEmpty())
        assertEquals(listOf("content://com.tencent.mm/file/1"), intake.fileUris)
    }

    @Test
    fun `按真实 MIME 分流而不信声明类型`() {
        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/images/9"))
            },
            sourceAppLabel = null,
            nonce = 1L,
            mimeTypeOf = { "image/jpeg" },
        )

        assertEquals(listOf("content://media/images/9"), intake?.imageUris)
        assertTrue(intake!!.fileUris.isEmpty())
    }

    @Test
    fun `判不出类型时按普通文件处理`() {
        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://unknown/1"))
            },
            sourceAppLabel = null,
            nonce = 1L,
        )

        assertEquals(listOf("content://unknown/1"), intake?.fileUris)
        assertTrue(intake!!.imageUris.isEmpty())
    }

    @Test
    fun `多文件分享按上限截断`() {
        val many = ArrayList<Uri>()
        repeat(MAX_SHARED_FILES + 5) { index ->
            many.add(Uri.parse("content://docs/item/$index"))
        }

        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)
            },
            sourceAppLabel = null,
            nonce = 1L,
        )

        assertEquals(MAX_SHARED_FILES, intake?.fileUris?.size)
    }

    @Test
    fun `只有附件没有正文也算有效预填`() {
        val intake = SharedContentIntakeParser.parse(
            intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/archive.zip"))
            },
            sourceAppLabel = null,
            nonce = 1L,
        )

        assertFalse(intake!!.isEmpty)
        assertNull(SharedContentIntakeParser.parse(Intent(Intent.ACTION_SEND), null, 1L))
    }
}
