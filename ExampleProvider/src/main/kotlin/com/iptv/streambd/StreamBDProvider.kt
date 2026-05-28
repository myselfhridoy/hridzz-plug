package com.iptv.streambd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.Drm
import java.util.concurrent.TimeUnit
import java.net.URL
import android.util.Log

class StreamBDProvider : MainAPI() {
    override var mainUrl = "https://streambd-iptv.netlify.app/playlists/main.json" // আপনার মেইন JSON লিংক
    override var name = "StreamBD IPTV"
    override val hasMainPage = true
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Live)

    // ডেটা মডেলসমূহ
    data class PlaylistInfo(val url: String, val logo: String?)
    data class M3UChannel(val name: String, val url: String, val logo: String)
    data class ExtractedData(val url: String, val headers: Map<String, String>?, val drm: Drm? = null)

    companion object {
        // ৫ মিনিটের ক্যাশ টিটিএল (TTL)
        private const val CACHE_TTL_MS = 5 * 60 * 1000
        private val tokenResolveCache = HashMap<String, Pair<ExtractedData?, Long>>()
        private const val STREAMBD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        // স্ট্রিমিং এক্সটেনশন ও প্রোটোকল ডিটেকশন Regex
        private val STREAM_EXTENSIONS = Regex("\\.(m3u8|mpd|mp4|mkv|ts|webm|flv|avi|mov|mpeg|mpg|m4s|f4m|ism|sdp)(\\?|$)", RegexOption.IGNORE_CASE)
        private val STREAM_PROTOCOLS = Regex("^(rtmp|rtsp|udp|srt)://", RegexOption.IGNORE_CASE)
    }

    // --- ১. হোমপেজ এবং প্লেলিস্ট লোডার ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homepageLists = arrayListOf<HomePageList>()
        try {
            val jsonResponse = app.get(mainUrl).text ?: return HomePageResponse(homepageLists)
            val outerList = tryParseJson<List<Map<String, Map<String, PlaylistInfo>>>>(jsonResponse)
            val playlistsMap = outerList?.firstOrNull()?.get("playlists") ?: return HomePageResponse(homepageLists)

            for ((categoryName, info) in playlistsMap) {
                val m3uContent = app.get(info.url).text
                if (!m3uContent.isNullOrEmpty()) {
                    val channels = parseM3U(m3uContent, info.logo ?: "")
                    val searchResponses = channels.map { channel ->
                        LiveTvSearchResponse(
                            name = channel.name,
                            url = channel.url,
                            apiName = this.name,
                            type = TvType.Live,
                            posterUrl = channel.logo
                        )
                    }
                    if (searchResponses.isNotEmpty()) {
                        homepageLists.add(HomePageList(categoryName, searchResponses))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StreamBD", "Error loading main page", e)
        }
        return HomePageResponse(homepageLists)
    }

    override suspend fun load(url: String): LoadResponse {
        return LiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            apiName = this.name,
            dataUrl = url
        )
    }

    // --- ২. মেইন প্লেয়ার লিংক রেজল্যুশন লজিক ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parsedInput = parseUrlHeadersAndCustom(data)
            
            val resolved = resolveTokenForUrl(
                baseUrl = parsedInput.cleanUrl,
                tokenUrl = parsedInput.customParams["tokenurl"] ?: parsedInput.customParams["token-url"],
                tokenId = parsedInput.customParams["tokenid"] ?: "1",
                headers = parsedInput.headers,
                tokenMatch = parsedInput.customParams["tokenmatch"],
                tokenReplace = parsedInput.customParams["tokenreplace"]
            )

            if (resolved != null) {
                val isM3u8Link = resolved.url.contains(".m3u8", ignoreCase = true)
                // ExtractorLink এর নতুন কনস্ট্রাক্টর
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Live TV",
                        url = resolved.url,
                        referer = resolved.headers?.get("Referer") ?: resolved.url.substringBefore("/"),
                        quality = Qualities.Unknown.value,
                        isM3u8 = isM3u8Link
                        // headers এবং অন্যান্য ফিল্ড আপাতত বাদ দিলাম যাতে কনস্ট্রাক্টর কনফ্লিক্ট না করে
                    )
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

                    )
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // --- ৩. টোকেন রেজল্যুশন কোর ইঞ্জিন (TypeScript থেকে পোর্ট করা) ---
    private suspend fun resolveTokenForUrl(
        baseUrl: String,
        tokenUrl: String?,
        tokenId: String?,
        headers: Map<String, String>?,
        tokenMatch: String?,
        tokenReplace: String?
    ): ExtractedData? {
        if (tokenUrl == null) return ExtractedData(baseUrl, headers)

        // ক্যাশ কি (Cache Key) জেনারেট করা
        val cacheKey = "$baseUrl||$tokenUrl||${tokenId ?: ""}||${tokenMatch ?: ""}||${tokenReplace ?: ""}"
        val cached = tokenResolveCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.second) < CACHE_TTL_MS) {
            return cached.first
        }

        val result = resolveTokenForUrlInternal(baseUrl, tokenUrl, tokenId, headers, tokenMatch, tokenReplace)
        if (result != null) {
            tokenResolveCache[cacheKey] = Pair(result, System.currentTimeMillis())
        }
        return result
    }

    private suspend fun resolveTokenForUrlInternal(
        baseUrl: String,
        tokenUrl: String,
        tokenId: String?,
        headers: Map<String, String>?,
        tokenMatch: String?,
        tokenReplace: String?
    ): ExtractedData? {
        val lowerTokenUrl = tokenUrl.lowercase().trim()

        // Handler A: infinityfree (if) লজিক
        if (lowerTokenUrl == "if") {
            val resp = app.get(baseUrl, headers = headers ?: emptyMap())
            val body = resp.text ?: return null
            val currentHeaders = resp.headers.toMap().toMutableMap()

            if (body.contains("#EXTM3U")) {
                var streamUrl = ""
                val streamHeaders = currentHeaders.toMutableMap()
                var drmType: String? = null
                var drmKey: String? = null

                body.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_type=")) {
                        val type = trimmed.substringAfter("=").lowercase()
                        if (type.contains("widevine")) drmType = "widevine"
                        if (type.contains("clearkey")) drmType = "clearkey"
                    }
                    if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                        drmKey = trimmed.substringAfter("=")
                    }
                    if (trimmed.startsWith("#EXTVLCOPT:")) {
                        val payload = trimmed.substringAfter("#EXTVLCOPT:").trim()
                        val name = payload.substringBefore("=").trim()
                        val value = payload.substringAfter("=").trim()
                        streamHeaders[name] = value
                    }
                    if (!trimmed.startsWith("#") && trimmed.isNotEmpty()) {
                        streamUrl = trimmed
                    }
                }
                if (streamUrl.isNotEmpty()) {
                    val drm = if (drmType == "clearkey" && drmKey != null) {
                        val parts = drmKey!!.split(":")
                        if (parts.size == 2) Drm(Drm.ClearKey, keyId = parts[0], key = parts[1]) else null
                    } else if (drmType == "widevine" && drmKey != null) {
                        Drm(Drm.Widevine, licenseServer = drmKey!!)
                    } else null

                    return ExtractedData(streamUrl, streamHeaders, drm)
                }
            } else {
                // HTML ইফ্রেম বা ক্রিকএইচডি বা জেএসডিকোড ব্যাকআপ চেক
                val crichdRegex = Regex("return\\s*\\(\\s*\\[(.*?)\\].join\\(", RegexOption.IGNORE_CASE)
                val m = crichdRegex.find(body)
                if (m != null) {
                    val chars = Regex("[\"']([^\"']*)[\"']").findAll(m.groupValues[1]).map { it.groupValues[1] }.joinToString("")
                    var src = chars.replace("\\/", "/")
                    if (tokenMatch != null && tokenReplace != null) src = src.replace(tokenMatch, tokenReplace)
                    return ExtractedData(src, getStreamHeaders(baseUrl, headers))
                }
                
                val extracted = extractStreamFromHtml(body, tokenId)
                if (extracted != null) return ExtractedData(extracted, getStreamHeaders(baseUrl, headers))
            }
        }

        // Handler B: jsdecode লজিক
        if (lowerTokenUrl == "jsdecode") {
            val body = app.get(baseUrl, headers = headers ?: emptyMap()).text ?: return null
            // Unpack বা কাস্টম স্ক্রিপ্ট ডিকোডিং এর জন্য রেগুলার এক্সপ্রেশন ম্যাচিং
            val extracted = extractStreamFromHtml(body, tokenId)
            if (extracted != null) {
                var finalUrl = extracted
                if (tokenMatch != null && tokenReplace != null) finalUrl = finalUrl.replace(tokenMatch, tokenReplace)
                return ExtractedData(finalUrl, getStreamHeaders(baseUrl, headers), extractDrmFromText(body))
            }
        }

        // Handler C: crichd লজিক
        if (lowerTokenUrl == "crichd") {
            val body = app.get(baseUrl, headers = headers ?: emptyMap()).text ?: return null
            val regex = Regex("return\\s*\\(\\s*\\[(.*?)\\].join\\(", RegexOption.IGNORE_CASE)
            val match = regex.find(body)
            if (match != null) {
                val chars = Regex("[\"']([^\"']*)[\"']").findAll(match.groupValues[1]).map { it.groupValues[1] }.joinToString("")
                var src = chars.replace("\\/", "/")
                if (tokenMatch != null && tokenReplace != null) src = src.replace(tokenMatch, tokenReplace)
                return ExtractedData(src, getStreamHeaders(baseUrl, headers))
            }
        }

        // Handler D: fetchiframe / fetchandreplace লজিক
        if (lowerTokenUrl == "fetchiframe" || lowerTokenUrl == "fetchandreplace") {
            val body = app.get(baseUrl, headers = headers ?: emptyMap()).text ?: return null
            val iframeRegex = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val iframes = iframeRegex.findAll(body).map { it.groupValues[1] }.toList()
            if (iframes.isNotEmpty()) {
                val index = parseTokenId(tokenId) - 1
                var src = if (index in iframes.indices) iframes[index] else iframes[0]
                if (tokenMatch != null && tokenReplace != null) src = src.replace(tokenMatch, tokenReplace)
                return ExtractedData(src, getStreamHeaders(baseUrl, headers))
            }
        }

        // Handler E: কাস্টম ডিজিট আইডি (\1, \2, \3) হ্যান্ডলিং
        val handlerId = tokenUrl.trim().toIntOrNull()
        if (handlerId != null) {
            val body = app.get(baseUrl, headers = mapOf("User-Agent" to STREAMBD_USER_AGENT) + (headers ?: emptyMap())).text ?: return null
            var streamUrl: String? = null
            when (handlerId) {
                1 -> streamUrl = extractStreamFromJson(body, tokenId)
                2 -> streamUrl = extractStreamFromHtml(body, tokenId)
                3 -> streamUrl = extractStreamFromJson(body, tokenId) ?: extractStreamFromHtml(body, tokenId)
            }
            if (streamUrl != null) {
                val drm = if (handlerId == 3) extractDrmFromText(body) else null
                return ExtractedData(streamUrl, getStreamHeaders(baseUrl, headers), drm)
            }
        }

        return ExtractedData(baseUrl, headers)
    }

    // --- ৪. টেক্সট থেকে ডেটা এক্সট্রাক্ট করার ইন্টারনাল ইউটিলিটিস ---
    private fun extractStreamFromJson(payload: String, tokenId: String?): String? {
        val candidates = extractAllUrls(payload)
        val streams = candidates.filter { isStreamUrl(it) }
        val pool = if (streams.isNotEmpty()) streams else candidates
        if (pool.isEmpty()) return null
        val index = parseTokenId(tokenId) - 1
        return if (index in pool.indices) pool[index] else pool[0]
    }

    private fun extractStreamFromHtml(html: String, tokenId: String?): String? {
        return extractStreamFromJson(html, tokenId)
    }

    private fun extractAllUrls(payload: String): List<String> {
        val decoded = decodeEscapedPayload(payload)
        val candidates = mutableListOf<String>()
        val httpRegex = Regex("https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+", RegexOption.IGNORE_CASE)
        httpRegex.findAll(decoded).forEach {
            val u = it.value.trim()
            if (!candidates.contains(u)) candidates.add(u)
        }
        return candidates
    }

    private fun extractDrmFromText(text: String): Drm? {
        val widevineMatch = Regex("(?:widevine|licenseUrl|licenseServer)[a-zA-Z0-9_]*\\s*[:=]\\s*['\"](https?://[^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(text)
        if (widevineMatch != null) return Drm(Drm.Widevine, licenseServer = widevineMatch.groupValues[1])

        val rawPairMatch = Regex("([a-fA-F0-9]{32}):([a-fA-F0-9]{32})").find(text)
        if (rawPairMatch != null) {
            return Drm(Drm.ClearKey, keyId = rawPairMatch.groupValues[1], key = rawPairMatch.groupValues[2])
        }
        return null
    }

    private fun isStreamUrl(url: String): Boolean = STREAM_EXTENSIONS.containsMatchIn(url) || STREAM_PROTOCOLS.containsMatchIn(url)

    private fun decodeEscapedPayload(input: String): String {
        return input.replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
    }

    private fun parseTokenId(tokenId: String?): Int {
        if (tokenId == null) return 1
        val n = tokenId.toIntOrNull() ?: return 1
        return if (n < 1) 1 else n
    }

    private fun getStreamHeaders(baseUrl: String, originalHeaders: Map<String, String>?): Map<String, String> {
        val streamHeaders = (originalHeaders ?: emptyMap()).toMutableMap()
        try {
            val u = URL(baseUrl)
            streamHeaders["Referer"] = "${u.protocol}://${u.host}/"
            streamHeaders["Origin"] = "${u.protocol}://${u.host}"
        } catch (e: Exception) {}
        return streamHeaders
    }

    // --- ৫. M3U এবং ইউআরএল হেডার পার্সার হেল্পার ---
    private fun parseM3U(content: String, defaultLogo: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        var currentName = ""
        var currentLogo = defaultLogo
        val logoRegex = Regex("""tvg-logo=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF")) {
                val match = logoRegex.find(trimmed)
                currentLogo = match?.groupValues?.get(1) ?: defaultLogo
                currentName = trimmed.substringAfterLast(",").trim()
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                if (currentName.isNotEmpty()) {
                    channels.add(M3UChannel(currentName, trimmed, currentLogo))
                    currentName = ""
                    currentLogo = defaultLogo
                }
            }
        }
        return channels
    }

    data class ParsedUrl(val cleanUrl: String, val headers: Map<String, String>, val customParams: Map<String, String>)

    private fun parseUrlHeadersAndCustom(input: String): ParsedUrl {
        val parts = input.split("|")
        val cleanUrl = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        val customParams = mutableMapOf<String, String>()

        for (i in 1 until parts.size) {
            val pair = parts[i].split("=")
            if (pair.size == 2) {
                val key = pair[0].trim().lowercase()
                val value = pair[1].trim()
                if (key.startsWith("token")) {
                    customParams[key] = value
                } else {
                    headers[pair[0].trim()] = value
                }
            }
        }

        // ব্যাকআপ চেক: যদি URL এর কুয়েরি প্যারামিটারে TokenUrl থাকে
        if (cleanUrl.contains("TokenUrl=", ignoreCase = true)) {
            val uri = URL(cleanUrl.replace(" ", "%20"))
            uri.query?.split("&")?.forEach { param ->
                val p = param.split("=")
                if (p.size == 2 && p[0].lowercase().startsWith("token")) {
                    customParams[p[0].lowercase()] = p[1]
                }
            }
        }

        return ParsedUrl(cleanUrl, headers, customParams)
    }
}
