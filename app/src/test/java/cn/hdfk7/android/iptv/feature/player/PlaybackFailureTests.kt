package cn.hdfk7.android.iptv.feature.player

import androidx.media3.common.PlaybackException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackFailureTests {
    @Test
    fun reportsSpecificReasonsOnlyForRecognizedErrors(): Unit {
        assertEquals(PlaybackFailure.Timeout,
            playbackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(PlaybackFailure.Decoder,
            playbackFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
        assertEquals(PlaybackFailure.Other,
            playbackFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
        assertEquals(PlaybackFailure.Other, playbackFailure(null, null))
    }
}
