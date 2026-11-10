package com.seeandsay.chronologer.data

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Pulls the eight chronologer fields out of an LLM frame.
 * A frame that is or contains NO_CHANGE is dropped so unchanged scenes never reach Room.
 */
object ChronologerParser {
    private val noChange = Regex("""NO_CHANGE""")

    private val bracketedField = Regex(
        """\[\s*(TIMESTAMP|ACTIVE\s+DEVICE|PRIMARY\s+APP\s*(?:&|AND)\s*CONTEXT|PRIMARY\s+ACTIVITY|VISIBLE\s+TEXT\s*(?:&|AND)\s*CODE|MICRO[-\s]?ACTIONS\s*(?:&|AND)\s*STATE|PHYSICAL\s+ACTION|AMBIENT\s+CONTEXT)\s*(?::\s*([^\]]*))?\s*]\s*[:\-–—]?\s*""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val looseField = Regex(
        """^\s*(?:[-*]\s*)?(?:\*\*|__)?\s*\[?\s*(TIMESTAMP|ACTIVE\s+DEVICE|PRIMARY\s+APP\s*(?:&|AND)\s*CONTEXT|PRIMARY\s+ACTIVITY|VISIBLE\s+TEXT\s*(?:&|AND)\s*CODE|MICRO[-\s]?ACTIONS\s*(?:&|AND)\s*STATE|PHYSICAL\s+ACTION|AMBIENT\s+CONTEXT)\s*]?\s*(?:\*\*|__)?\s*[:\-–—]\s*(.+?)\s*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    private val timestampFormatters = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_ZONED_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US),
        DateTimeFormatter.ofPattern("h:mm a", Locale.US),
    )

    fun parse(rawOutput: String): NarrationLog? {
        if (rawOutput.isBlank() || noChange.containsMatchIn(rawOutput)) {
            return null
        }

        val extracted = linkedMapOf(
            "formattedTimestamp" to "",
            "activeDevice" to "",
            "primaryApp" to "",
            "primaryActivity" to "",
            "visibleText" to "",
            "microActions" to "",
            "physicalAction" to "",
            "ambientContext" to "",
        )
        extractBracketed(rawOutput, extracted)
        fillMissingLooseLines(rawOutput, extracted)

        val formattedTimestamp = extracted.getValue("formattedTimestamp")
        return NarrationLog(
            timestamp = epochMillis(formattedTimestamp),
            formattedTimestamp = formattedTimestamp,
            activeDevice = extracted.getValue("activeDevice"),
            primaryApp = extracted.getValue("primaryApp"),
            primaryActivity = extracted.getValue("primaryActivity"),
            visibleText = extracted.getValue("visibleText"),
            microActions = extracted.getValue("microActions"),
            physicalAction = extracted.getValue("physicalAction"),
            ambientContext = extracted.getValue("ambientContext"),
            rawPayload = rawOutput,
        )
    }

    private fun extractBracketed(rawOutput: String, extracted: MutableMap<String, String>) {
        val matches = bracketedField.findAll(rawOutput).toList()
        matches.forEachIndexed { index, match ->
            val key = canonicalKey(match.groupValues[1]) ?: return@forEachIndexed
            val inline = clean(match.groupValues.getOrElse(2) { "" })
            val valueEnd = if (index + 1 < matches.size) matches[index + 1].range.first else rawOutput.length
            val trailing = clean(rawOutput.substring(match.range.last + 1, valueEnd))
            val value = inline.ifEmpty { trailing }
            if (value.isNotEmpty()) {
                extracted[key] = value
            }
        }
    }

    private fun fillMissingLooseLines(rawOutput: String, extracted: MutableMap<String, String>) {
        looseField.findAll(rawOutput).forEach { match ->
            val key = canonicalKey(match.groupValues[1]) ?: return@forEach
            if (extracted[key].isNullOrEmpty()) {
                extracted[key] = clean(match.groupValues[2])
            }
        }
    }

    private fun canonicalKey(label: String): String? {
        val compact = label
            .uppercase(Locale.US)
            .replace('-', ' ')
            .replace('&', ' ')
            .replace(Regex("""\bAND\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return when (compact) {
            "TIMESTAMP" -> "formattedTimestamp"
            "ACTIVE DEVICE" -> "activeDevice"
            "PRIMARY APP CONTEXT" -> "primaryApp"
            "PRIMARY ACTIVITY" -> "primaryActivity"
            "VISIBLE TEXT CODE" -> "visibleText"
            "MICRO ACTIONS STATE" -> "microActions"
            "PHYSICAL ACTION" -> "physicalAction"
            "AMBIENT CONTEXT" -> "ambientContext"
            else -> null
        }
    }

    private fun clean(raw: String): String {
        return raw
            .trim()
            .trim('"', '\'', '`')
            .replace(Regex("""\r\n?"""), "\n")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun epochMillis(formatted: String): Long {
        val trimmed = formatted.trim()
        if (trimmed.isEmpty()) {
            return System.currentTimeMillis()
        }
        trimmed.toLongOrNull()?.let { numeric ->
            return if (numeric < 10_000_000_000L) numeric * 1_000 else numeric
        }
        val zone = ZoneId.systemDefault()
        for (formatter in timestampFormatters) {
            val parsed = try {
                formatter.parse(trimmed)
            } catch (_: DateTimeException) {
                null
            } ?: continue
            val instant = try {
                when {
                    parsed.isSupported(ChronoField.INSTANT_SECONDS) -> Instant.from(parsed)
                    parsed.isSupported(ChronoField.OFFSET_SECONDS) && parsed.isSupported(ChronoField.YEAR) ->
                        Instant.from(parsed)
                    parsed.isSupported(ChronoField.YEAR) && parsed.isSupported(ChronoField.HOUR_OF_DAY) ->
                        LocalDateTime.from(parsed).atZone(zone).toInstant()
                    parsed.isSupported(ChronoField.HOUR_OF_DAY) ->
                        LocalTime.from(parsed).atDate(LocalDate.now(zone)).atZone(zone).toInstant()
                    else -> null
                }
            } catch (_: DateTimeException) {
                null
            }
            if (instant != null) {
                return instant.toEpochMilli()
            }
        }
        return System.currentTimeMillis()
    }
}
