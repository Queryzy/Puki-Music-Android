package com.puki.music

import android.app.Application
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PukiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val resilientDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    try {
                        val all = InetAddress.getAllByName(hostname).toList()
                        if (all.isNotEmpty()) all else throw e
                    } catch (_: Exception) {
                        if (hostname.contains("youtubei.googleapis.com", ignoreCase = true)) {
                            // High-availability Google Anycast IPs to bypass Indonesian cellular DNS drops
                            listOf(
                                InetAddress.getByName("142.250.190.42"),
                                InetAddress.getByName("172.217.16.202"),
                                InetAddress.getByName("142.250.200.42")
                            )
                        } else {
                            throw e
                        }
                    }
                }
            }
        }

        val client = OkHttpClient.Builder()
            .dns(resilientDns)
            .retryOnConnectionFailure(true)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val downloader = object : Downloader() {
            override fun execute(request: Request): Response {
                val httpReqBuilder = okhttp3.Request.Builder()
                    .url(request.url())
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; CONSENT=PENDING+999")

                request.headers().forEach { (key, values) ->
                    if (!key.equals("User-Agent", ignoreCase = true) &&
                        !key.equals("Cookie", ignoreCase = true) &&
                        !key.equals("Accept-Language", ignoreCase = true)
                    ) {
                        httpReqBuilder.header(key, values.joinToString("; "))
                    }
                }

                val httpMethod = request.httpMethod()
                val requestBody = request.dataToSend()

                if (httpMethod.equals("POST", ignoreCase = true) && requestBody != null) {
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                    val body = requestBody.toRequestBody(mediaType)
                    httpReqBuilder.post(body)
                }

                val okHttpResponse = client.newCall(httpReqBuilder.build()).execute()
                val responseBody = okHttpResponse.body?.string()

                return Response(
                    okHttpResponse.code,
                    okHttpResponse.message,
                    okHttpResponse.headers.toMultimap(),
                    responseBody,
                    okHttpResponse.request.url.toString()
                )
            }
        }

        NewPipe.init(downloader, Localization.DEFAULT, ContentCountry("US"))
    }
}
