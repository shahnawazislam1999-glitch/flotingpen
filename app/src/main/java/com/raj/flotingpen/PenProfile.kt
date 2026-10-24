package com.raj.flotingpen

const val PEN_NIRMAL = 0
const val PEN_CLASSIC = 1
const val PEN_PENCIL = 2

/**
 * Pen feel, ported 1:1 from PEN_PROFILES in the reference HTML (writing pad).
 * Pixel values are dp-like and get multiplied by the screen density where used.
 */
class PenProfile(
    val stab: Float,
    val radiusMul: Float,
    val radiusMin: Float,
    val radiusMax: Float,
    val taperN: Int,
    val taperFloor: Float,
    val pressureBase: Float,
    val pressureMul: Float,
    val speedFloor: Float,
    val speedCeil: Float,
    val widthSmooth: Float,
    val endTaperN: Int,
    val endTaperFloor: Float,
    val baseScale: Float,
    val grain: Boolean,
)

object PenProfiles {
    private val NIRMAL = PenProfile(
        stab = 0.62f, radiusMul = 1.3f, radiusMin = 4f, radiusMax = 12f,
        taperN = 2, taperFloor = 0.55f, pressureBase = 0.62f, pressureMul = 0.65f,
        speedFloor = 0.8f, speedCeil = 1.0f, widthSmooth = 0.4f,
        endTaperN = 5, endTaperFloor = 0.22f, baseScale = 1f, grain = false,
    )
    private val CLASSIC = PenProfile(
        stab = 0.45f, radiusMul = 2.2f, radiusMin = 7f, radiusMax = 26f,
        taperN = 5, taperFloor = 0.35f, pressureBase = 0.45f, pressureMul = 1.0f,
        speedFloor = 0.55f, speedCeil = 1.05f, widthSmooth = 0.28f,
        endTaperN = 0, endTaperFloor = 1f, baseScale = 1f, grain = false,
    )
    private val PENCIL = PenProfile(
        stab = 0.64f, radiusMul = 0.9f, radiusMin = 2.5f, radiusMax = 6.5f,
        taperN = 3, taperFloor = 0.42f, pressureBase = 0.58f, pressureMul = 0.48f,
        speedFloor = 0.78f, speedCeil = 1.0f, widthSmooth = 0.44f,
        endTaperN = 4, endTaperFloor = 0.28f, baseScale = 0.7f, grain = true,
    )

    fun of(type: Int): PenProfile = when (type) {
        PEN_CLASSIC -> CLASSIC
        PEN_PENCIL -> PENCIL
        else -> NIRMAL
    }
}
