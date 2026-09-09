package com.example.yt3chardirect

data class Candidate(
    val id: Long,
    val handle: String,
    val score: Int,
    val state: String,
    val reason: String,
    val clearCount: Int,
    val checks: Int,
    val channelId: String?,
    val lastError: String?
)

object States {
    const val QUEUED = "queued"
    const val PRELIM = "prelim"
    const val STRONG = "strong"
    const val BUSY = "busy"
    const val UNKNOWN = "unknown"
    const val INVALID = "invalid"
}

data class ProbeResult(
    val kind: String,
    val evidence: String,
    val channelId: String? = null,
    val httpStatus: Int? = null
) {
    companion object {
        const val OCCUPIED = "occupied"
        const val NOT_FOUND = "not_found"
        const val UNKNOWN = "unknown"
        const val BLOCKED = "blocked"
        const val INVALID = "invalid"
    }
}
