package com.seeandsay.chronologer.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.seeandsay.chronologer.BuildConfig
import com.seeandsay.chronologer.data.AppDatabase
import com.seeandsay.chronologer.data.LogExporter
import com.seeandsay.chronologer.data.NarrationLog
import com.seeandsay.chronologer.engine.AIVisionNarrator
import com.seeandsay.chronologer.engine.CameraSourceMode
import com.seeandsay.chronologer.engine.MetaCameraManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class ChronologerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val database = AppDatabase.getInstance(application)
    private val dao = database.narrationDao()
    private val hostOwner = HostOwner()
    private val camera = MetaCameraManager(application, hostOwner)
    private val narrator = AIVisionNarrator(application, camera)

    private val hostRef = mutableListOf<WeakReference<ComponentActivity>>()
    private val analyzeMutex = Mutex()
    private var pollJob: Job? = null
    private var openedMode: CameraSourceMode? = null
    private var handshakePending = false

    private val _geminiApiKey = MutableStateFlow(
        preferences.getString(KEY_GEMINI, null) ?: BuildConfig.GEMINI_API_KEY,
    )
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _selectedCameraMode = MutableStateFlow(readMode())
    val selectedCameraMode: StateFlow<CameraSourceMode> = _selectedCameraMode.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isAutoPolling = MutableStateFlow(false)
    val isAutoPolling: StateFlow<Boolean> = _isAutoPolling.asStateFlow()

    private val _pollIntervalSeconds = MutableStateFlow(
        preferences.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS).coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
    )
    val pollIntervalSeconds: StateFlow<Int> = _pollIntervalSeconds.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(preferences.getBoolean(KEY_TTS, true))
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _statusBannerText = MutableStateFlow("Camera is idle.")
    val statusBannerText: StateFlow<String> = _statusBannerText.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _notice = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val logs: StateFlow<List<NarrationLog>> = _searchQuery
        .map { it.trim() }
        .debounce(120)
        .flatMapLatest { query ->
            if (query.isEmpty()) dao.getAllLogsSorted() else dao.searchLogs(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        narrator.apiKey = _geminiApiKey.value.trim()
        narrator.ttsEnabled = _isTtsEnabled.value
        viewModelScope.launch {
            combine(
                camera.connectionStatus,
                narrator.status,
                _isProcessing,
                _notice,
            ) { cameraStatus, narratorStatus, processing, notice ->
                composeBanner(cameraStatus, narratorStatus, processing, notice)
            }.collect { banner ->
                _statusBannerText.value = banner
            }
        }
        viewModelScope.launch {
            camera.latestPreviewBitmap.collect { source ->
                _previewBitmap.value = withContext(Dispatchers.Default) { copyPreview(source) }
            }
        }
    }

    fun attach(activity: ComponentActivity) {
        val previous = hostOwner.host
        hostOwner.host = activity
        hostRef.clear()
        hostRef += WeakReference(activity)
        if (previous != null && previous !== activity && openedMode != null && !handshakePending) {
            narrator.openSource(_selectedCameraMode.value, activity, handshake = false)
        }
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val cameraGranted = result[android.Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (!cameraGranted) {
            _notice.value = "Camera permission is required for the live preview."
            return
        }
        _notice.value = ""
        if (!handshakePending) {
            host()?.let(::ensureSource)
        }
    }

    fun setGeminiApiKey(value: String) {
        _geminiApiKey.value = value
        narrator.apiKey = value.trim()
        preferences.edit().putString(KEY_GEMINI, value).apply()
    }

    fun selectCameraMode(mode: CameraSourceMode) {
        handshakePending = false
        _selectedCameraMode.value = mode
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        openedMode = null
        host()?.let(::ensureSource)
    }

    fun setTtsEnabled(enabled: Boolean) {
        _isTtsEnabled.value = enabled
        narrator.ttsEnabled = enabled
        preferences.edit().putBoolean(KEY_TTS, enabled).apply()
    }

    fun setPollInterval(seconds: Int) {
        _pollIntervalSeconds.value = seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        preferences.edit().putInt(KEY_INTERVAL, _pollIntervalSeconds.value).apply()
        if (_isAutoPolling.value) {
            restartPoller()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun captureAndAnalyzeNow() {
        viewModelScope.launch {
            analyzeNow()
        }
    }

    fun toggleAutoPolling() {
        _isAutoPolling.value = !_isAutoPolling.value
        restartPoller()
    }

    fun pairMetaGlasses() {
        val activity = host() ?: return
        handshakePending = true
        _selectedCameraMode.value = CameraSourceMode.META_GLASSES_DAT
        preferences.edit().putString(KEY_MODE, CameraSourceMode.META_GLASSES_DAT.name).apply()
        camera.triggerCompanionHandshake(activity)
    }

    fun completeDatHandshake(activity: ComponentActivity) {
        attach(activity)
        handshakePending = false
        _selectedCameraMode.value = CameraSourceMode.META_GLASSES_DAT
        preferences.edit().putString(KEY_MODE, CameraSourceMode.META_GLASSES_DAT.name).apply()
        _notice.value = "Meta companion returned the registration handshake."
        narrator.openSource(CameraSourceMode.META_GLASSES_DAT, activity, handshake = false)
        openedMode = CameraSourceMode.META_GLASSES_DAT
    }

    fun exportDatabase() {
        val activity = host() ?: return
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { dao.getAllLogsSorted().first() }
            try {
                LogExporter.export(activity, history)
                _notice.value = "Exported ${history.size} logs as JSON."
            } catch (error: Exception) {
                _notice.value = error.message?.ifBlank { null } ?: "Export failed."
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clearAllLogs()
            _notice.value = "Chronology cleared."
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        narrator.release()
        super.onCleared()
    }

    private fun restartPoller() {
        pollJob?.cancel()
        if (!_isAutoPolling.value) {
            return
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                analyzeNow()
                delay(_pollIntervalSeconds.value * 1_000L)
            }
        }
    }

    private suspend fun analyzeNow() {
        if (!analyzeMutex.tryLock()) {
            return
        }
        _isProcessing.value = true
        _notice.value = ""
        try {
            val activity = host()
            if (activity == null) {
                _notice.value = "Dashboard is not attached."
                return
            }
            if (_selectedCameraMode.value == CameraSourceMode.PHONE_CAMERAX && !hasCameraPermission()) {
                _notice.value = "Camera permission is required before narrating."
                return
            }
            if (_selectedCameraMode.value == CameraSourceMode.META_GLASSES_DAT && handshakePending) {
                _notice.value = "Finish the Meta companion handshake to start glasses capture."
                return
            }
            narrator.apiKey = _geminiApiKey.value.trim()
            narrator.ttsEnabled = _isTtsEnabled.value
            ensureSource(activity)
            narrator.narrateCurrentFrame()
        } finally {
            _isProcessing.value = false
            analyzeMutex.unlock()
        }
    }

    private fun ensureSource(activity: ComponentActivity) {
        val mode = _selectedCameraMode.value
        val status = camera.connectionStatus.value
        val live = openedMode == mode && status in LIVE_STATUSES
        if (live || (mode == CameraSourceMode.META_GLASSES_DAT && handshakePending)) {
            return
        }
        narrator.openSource(mode, activity, handshake = mode == CameraSourceMode.META_GLASSES_DAT)
        openedMode = mode
    }

    private fun host(): ComponentActivity? = hostRef.lastOrNull()?.get()

    private fun hasCameraPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun readMode(): CameraSourceMode {
        val stored = preferences.getString(KEY_MODE, CameraSourceMode.PHONE_CAMERAX.name)
        return runCatching { CameraSourceMode.valueOf(stored ?: CameraSourceMode.PHONE_CAMERAX.name) }
            .getOrDefault(CameraSourceMode.PHONE_CAMERAX)
    }

    private class HostOwner : LifecycleOwner {
        @Volatile
        var host: LifecycleOwner? = null

        override val lifecycle: Lifecycle
            get() = checkNotNull(host) { "Chronologer host is not attached" }.lifecycle
    }

    companion object {
        const val MIN_INTERVAL_SECONDS = 4
        const val MAX_INTERVAL_SECONDS = 30
        private const val DEFAULT_INTERVAL_SECONDS = 8
        private const val PREFS = "chronologer_prefs"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_MODE = "camera_mode"
        private const val KEY_TTS = "tts_enabled"
        private const val KEY_INTERVAL = "poll_interval_seconds"

        private val LIVE_STATUSES = setOf(
            "PHONE_CAMERAX_STREAMING",
            "GLASSES_STREAMING",
            "GLASSES_CONNECTING",
            "GLASSES_HANDSHAKE",
            "HINGES_CLOSED",
            "REMOVED_FROM_HEAD",
            "LED_BLOCKED",
            "BLUETOOTH_DISCONNECTED",
            "THERMAL_THROTTLE",
        )

        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(ChronologerViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown model ${modelClass.name}")
                    }
                    return ChronologerViewModel(application) as T
                }
            }
        }
    }
}

