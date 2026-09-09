package com.example.yt3chardirect

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

object DirectChecker {
    private val channelIdPatterns = listOf(
        Regex("""<meta[^>]+itemprop=["']channelId["'][^>]+content=["'](UC[A-Za-z0-9_-]{20,})["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["'](UC[A-Za-z0-9_-]{20,})["'][^>]+itemprop=["']channelId["']""", RegexOption.IGNORE_CASE),
        Regex(""""externalId"\s*:\s*"(UC[A-Za-z0-9_-]{20,})"""", RegexOption.IGNORE_CASE)
    )

    fun probe(handle: String, about: Boolean): ProbeResult {
        val bad = HandleScoring.validate(handle)
        if (bad != null) return ProbeResult(ProbeResult.INVALID, bad)

        var current = "https://www.youtube.com/@${handle}" + if (about) "/about" else ""
        val visited = HashSet<String>()

        try {
            repeat(5) {
                if (!visited.add(current)) return ProbeResult(ProbeResult.UNKNOWN, "redirect_loop")

                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                conn.useCaches = false
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")

                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return ProbeResult(ProbeResult.UNKNOWN, "redirect_without_location", httpStatus = code)
                    val next = URI(current).resolve(loc).toString()
                    val host = try { URI(next).host?.lowercase(Locale.US) } catch (_: Exception) { null }
                    if (host != "youtube.com" && host != "www.youtube.com" && host != "m.youtube.com") {
                        return ProbeResult(ProbeResult.BLOCKED, "external_redirect:$host", httpStatus = code)
                    }
                    current = next
                    return@repeat
                }

                if (code == 404 || code == 410) {
                    conn.disconnect()
                    return ProbeResult(ProbeResult.NOT_FOUND, "http_$code", httpStatus = code)
                }

                if (code == 403 || code == 429) {
                    conn.disconnect()
                    return ProbeResult(ProbeResult.BLOCKED, "youtube_$code", httpStatus = code)
                }

                if (code >= 500) {
                    conn.disconnect()
                    return ProbeResult(ProbeResult.UNKNOWN, "youtube_$code", httpStatus = code)
                }

                if (code != 200) {
                    conn.disconnect()
                    return ProbeResult(ProbeResult.UNKNOWN, "http_$code", httpStatus = code)
                }

                val html = readLimited(conn, 2_500_000)
                conn.disconnect()

                for (p in channelIdPatterns) {
                    val m = p.find(html)
                    if (m != null) return ProbeResult(ProbeResult.OCCUPIED, "target_channel_id", m.groupValues[1], code)
                }

                canonicalEvidence(html, handle)?.let { return it.copy(httpStatus = code) }

                val exact = Regex(
                    """"(?:vanityChannelUrl|canonicalBaseUrl)"\s*:\s*"[^"]*/@${Regex.escape(handle)}(?:["/?#])""",
                    RegexOption.IGNORE_CASE
                )
                if (exact.containsMatchIn(html)) {
                    return ProbeResult(ProbeResult.OCCUPIED, "handle_metadata", httpStatus = code)
                }

                val finalPath = try { URI(current).path ?: "" } catch (_: Exception) { "" }
                val channelMatch = Regex("""^/channel/(UC[A-Za-z0-9_-]{20,})""").find(finalPath)
                if (channelMatch != null) {
                    return ProbeResult(ProbeResult.OCCUPIED, "redirect_channel", channelMatch.groupValues[1], code)
                }

                val lower = html.lowercase(Locale.US)
                val missingSignals = listOf(
                    "this page isn't available",
                    "this page isn’t available",
                    "this page is not available",
                    "the page you requested cannot be found",
                    "эта страница недоступна",
                    "страница недоступна",
                    "페이지를 사용할 수 없습니다",
                    "페이지를 찾을 수 없습니다"
                )
                if (missingSignals.any { lower.contains(it) }) {
                    return ProbeResult(ProbeResult.NOT_FOUND, "missing_page_text", httpStatus = code)
                }

                if (lower.contains("captcha") || lower.contains("unusual traffic")) {
                    return ProbeResult(ProbeResult.BLOCKED, "captcha_or_traffic", httpStatus = code)
                }

                return ProbeResult(ProbeResult.UNKNOWN, "ambiguous_200", httpStatus = code)
            }

            return ProbeResult(ProbeResult.UNKNOWN, "too_many_redirects")
        } catch (e: java.net.SocketTimeoutException) {
            return ProbeResult(ProbeResult.UNKNOWN, "timeout")
        } catch (e: Exception) {
            return ProbeResult(ProbeResult.UNKNOWN, "network:${e.javaClass.simpleName}")
        }
    }

    private fun canonicalEvidence(html: String, handle: String): ProbeResult? {
        val patterns = listOf(
            Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<link[^>]+href=["']([^"']+)["'][^>]+rel=["']canonical["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            val href = m.groupValues[1].replace("&amp;", "&")
            try {
                val path = URI(href).path ?: continue
                val ch = Regex("""^/channel/(UC[A-Za-z0-9_-]{20,})""").find(path)
                if (ch != null) return ProbeResult(ProbeResult.OCCUPIED, "canonical_channel", ch.groupValues[1])
                if (path.startsWith("/@")) {
                    val got = path.removePrefix("/@").substringBefore("/").lowercase(Locale.US)
                    if (got == handle.lowercase(Locale.US)) {
                        return ProbeResult(ProbeResult.OCCUPIED, "canonical_handle")
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun readLimited(conn: HttpURLConnection, maxBytes: Int): String {
        val stream = try { conn.inputStream } catch (_: Exception) { conn.errorStream }
        if (stream == null) return ""
        BufferedInputStream(stream).use { input ->
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < maxBytes) {
                val want = minOf(buf.size, maxBytes - total)
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
