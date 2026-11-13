package cn.hdfk7.android.iptv.feature.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import cn.hdfk7.android.iptv.R

internal enum class PlaybackFailure(val message: Int) {
    Timeout(R.string.stream_error_timeout),
    ProxyAuthentication(R.string.stream_error_proxy),
    Decoder(R.string.stream_error_decoder),
    Other(R.string.stream_error)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackFailure(code: Int?, cause: Throwable?): PlaybackFailure {
    if (generateSequence(cause, nextFunction = { failure -> failure.cause }).any(predicate = { cause ->
            cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 407
        })) {
        return PlaybackFailure.ProxyAuthentication
    }
    return when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackFailure.Timeout
        in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackFailure.Decoder
        else -> PlaybackFailure.Other
    }
}
