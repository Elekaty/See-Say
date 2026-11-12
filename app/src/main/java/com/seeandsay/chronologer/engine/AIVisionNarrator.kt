package com.seeandsay.chronologer.engine

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Base64
import com.seeandsay.chronologer.BuildConfig
import com.seeandsay.chronologer.data.AppDatabase
import com.seeandsay.chronologer.data.ChronologerParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends changed WebP frames to Gemini and keeps two tracks:
 * the full microscopic log goes to Room, and TTS speaks only the app and activity.
 */
class AIVisionNarrator(
    context: Context,
    private val camera: MetaCameraManager,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val frameProcessor: FrameProcessor = FrameProcessor(),
    initialApiKey: String = BuildConfig.GEMINI_API_KEY,
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tts = TextToSpeech(appContext, this)
    private var loopJob: Job? = null
    private var ttsReady = false
    private var previousRawOutput = ""

    var apiKey: String = initialApiKey

    var ttsEnabled: Boolean = true

    private val _status = MutableStateFlow("IDLE")
    val status: StateFlow<String> = _status.asStateFlow()

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.US
            _status.value = "TTS_READY"
        } else {
            _status.value = "TTS_UNAVAILABLE"
        }
    }

    fun openSource(
        mode: CameraSourceMode,
        activity: android.app.Activity?,
        handshake: Boolean = true,
    ) {
        loopJob?.cancel()
        loopJob = null
        frameProcessor.reset()
        previousRawOutput = ""
        camera.start(mode, activity, handshake)
    }

    suspend fun narrateCurrentFrame() {
        val deadline = SystemClock.elapsedRealtime() + FRAME_WAIT_MS
        var frame = camera.captureSnapshot()
        while (frame == null && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
            frame = camera.captureSnapshot()
        }
        if (frame == null) {
            _status.value = "WAITING_FOR_FRAME"
            return
        }
        consumeFrame(frame)
    }

    fun start(mode: CameraSourceMode, activity: android.app.Activity? = null) {
        frameProcessor.reset()
        previousRawOutput = ""
        camera.start(mode, activity)
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launch {
            while (isActive) {
                val started = SystemClock.elapsedRealtime()
                runCatching { captureAndNarrate() }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        _status.value = error.message?.ifBlank { null } ?: "NARRATION_FAILED"
                    }
                val remaining = SNAPSHOT_INTERVAL_MS - (SystemClock.elapsedRealtime() - started)
                if (remaining > 0) {
                    delay(remaining)
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        tts.stop()
        camera.closeSession()
        frameProcessor.reset()
        _status.value = "STOPPED"
    }

    fun release() {
        stop()
        tts.shutdown()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun captureAndNarrate() {
        val frame = camera.captureSnapshot() ?: return
        consumeFrame(frame)
    }

    private suspend fun consumeFrame(frame: android.graphics.Bitmap) {
        val webp = try {
            val cropRoi = camera.sourceMode.value == CameraSourceMode.META_GLASSES_DAT
            frameProcessor.processFrameIfChanged(frame, cropRoi)
        } finally {
            if (!frame.isRecycled) {
                frame.recycle()
            }
        } ?: run {
            _status.value = "DELTA_SKIP"
            return
        }
        val raw = requestChronolog(webp) ?: return
        previousRawOutput = raw
        val log = ChronologerParser.parse(raw)
        if (log == null) {
            _status.value = "NO_CHANGE"
            return
        }
        database.narrationDao().insertLog(log)
        _status.value = "LOGGED"
        if (ttsEnabled && ttsReady && (log.primaryApp.isNotBlank() || log.primaryActivity.isNotBlank())) {
            val spoken = "${log.primaryApp}. ${log.primaryActivity}"
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "chronologer-${log.timestamp}")
        }
    }

    private suspend fun requestChronolog(webp: ByteArray): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        apiKey = cleanKey
        if (cleanKey.isBlank()) {
            _status.value = "GEMINI_API_KEY_MISSING"
            return@withContext null
        }
        val prior = previousRawOutput.ifBlank { "(none)" }
        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", "Previous frame output:\n$prior"))
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "image/webp")
                                            .put("data", Base64.encodeToString(webp, Base64.NO_WRAP)),
                                    ),
                                ),
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 2048)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "low"),
                    ),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        val primary = postUntilReady(PRIMARY_ENDPOINT, cleanKey, payload)
        val result = when {
            primary.code == 200 -> primary
            primary.code == 0 -> {
                _status.value = "GEMINI_RETRYING 1 0"
                postGenerateContent(FALLBACK_ENDPOINT, cleanKey, payload)
            }
            primary.code == 404 || primary.code in RETRYABLE_STATUS -> {
                postUntilReady(FALLBACK_ENDPOINT, cleanKey, payload)
            }
            else -> primary
        }
        if (result.code != 200) {
            _status.value = "GEMINI_HTTP_${result.code} ${result.body.trim()}"
            return@withContext null
        }
        extractText(result.body)
    }

    private suspend fun postUntilReady(endpoint: String, cleanKey: String, payload: ByteArray): GeminiHttpResult {
        var result = postGenerateContent(endpoint, cleanKey, payload)
        var attempt = 0
        while (result.code in RETRYABLE_STATUS && attempt < MAX_UNAVAILABLE_RETRIES) {
            attempt += 1
            val waitMs = result.retryAfterMs ?: (1_000L shl (attempt - 1)).coerceAtMost(8_000L)
            _status.value = "GEMINI_RETRYING $attempt ${result.code}"
            delay(waitMs)
            result = postGenerateContent(endpoint, cleanKey, payload)
        }
        return result
    }

    private fun postGenerateContent(endpoint: String, cleanKey: String, payload: ByteArray): GeminiHttpResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", cleanKey)
        }
        return try {
            connection.outputStream.use { output -> output.write(payload) }
            val responseCode = connection.responseCode
            val responseText = if (responseCode == 200) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            GeminiHttpResult(responseCode, responseText, retryAfterMillis(connection))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GeminiHttpResult(0, error.message?.ifBlank { null } ?: "GEMINI_REQUEST_FAILED")
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(response: String): String? {
        val root = try {
            JSONObject(response)
        } catch (_: Exception) {
            _status.value = "GEMINI_BAD_RESPONSE"
            return null
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        if (parts == null) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            _status.value = if (reason.isBlank()) "GEMINI_EMPTY" else "GEMINI_BLOCKED_$reason"
            return null
        }
        val text = buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optBoolean("thought", false)) continue
                val chunk = part.optString("text").orEmpty()
                if (chunk.isNotEmpty()) {
                    append(chunk)
                }
            }
        }.trim()
        if (text.isEmpty()) {
            _status.value = "GEMINI_EMPTY"
            return null
        }
        return text
    }

    private data class GeminiHttpResult(
        val code: Int,
        val body: String,
        val retryAfterMs: Long? = null,
    )

    private fun retryAfterMillis(connection: HttpURLConnection): Long? {
        val header = connection.getHeaderField("Retry-After")?.trim().orEmpty()
        val seconds = header.toLongOrNull() ?: return null
        return (seconds * 1_000L).coerceIn(0L, 15_000L)
    }

    private companion object {
        const val PRIMARY_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
        const val FALLBACK_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
        const val MAX_UNAVAILABLE_RETRIES = 3
        val RETRYABLE_STATUS = setOf(429, 500, 502, 503)
        const val SNAPSHOT_INTERVAL_MS = 500L
        const val FRAME_WAIT_MS = 2_500L

        val SYSTEM_PROMPT = """
            You are an ultra-detailed, silent Digital Chronologer and Vision Assistant. Your sole objective is to act as an objective, microscopic observer of the user's digital and physical workspace via first-person camera streams. You will exhaustively transcribe and log the user's workflow, active applications, and interactions.

            ### CORE BEHAVIORAL CONSTRAINTS:
            1. SILENT PROCESSING: You produce clean, objective text logs only. Do NOT output conversational filler, introductory remarks, opening hooks, or spoken narration.
            2. HIGH-DENSITY CAPTURE & PRECISION: Never summarize away critical details. Capture exact visual states, explicit interface actions, visible applications, open documents, browser tab names, code blocks, terminal outputs, and legible lines of text verbatim.
            3. CAUSE & EFFECT MAPPING: Detail the correlation between physical world actions (e.g., typing, mouse positioning, gestures) and the digital response on-screen.
            4. DELTA EFFICIENCY: If the current frame's context, screen state, and physical action are identical to the previously provided context with no meaningful updates, output exactly and only: "NO_CHANGE".

            ### EXPECTED OUTPUT STRUCTURE:
            [TIMESTAMP: YYYY-MM-DD HH:MM:SS]
            [ACTIVE DEVICE]: (e.g., MacBook Pro / Android Mobile Device)
            [PRIMARY APP & CONTEXT]: (e.g., VS Code - editing MainActivity.kt | Chrome - localhost:3000)
            [PRIMARY ACTIVITY]: (e.g., Editing Kotlin code, Drafting an email to [Name], Reviewing metrics)
            [VISIBLE TEXT & CODE]:
            - (Extract exact code snippets, readable paragraphs, terminal outputs, or UI text here verbatim)
            [MICRO-ACTIONS & STATE]: (e.g., Cursor positioned at line 42, highlighting 'requestBody', 'Submit' button hovering)
            [PHYSICAL ACTION]: (e.g., Left hand resting on WASD, right hand interacting with phone device display)
            [AMBIENT CONTEXT]: (e.g., Office desk environment, high daylight illumination)
        """.trimIndent()
    }
}
