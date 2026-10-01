package dev.rortega.orchardnotes.cloudkit

import okhttp3.Interceptor
import okhttp3.Response

/** Headers every iCloud web-service call carries, matching the www.icloud.com client. */
class IcloudHeadersInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .header("Origin", ICLOUD_ORIGIN)
            .header("Referer", "$ICLOUD_ORIGIN/")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val ICLOUD_ORIGIN = "https://www.icloud.com"
    }
}

/** An iCloud call failed in a way the caller may want to distinguish. */
open class IcloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The session is no longer valid (HTTP 401/421) and the user must sign in again. */
class SessionExpiredException(message: String = "Your iCloud session has expired.") : IcloudException(message)

/** iCloud answered, but not with the shape this app understands. */
class UnexpectedResponseException(message: String) : IcloudException(message)
