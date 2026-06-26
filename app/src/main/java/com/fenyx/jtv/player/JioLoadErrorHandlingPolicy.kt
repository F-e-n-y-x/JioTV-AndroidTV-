package com.fenyx.jtv.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Jio's live stream URLs carry a short-lived Akamai token (`__hdnea__`) that expires ~120s after it
 * is issued. When it expires the CDN returns HTTP 403/404 on segments, which the default policy
 * treats as NON-retryable -> a fatal player error -> the whole stream reloads ("loading" flash).
 *
 * This policy makes 403/404/410 retryable for a few attempts with a short backoff. Combined with the
 * ResolvingDataSource that rewrites every request with the latest refreshed token, the retried
 * request goes out with a valid token and succeeds — so playback continues without a visible reload.
 */
@UnstableApi
class JioLoadErrorHandlingPolicy(
    private val tokenRetryLimit: Int = 4
) : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 6) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val ex = loadErrorInfo.exception
        if (ex is HttpDataSource.InvalidResponseCodeException) {
            val code = ex.responseCode
            if (code == 403 || code == 404 || code == 410) {
                return if (loadErrorInfo.errorCount <= tokenRetryLimit) {
                    // Quick backoff; gives the token-refresh loop a beat to publish a fresh token.
                    500L * loadErrorInfo.errorCount
                } else {
                    C.TIME_UNSET // give up -> falls through to a fatal error (then app re-fetches)
                }
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
