package com.example.data.online

import com.example.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP configuration for optional online enhancements.
 * Every online request uses a clear User-Agent and a short timeout (~5s)
 * so network delays never block or slow down offline navigation.
 */
object OnlineHttpConfig {

    fun userAgent(): String = "RahYab/${BuildConfig.VERSION_NAME} (personal navigation app)"

    fun createShortTimeoutClient(timeoutSeconds: Long = 5L): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 2L, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", userAgent())
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}
