package com.seeandsay.chronologer.engine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.seeandsay.chronologer.MetaWearables
import com.seeandsay.chronologer.MetaWearablesResult
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

enum class CameraSourceMode {
    PHONE_CAMERAX,
    META_GLASSES_DAT,
}

/**
 * Phone CameraX for the emulator and any handset, or a reflected Ray-Ban Meta DAT session.
 * Glasses types stay off the compile classpath so a phone build still runs when the SDK is absent.
 */
class MetaCameraManager(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewLock = Any()

    private val _connectionStatus = MutableStateFlow("IDLE")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _latestPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val latestPreviewBitmap: StateFlow<Bitmap?> = _latestPreviewBitmap.asStateFlow()

    private val _sourceMode = MutableStateFlow(CameraSourceMode.PHONE_CAMERAX)
    val sourceMode: StateFlow<CameraSourceMode> = _sourceMode.asStateFlow()

    private val cameraLock = Any()
    private var sessionJob: Job? = null
    private var phoneGeneration = 0
    private var cameraProvider: ProcessCameraProvider? = null
    private var glasses: GlassesLink? = null

    fun start(mode: CameraSourceMode, activity: Activity? = null, handshake: Boolean = true) {
        closeSession()
        _sourceMode.value = mode
        when (mode) {
            CameraSourceMode.PHONE_CAMERAX -> startPhone()
            CameraSourceMode.META_GLASSES_DAT -> {
                if (activity == null) {
                    _connectionStatus.value = "ACTIVITY_REQUIRED"
                } else {
                    initializeGlassesSession(activity, handshake)
                }
            }
        }
    }

    fun initializeGlassesSession(activity: Activity, handshake: Boolean = true) {
        closeSession()
        _sourceMode.value = CameraSourceMode.META_GLASSES_DAT
        _connectionStatus.value = "GLASSES_CONNECTING"
        sessionJob = scope.launch {
            var opened: GlassesLink? = null
            try {
                opened = GlassesLink.open(
                    context = appContext,
                    activity = activity,
                    handshake = handshake,
                    onStatus = { status -> _connectionStatus.value = status },
                    onFrame = ::publishPreview,
                )
                if (opened == null) {
                    if (_connectionStatus.value == "GLASSES_CONNECTING") {
                        _connectionStatus.value = "SDK_UNAVAILABLE"
                    }
                    return@launch
                }
                glasses = opened
                opened.run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _connectionStatus.value = rootMessage(error).ifBlank { "GLASSES_FAILED" }
            } finally {
                if (glasses === opened) {
                    glasses = null
                }
                opened?.close()
            }
        }
    }

    fun triggerCompanionHandshake(activity: Activity) {
        when (val result = MetaWearables.startRegistration(activity)) {
            MetaWearablesResult.Success -> _connectionStatus.value = "GLASSES_HANDSHAKE"
            MetaWearablesResult.Unavailable -> _connectionStatus.value = "SDK_UNAVAILABLE"
            is MetaWearablesResult.Failed -> _connectionStatus.value = result.reason.ifBlank { "HANDSHAKE_FAILED" }
        }
    }

    fun captureSnapshot(): Bitmap? {
        synchronized(previewLock) {
            val current = _latestPreviewBitmap.value ?: return null
            if (current.isRecycled) {
                return null
            }
            return try {
                current.copy(current.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (_: OutOfMemoryError) {
                _connectionStatus.value = "OUT_OF_MEMORY"
                null
            }
        }
    }

    fun closeSession() {
        val job: Job?
        val link: GlassesLink?
        val provider: ProcessCameraProvider?
        synchronized(cameraLock) {
            phoneGeneration += 1
            job = sessionJob
            sessionJob = null
            link = glasses
            glasses = null
            provider = cameraProvider
            cameraProvider = null
        }
        job?.cancel()
        link?.close()
        runCatching { provider?.unbindAll() }
        clearPreview()
        _connectionStatus.value = "CLOSED"
    }

    fun release() {
        closeSession()
        scope.coroutineContext[Job]?.cancel()
        cameraExecutor.shutdown()
    }

    private fun startPhone() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _connectionStatus.value = "CAMERA_PERMISSION_REQUIRED"
            return
        }
        val ticket = phoneGeneration
        sessionJob = scope.launch {
            val provider = try {
                ProcessCameraProvider.getInstance(appContext).await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _connectionStatus.value = rootMessage(error).ifBlank { "CAMERA_UNAVAILABLE" }
                return@launch
            }
            if (ticket != phoneGeneration) {
                return@launch
            }
            val selector = selectCamera(provider)
            if (selector == null) {
                _connectionStatus.value = "CAMERA_UNAVAILABLE"
                return@launch
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(CAPTURE_WIDTH, CAPTURE_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
            analysis.setAnalyzer(cameraExecutor, ::onPhoneFrame)
            val bound = synchronized(cameraLock) {
                if (ticket != phoneGeneration) {
                    false
                } else {
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                        cameraProvider = provider
                        true
                    } catch (error: Throwable) {
                        _connectionStatus.value = rootMessage(error).ifBlank { "CAMERA_BIND_FAILED" }
                        false
                    }
                }
            }
            if (bound && ticket == phoneGeneration) {
                _connectionStatus.value = "PHONE_CAMERAX_STREAMING"
            }
        }
    }

    private fun selectCamera(provider: ProcessCameraProvider): CameraSelector? {
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        return when {
            runCatching { provider.hasCamera(back) }.getOrDefault(false) -> back
            runCatching { provider.hasCamera(front) }.getOrDefault(false) -> front
            else -> null
        }
    }

    private var lastPhoneFrameAt = 0L

    private fun onPhoneFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPhoneFrameAt < SNAPSHOT_INTERVAL_MS) {
                return
            }
            val bitmap = image.decodeRgbaFrame() ?: return
            lastPhoneFrameAt = now
            publishPreview(bitmap)
        } catch (_: OutOfMemoryError) {
            _connectionStatus.value = "OUT_OF_MEMORY"
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.decodeRgbaFrame(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val width = width
        val height = height
        if (width <= 0 || height <= 0) {
            return null
        }
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowPadding = plane.rowStride - pixelStride * width
        val bitmapWidth = width + (rowPadding / pixelStride)
        return try {
            val raw = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            raw.copyPixelsFromBuffer(plane.buffer)
            val tight = if (bitmapWidth == width) {
                raw
            } else {
                Bitmap.createBitmap(raw, 0, 0, width, height).also { cropped ->
                    if (cropped !== raw) {
                        raw.recycle()
                    }
                }
            }
            rotate(tight, imageInfo.rotationDegrees)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) {
            return source
        }
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    private fun publishPreview(bitmap: Bitmap) {
        synchronized(previewLock) {
            val previous = _latestPreviewBitmap.value
            _latestPreviewBitmap.value = bitmap
            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                previous.recycle()
            }
        }
    }

    private fun clearPreview() {
        synchronized(previewLock) {
            val previous = _latestPreviewBitmap.value
            _latestPreviewBitmap.value = null
            if (previous != null && !previous.isRecycled) {
                previous.recycle()
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener(
            {
                if (!continuation.isActive) {
                    return@addListener
                }
                try {
                    continuation.resume(get())
                } catch (error: Throwable) {
                    continuation.resumeWithException(error.cause ?: error)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

    private class GlassesLink private constructor(
        private val session: Any,
        private val camera: Any,
        private val photo: Any,
        private val photoResolution: Any,
        private val photoQuality: Any,
        private val onStatus: (String) -> Unit,
        private val onFrame: (Bitmap) -> Unit,
    ) {
        private val closed = AtomicBoolean(false)
        private val captureInFlight = AtomicBoolean(false)
        private val interrupt = AtomicReference<String?>(null)
        private var captureRequestedAt = 0L

        suspend fun run() {
            onStatus("GLASSES_STREAMING")
            coroutineScope {
                launch(Dispatchers.Default) { collectFlow(call(session, "getDeviceInfo"), ::onDevice) }
                launch(Dispatchers.Default) { collectFlow(call(session, "getErrors"), ::onError) }
                launch(Dispatchers.Default) { collectFlow(call(photo, "getErrors"), ::onError) }
                launch(Dispatchers.Default) { collectFlow(call(photo, "getPhotoStream"), ::onPhoto) }
                launch(Dispatchers.Default) { snapshotLoop() }
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            runCatching { call(photo, "stop") }
            runCatching { call(camera, "stop") }
            runCatching { call(camera, "close") }
            runCatching {
                val extensions = Class.forName(EXTENSIONS)
                val remove = extensions.methods.first { it.name == "removeCamera" && it.parameterCount == 1 }
                remove.invoke(null, session)
            }
            runCatching { call(session, "stop") }
        }

        private suspend fun snapshotLoop() {
            while (!closed.get()) {
                val blocked = interrupt.get()
                val state = enumName(stateValue(call(photo, "getState")))
                val now = SystemClock.elapsedRealtime()
                val busy = captureInFlight.get() && now - captureRequestedAt < CAPTURE_TIMEOUT_MS
                if (blocked == null && !busy && (state == null || state == "STARTED")) {
                    triggerSnapshot()
                }
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        private fun triggerSnapshot() {
            captureInFlight.set(true)
            captureRequestedAt = SystemClock.elapsedRealtime()
            try {
                photo.javaClass.methods
                    .first { it.name == "capturePhoto" && it.parameterCount == 2 }
                    .invoke(photo, photoResolution, photoQuality)
            } catch (error: Throwable) {
                captureInFlight.set(false)
                val code = interruptCode(rootCause(error))
                if (code != null) {
                    interrupt.set(code)
                    onStatus(code)
                }
            }
        }

        private suspend fun collectFlow(flowObject: Any?, consumer: (Any) -> Unit) {
            val flow = flowObject as? Flow<*> ?: return
            flow.collect { value ->
                if (value != null && !closed.get()) {
                    consumer(value)
                }
            }
        }

        private fun onDevice(device: Any) {
            val code = deviceInterrupt(device)
            interrupt.set(code)
            onStatus(code ?: "GLASSES_STREAMING")
        }

        private fun onError(error: Any) {
            val code = interruptCode(error) ?: enumName(error) ?: return
            if (isPauseCode(code)) {
                interrupt.set(code)
            }
            onStatus(code)
            captureInFlight.set(false)
        }

        private fun onPhoto(data: Any) {
            captureInFlight.set(false)
            if (interrupt.get() != null || closed.get()) {
                return
            }
            val bytes = call(data, "getImageData") as? ByteArray ?: return
            val bitmap = decodeBounded(bytes) ?: return
            onFrame(bitmap)
        }

        companion object {
            suspend fun open(
                context: Context,
                activity: Activity,
                handshake: Boolean,
                onStatus: (String) -> Unit,
                onFrame: (Bitmap) -> Unit,
            ): GlassesLink? {
                val wearablesClass = loadClass(WEARABLES) ?: return null
                val wearables = wearablesInstance(wearablesClass) ?: return null
                val initialized = invoke(wearables, "initialize", context.applicationContext)
                if (!datSuccess(initialized, allowAlreadyInitialized = true)) {
                    onStatus(datFailureStatus(initialized) ?: "SDK_UNAVAILABLE")
                    return null
                }
                if (handshake) {
                    when (val registration = MetaWearables.startRegistration(activity)) {
                        MetaWearablesResult.Unavailable -> {
                            onStatus("SDK_UNAVAILABLE")
                            return null
                        }
                        is MetaWearablesResult.Failed -> {
                            onStatus(registration.reason.ifBlank { "HANDSHAKE_FAILED" })
                            return null
                        }
                        MetaWearablesResult.Success -> onStatus("GLASSES_HANDSHAKE")
                    }
                }
                val selector = loadClass(AUTO_SELECTOR)?.getDeclaredConstructor()?.newInstance() ?: return null
                val created = invoke(wearables, "createSession", selector)
                val session = datValue(created)
                if (session == null) {
                    onStatus(datFailureStatus(created) ?: "SESSION_FAILED")
                    return null
                }
                call(session, "start")
                val configuration = highRateConfiguration()
                val extensions = loadClass(EXTENSIONS)
                val addCamera = extensions?.methods?.firstOrNull { it.name == "addCamera" && it.parameterCount == 2 }
                val cameraResult = addCamera?.invoke(null, session, configuration)
                val camera = cameraResult?.let(::datValue)
                if (camera == null) {
                    call(session, "stop")
                    onStatus(cameraResult?.let(::datFailureStatus) ?: "CAMERA_UNAVAILABLE")
                    return null
                }
                val photo = call(camera, "getPhoto")
                val resolution = enumConstant(PHOTO_RESOLUTION, "MEDIUM")
                val quality = enumConstant(PHOTO_QUALITY, "HIGH")
                if (photo == null || resolution == null || quality == null) {
                    call(camera, "stop")
                    call(session, "stop")
                    onStatus("CAMERA_UNAVAILABLE")
                    return null
                }
                return GlassesLink(
                    session = session,
                    camera = camera,
                    photo = photo,
                    photoResolution = resolution,
                    photoQuality = quality,
                    onStatus = onStatus,
                    onFrame = onFrame,
                )
            }

            private fun highRateConfiguration(): Any? {
                val type = loadClass(STREAM_CONFIGURATION) ?: return null
                val defaults = type.getDeclaredConstructor().newInstance()
                val high = enumConstant(VIDEO_QUALITY, "HIGH") ?: return defaults
                val copy = type.methods.firstOrNull { it.name == "copy\$default" && it.parameterCount == 7 }
                    ?: return defaults
                return copy.invoke(null, defaults, null, high, SNAPSHOT_FPS, false, COPY_VIDEO_AND_RATE_MASK, null)
                    ?: defaults
            }

            private fun decodeBounded(bytes: ByteArray): Bitmap? {
                if (bytes.isEmpty()) {
                    return null
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, CAPTURE_HEIGHT)
                }
                return try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                } catch (_: OutOfMemoryError) {
                    null
                }
            }

            private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
                if (width <= 0 || height <= 0) {
                    return 1
                }
                var size = 1
                var longest = maxOf(width, height)
                while (longest / (size * 2) >= maxEdge && size < 16) {
                    size *= 2
                }
                return size
            }

            private fun deviceInterrupt(device: Any): String? {
                val hinge = enumName(call(device, "getHingeState"))
                val don = enumName(call(device, "getDonState"))
                val link = enumName(call(device, "getLinkState"))
                val described = "${call(device, "toString") ?: ""}"
                return when {
                    hinge == "CLOSED" || described.contains("HINGES_CLOSED") -> HINGES_CLOSED
                    don == "DOFFED" || described.contains("REMOVED_FROM_HEAD") -> REMOVED_FROM_HEAD
                    described.contains("LED_BLOCKED") -> LED_BLOCKED
                    link == "DISCONNECTED" || described.contains("BLUETOOTH_DISCONNECTED") -> BLUETOOTH_DISCONNECTED
                    else -> null
                }
            }

            private fun interruptCode(error: Any): String? {
                val name = enumName(error) ?: error.javaClass.simpleName
                val description = call(error, "getDescription") as? String ?: ""
                val blob = "$name $description"
                return when {
                    name == "HINGE_CLOSED" || blob.contains("HINGES_CLOSED") -> HINGES_CLOSED
                    name == "LED_BLOCKED" || blob.contains("LED_BLOCKED") -> LED_BLOCKED
                    blob.contains("REMOVED_FROM_HEAD") || name.contains("DOFF") -> REMOVED_FROM_HEAD
                    name == "DEVICE_DISCONNECTED" || blob.contains("BLUETOOTH_DISCONNECTED") || blob.contains("BLUETOOTH") ->
                        BLUETOOTH_DISCONNECTED
                    name == "THERMAL_HOT" || name == "THERMAL_CRITICAL" || name == "THERMAL_EMERGENCY" ||
                        name == "PEAK_POWER_LIMIT" || name == "PEAK_POWER_SHUTDOWN" -> THERMAL_THROTTLE
                    else -> null
                }
            }

            private fun isPauseCode(code: String): Boolean {
                return code == HINGES_CLOSED ||
                    code == REMOVED_FROM_HEAD ||
                    code == LED_BLOCKED ||
                    code == BLUETOOTH_DISCONNECTED ||
                    code == THERMAL_THROTTLE
            }

            private fun datSuccess(result: Any?, allowAlreadyInitialized: Boolean): Boolean {
                if (result == null) {
                    return true
                }
                val failure = call(result, "isFailure") as? Boolean ?: return true
                if (!failure) {
                    return true
                }
                val name = enumName(call(result, "errorOrNull"))
                return allowAlreadyInitialized && name == "ALREADY_INITIALIZED"
            }

            private fun datValue(result: Any?): Any? {
                if (result == null) {
                    return null
                }
                return call(result, "getOrNull") ?: result.takeUnless { call(it, "isFailure") == true }
            }

            private fun datFailureStatus(result: Any?): String? {
                val error = result?.let { call(it, "errorOrNull") } ?: return null
                return interruptCode(error) ?: enumName(error) ?: call(error, "getDescription") as? String
            }

            private fun wearablesInstance(type: Class<*>): Any? {
                return try {
                    type.getField("INSTANCE").get(null)
                } catch (_: ReflectiveOperationException) {
                    null
                }
            }

            private fun invoke(target: Any, methodName: String, argument: Any): Any? {
                val method = target.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == methodName &&
                        candidate.parameterCount == 1 &&
                        candidate.parameterTypes[0].isAssignableFrom(argument.javaClass)
                } ?: return null
                return try {
                    method.invoke(target, argument)
                } catch (error: InvocationTargetException) {
                    throw error.targetException ?: error
                }
            }

            private fun call(target: Any?, methodName: String): Any? {
                if (target == null) {
                    return null
                }
                val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?: return null
                return try {
                    method.invoke(target)
                } catch (error: InvocationTargetException) {
                    throw error.targetException ?: error
                }
            }

            private fun stateValue(flow: Any?): Any? {
                if (flow == null) {
                    return null
                }
                val method = flow.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                return method?.invoke(flow)
            }

            private fun enumName(value: Any?): String? {
                if (value == null) {
                    return null
                }
                val method = value.javaClass.methods.firstOrNull { it.name == "name" && it.parameterCount == 0 }
                return method?.invoke(value) as? String
            }

            private fun enumConstant(className: String, constant: String): Any? {
                val type = loadClass(className) ?: return null
                return try {
                    type.getField(constant).get(null)
                } catch (_: ReflectiveOperationException) {
                    null
                }
            }

            private fun loadClass(name: String): Class<*>? {
                return try {
                    Class.forName(name)
                } catch (_: ClassNotFoundException) {
                    null
                } catch (_: LinkageError) {
                    null
                }
            }

            private fun rootCause(error: Throwable): Throwable {
                return (error as? InvocationTargetException)?.targetException ?: error.cause ?: error
            }
        }
    }

    private companion object {
        const val CAPTURE_WIDTH = 720
        const val CAPTURE_HEIGHT = 1280
        const val SNAPSHOT_FPS = 2
        const val SNAPSHOT_INTERVAL_MS = 500L
        const val CAPTURE_TIMEOUT_MS = 2_000L
        const val COPY_VIDEO_AND_RATE_MASK = 9
        const val HINGES_CLOSED = "HINGES_CLOSED"
        const val REMOVED_FROM_HEAD = "REMOVED_FROM_HEAD"
        const val LED_BLOCKED = "LED_BLOCKED"
        const val BLUETOOTH_DISCONNECTED = "BLUETOOTH_DISCONNECTED"
        const val THERMAL_THROTTLE = "THERMAL_THROTTLE"
        const val WEARABLES = "com.meta.wearable.dat.core.Wearables"
        const val AUTO_SELECTOR = "com.meta.wearable.dat.core.selectors.AutoDeviceSelector"
        const val EXTENSIONS = "com.meta.wearable.dat.camera.SessionCameraExtensionsKt"
        const val STREAM_CONFIGURATION = "com.meta.wearable.dat.camera.types.StreamConfiguration"
        const val VIDEO_QUALITY = "com.meta.wearable.dat.camera.types.VideoQuality"
        const val PHOTO_RESOLUTION = "com.meta.wearable.dat.camera.photo.types.PhotoResolution"
        const val PHOTO_QUALITY = "com.meta.wearable.dat.camera.photo.types.PhotoQuality"
    }
}

private fun rootMessage(error: Throwable): String {
    val cause = (error as? InvocationTargetException)?.targetException ?: error
    return cause.message?.trim().orEmpty().ifEmpty { cause.javaClass.simpleName }
}
