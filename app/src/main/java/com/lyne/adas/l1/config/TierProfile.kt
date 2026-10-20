package com.lyne.adas.l1.config

/** Preferred inference backend order; the engine still validates and falls back. */
enum class BackendPreference { NNAPI_THEN_GPU, GPU_THEN_NNAPI, XNNPACK_ONLY }

/** Bitmap config used for the downscaled detection frame. RGB565 halves memory bandwidth. */
enum class PixelFormat { ARGB_8888, RGB_565 }

/**
 * All per-tier tuning lives here as data so behaviour is declarative and runtime-tunable,
 * never scattered through the pipeline. Cadence values are "run every Nth frame"; 0 = disabled.
 */
data class TierProfile(
    val tier: DeviceTier,
    /** Square detection-input edge in px, decoupled from preview resolution. */
    val inputResolution: Int,
    val targetFps: Int,
    val objectCadence: Int,
    val laneCadence: Int,
    val signCadence: Int,
    val backendPreference: BackendPreference,
    val inferenceThreads: Int,
    val pixelFormat: PixelFormat,
    /** Use the smaller "nano" model variants bundled for weak devices. */
    val useNanoModels: Boolean,
    /** Run the real drivable-area seg model (A/B). Tier C uses the lighter classical segmenter. */
    val useSegModel: Boolean,
    val allowDashcam: Boolean,
    val features: FeatureFlags,
    /** Max in-flight frames before the analyzer drops (counts as a dropped frame). */
    val inferenceQueueDepth: Int = 1,
) {
    companion object {
        /**
         * Dashcam profile for a Galaxy A30s (Exynos 7904: no NPU, weak NNAPI) running continuous
         * 720p loop recording next to the detector. Used for EVERY tier so the result does not
         * depend on what the capability probe decides.
         *
         *  - XNNPACK on CPU only, 4 threads (no NNAPI / GPU attempts).
         *  - Detector input 256 with the bundled nano YOLOv8n (the known-good light model).
         *    To try 320 you need a 320-input .tflite exported and bundled first; then change
         *    [inputResolution] here and make sure DetectorProvider loads that file.
         *  - Neural segmentation, lane detection and traffic-sign recognition are OFF.
         *    FCW / pedestrians+two-wheelers / headway stay ON (they only need object detection).
         */
        private val DASHCAM = TierProfile(
            tier = DeviceTier.B,
            inputResolution = 256,
            targetFps = 15,
            objectCadence = 1,
            laneCadence = 0,        // lane / drivable-area segmentation disabled
            signCadence = 0,        // traffic-sign recognition disabled
            backendPreference = BackendPreference.XNNPACK_ONLY,
            inferenceThreads = 4,
            pixelFormat = PixelFormat.ARGB_8888,
            useNanoModels = true,
            useSegModel = false,
            allowDashcam = true,
            features = FeatureFlags(
                fcw = true,
                vru = true,
                ldw = false,
                headway = true,
                tsr = false,
            ),
        )

        val TIER_A = DASHCAM.copy(tier = DeviceTier.A)
        val TIER_B = DASHCAM.copy(tier = DeviceTier.B)
        val TIER_C = DASHCAM.copy(tier = DeviceTier.C)

        fun forTier(tier: DeviceTier): TierProfile = when (tier) {
            DeviceTier.A -> TIER_A
            DeviceTier.B -> TIER_B
            DeviceTier.C -> TIER_C
        }
    }
}
