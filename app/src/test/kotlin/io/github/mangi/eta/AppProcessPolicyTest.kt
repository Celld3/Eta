package io.github.mangi.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.celld3.eta", "io.github.celld3.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.celld3.eta:voice", "io.github.celld3.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.celld3.eta:voice_session", "io.github.celld3.eta"))
    }
}
