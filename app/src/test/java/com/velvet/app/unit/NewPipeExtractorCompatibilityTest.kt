package com.velvet.app.unit

import com.velvet.app.BuildConfig
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.YouTube
import com.velvet.app.data.YtDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class NewPipeExtractorCompatibilityTest {

    @Test
    fun buildConfigTargetsNewPipeExtractor0271() {
        assertEquals("v0.27.1", BuildConfig.NEWPIPE_VERSION)
    }

    @Test
    fun streamResolverBuildsVisionOsAndWebHeadersCleanly() {
        val visionUrl = "https://rr1---sn-4g5e6nsz.googlevideo.com/videoplayback?itag=251&c=VISIONOS&expire=1900000000"
        val visionHeaders = StreamResolver.headersFor(visionUrl)
        assertTrue(visionHeaders["User-Agent"]?.contains("visionOS") == true)

        val webUrl = "https://rr1---sn-4g5e6nsz.googlevideo.com/videoplayback?itag=251&c=WEB&expire=1900000000"
        val webHeaders = StreamResolver.headersFor(webUrl)
        assertEquals(YtDownloader.USER_AGENT, webHeaders["User-Agent"])
        assertEquals("https://www.youtube.com", webHeaders["Origin"])

        val otherUrl = "https://rr1---sn-4g5e6nsz.googlevideo.com/videoplayback?expire=1900000000"
        assertEquals(mapOf("User-Agent" to YtDownloader.USER_AGENT), StreamResolver.headersFor(otherUrl))
    }

    @Test
    fun streamInfoRecordDescriptionPreservesDistributorAudioHint() {
        val info = StreamInfo(
            0,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            StreamType.AUDIO_STREAM,
            "dQw4w9WgXcQ",
            "Test Song",
            0,
        )
        info.uploaderName = "Original Artist - Topic"
        info.duration = 215L
        info.description = Description("Provided to YouTube by Universal Music Group\nReleased on 2024", Description.Type.PLAIN_TEXT)

        val song = YouTube.songFrom(info)
        assertEquals("Provided to YouTube by Universal Music Group", song.releaseDescriptionHint)
        assertTrue(song.isTopicRelease)
    }
}
