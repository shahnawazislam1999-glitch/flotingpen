package com.raj.flotingpen

/** Per-pen smoothing profile, ported 1:1 from the HTML reference (PEN_PROFILES). Sizes are in dp. */
class PenProfile(
    val stab: Float, val rMul: Float, val rMin: Float, val rMax: Float,
    val taperN: Int, val taperFloor: Float,
    val pBase: Float, val pMul: Float,
    val spFloor: Float, val spCeil: Float,
    val wSmooth: Float,
    val endN: Int, val endFloor: Float,
    val baseScale: Float, val grain: Boolean,
)
