package com.velvet.app.data

/**
 * Pure Kotlin Semantic Versioning comparator.
 *
 * Supports:
 * - 'v' or 'Version ' prefix removal
 * - Multi-component numerical comparison (e.g. 0.24.9 < 0.24.10)
 * - Pre-release tags (e.g. 2.7-beta1 < 2.7)
 * - Build numbers (e.g. build-100)
 */
object SemVer {
    private val prefixRegex = Regex("""^(?:Version\s*|v)""", RegexOption.IGNORE_CASE)
    private val buildRegex = Regex("""^build-(\d+)$""", RegexOption.IGNORE_CASE)

    data class ParsedVersion(
        val numbers: List<Int>,
        val preRelease: String? = null,
        val isBuildOnly: Boolean = false,
        val buildNumber: Int = 0,
    ) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int {
            if (isBuildOnly && other.isBuildOnly) {
                return buildNumber.compareTo(other.buildNumber)
            }
            if (isBuildOnly) return 1
            if (other.isBuildOnly) return -1

            // Compare numeric components
            val maxLen = maxOf(numbers.size, other.numbers.size)
            for (i in 0 until maxLen) {
                val n1 = numbers.getOrElse(i) { 0 }
                val n2 = other.numbers.getOrElse(i) { 0 }
                if (n1 != n2) {
                    return n1.compareTo(n2)
                }
            }

            // Normal release has higher precedence than pre-release!
            // E.g. "2.7" > "2.7-beta1"
            if (preRelease == null && other.preRelease != null) return 1
            if (preRelease != null && other.preRelease == null) return -1
            if (preRelease != null && other.preRelease != null) {
                return comparePreRelease(preRelease, other.preRelease)
            }
            return 0
        }

        private fun comparePreRelease(p1: String, p2: String): Int {
            val numRegex = Regex("""\d+""")
            val n1 = numRegex.find(p1)?.value?.toIntOrNull()
            val n2 = numRegex.find(p2)?.value?.toIntOrNull()
            val prefix1 = p1.replace(numRegex, "")
            val prefix2 = p2.replace(numRegex, "")
            val prefixCmp = prefix1.compareTo(prefix2, ignoreCase = true)
            if (prefixCmp != 0) return prefixCmp
            if (n1 != null && n2 != null) return n1.compareTo(n2)
            return p1.compareTo(p2, ignoreCase = true)
        }
    }

    fun parse(versionStr: String): ParsedVersion {
        val clean = versionStr.trim().replace(prefixRegex, "")
        val buildMatch = buildRegex.matchEntire(clean)
        if (buildMatch != null) {
            val num = buildMatch.groupValues[1].toIntOrNull() ?: 0
            return ParsedVersion(numbers = emptyList(), isBuildOnly = true, buildNumber = num)
        }
        val parts = clean.split("-", limit = 2)
        val numPart = parts[0]
        val prePart = if (parts.size > 1) parts[1] else null

        val numbers = numPart.split(".").mapNotNull { it.toIntOrNull() }
        return ParsedVersion(
            numbers = if (numbers.isEmpty()) listOf(0) else numbers,
            preRelease = prePart,
        )
    }

    fun compare(v1: String, v2: String): Int {
        return parse(v1).compareTo(parse(v2))
    }

    fun isNewer(remote: String, local: String): Boolean {
        return compare(remote, local) > 0
    }
}