private fun copyPreview(source: Bitmap?): Bitmap? {
    if (source == null || source.isRecycled) {
        return null
    }
    return try {
        source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun composeBanner(
    cameraStatus: String,
    narratorStatus: String,
    processing: Boolean,
    notice: String,
): String {
    val parts = mutableListOf<String>()
    if (notice.isNotBlank()) {
        parts += notice
    }
    parts += cameraBanner(cameraStatus)
    narratorBanner(narratorStatus)?.let { parts += it }
    if (processing) {
        parts += "Narrating the current frame."
    }
    return parts.distinct().joinToString("  ·  ")
}

private fun cameraBanner(status: String): String {
    return when (status) {
        "HINGES_CLOSED" -> "Glasses hinges are closed. Capture is paused."
        "REMOVED_FROM_HEAD" -> "Glasses were removed. Capture is paused."
        "LED_BLOCKED" -> "Glasses capture LED is blocked. Capture is paused."
        "BLUETOOTH_DISCONNECTED" -> "Glasses Bluetooth link dropped."
        "THERMAL_THROTTLE" -> "Glasses are thermally throttled. Capture is paused."
        "SDK_UNAVAILABLE" -> "Meta Wearables SDK is not packaged in this build."
        "CAMERA_PERMISSION_REQUIRED" -> "Camera permission is required for the phone preview."
        "ACTIVITY_REQUIRED" -> "The dashboard has to be open before the glasses session starts."
        "OUT_OF_MEMORY" -> "A frame was dropped to stay under the memory limit."
        "GLASSES_HANDSHAKE" -> "Meta companion handshake is in progress."
        "GLASSES_CONNECTING" -> "Connecting to Ray-Ban Meta glasses."
        "GLASSES_STREAMING" -> "Ray-Ban Meta glasses are streaming."
        "PHONE_CAMERAX_STREAMING" -> "Phone camera is live."
        "HANDSHAKE_FAILED" -> "Meta companion handshake failed."
        "CLOSED" -> "Camera session is closed."
        "IDLE" -> "Camera is idle."
        else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
    }
}

private fun narratorBanner(status: String): String? {
    return when (status) {
        "DELTA_SKIP" -> "Delta filter skipped an unchanged frame."
        "NO_CHANGE" -> "Model returned NO_CHANGE. Nothing new was logged."
        "WAITING_FOR_FRAME" -> "Waiting for a camera frame."
        "GEMINI_API_KEY_MISSING" -> "Add a Gemini API key to narrate."
        "LOGGED" -> "Scene logged to the chronology."
        "TTS_UNAVAILABLE" -> "Executive TTS is unavailable on this device."
        "GEMINI_EMPTY" -> "Gemini returned an empty narration."
        "GEMINI_BAD_RESPONSE" -> "Gemini returned a response that could not be read."
        "IDLE", "STOPPED", "TTS_READY", "" -> null
        else -> when {
            status.startsWith("GEMINI_HTTP_") -> "Gemini request failed (HTTP ${status.removePrefix("GEMINI_HTTP_")})."
            status.startsWith("GEMINI_BLOCKED_") -> "Gemini blocked the frame (${status.removePrefix("GEMINI_BLOCKED_")})."
            status.startsWith("GEMINI_") -> "Gemini status: ${status.removePrefix("GEMINI_")}."
            else -> null
        }
    }
}
