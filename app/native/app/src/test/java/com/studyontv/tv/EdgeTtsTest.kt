package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EdgeTtsTest {
    @Test
    fun secMsGecAlignsToFiveMinuteWindow() {
        val t0 = 1_700_000_100_000L // aligned down to 1_700_000_100s
        val t1 = 1_700_000_399_000L // within same 300s window
        val t2 = 1_700_000_400_000L // next 300s window
        val gec0 = EdgeTts.secMsGec(t0)
        assertEquals(64, gec0.length)
        assertEquals(gec0, EdgeTts.secMsGec(t1))
        assertNotEquals(gec0, EdgeTts.secMsGec(t2))
    }

    @Test
    fun defaultVoiceMapsLanguages() {
        assertEquals("zh-HK-HiuGaaiNeural", EdgeTts.defaultVoice("zh-HK"))
        assertEquals("zh-HK-HiuGaaiNeural", EdgeTts.defaultVoice("yue-HK"))
        assertEquals("zh-TW-HsiaoChenNeural", EdgeTts.defaultVoice("zh-TW"))
        assertEquals("en-GB-SoniaNeural", EdgeTts.defaultVoice("en-GB"))
        assertEquals("en-US-JennyNeural", EdgeTts.defaultVoice("en-US"))
        assertEquals("zh-CN-XiaoxiaoNeural", EdgeTts.defaultVoice(""))
    }
}
