package com.lyne.adas.l1.fusion

import kotlin.math.abs

/**
 * Fires once when the vehicle directly ahead starts pulling away while we are stationary.
 *
 * Allocation-free (keeps Lyne's "zero per-frame allocation" invariant):
 *   departure.beginFrame()
 *   for each detected VEHICLE (car/motorcycle/bus/truck): departure.offer(cx, w, h)
 *   if (departure.endFrame(nowMs, egoSpeedKmh)) -> play the alert
 *
 * All box values are normalized [0,1] in display space (same convention as the detectors):
 *   cx = box center x, w = box width, h = box height.
 *
 * Logic:
 *   1. We must be stopped (speed <= egoStopKmh) for egoStopHoldMs. Unknown speed (no GPS fix) = disarmed.
 *   2. Lead vehicle = largest box whose center lies inside the corridor in front of us.
 *   3. IDLE -> ARMED once the lead's (smoothed) box height has been steady for armStableMs.
 *   4. ARMED -> fires when the box height shrinks by departShrink vs the armed reference
 *      for departHoldMs continuously (box shrinking = the car is getting farther away).
 *   5. If the box grows (closer vehicle / we crept forward) it re-arms from scratch.
 *
 * The numbers in [Config] are starting values - tune them on real traffic.
 */
class LeadDepartureMonitor(private val cfg: Config = Config()) {

    data class Config(
        val egoStopKmh: Float = 2f,
        val egoStopHoldMs: Long = 1500L,
        val corridorCenterX: Float = 0.5f,   // move if the phone is not centered on the windshield
        val corridorHalfWidth: Float = 0.18f,
        val minBoxHeight: Float = 0.06f,     // ignore tiny / far boxes
        val armStableMs: Long = 1500L,
        val stationaryTol: Float = 0.04f,    // relative height change still counted as "not moving"
        val departShrink: Float = 0.10f,     // 10% shrink = departure candidate
        val departHoldMs: Long = 400L,
        val closerGrow: Float = 0.15f,       // 15% growth = something closer, re-arm
        val maxGapMs: Long = 700L,           // lead lost longer than this -> reset
        val cooldownMs: Long = 8000L,
    )

    private enum class State { IDLE, ARMED, COOLDOWN }

    private var state = State.IDLE
    private var egoStoppedSince = -1L
    private var refH = 0f
    private var smoothH = 0f
    private var stableSince = -1L
    private var lastSeen = -1L
    private var departingSince = -1L
    private var cooldownUntil = 0L

    // per-frame candidate
    private var candFound = false
    private var candArea = 0f
    private var candH = 0f

    val isArmed: Boolean get() = state == State.ARMED

    fun reset() = resetAll()

    fun beginFrame() {
        candFound = false
        candArea = 0f
        candH = 0f
    }

    fun offer(cx: Float, w: Float, h: Float) {
        if (abs(cx - cfg.corridorCenterX) > cfg.corridorHalfWidth) return
        if (h < cfg.minBoxHeight) return
        val area = w * h
        if (area > candArea) {
            candFound = true
            candArea = area
            candH = h
        }
    }

    /** @return true exactly on the frame the "front car moved" alert should fire. */
    fun endFrame(nowMs: Long, egoSpeedKmh: Float?): Boolean {
        val stopped = egoSpeedKmh != null && egoSpeedKmh <= cfg.egoStopKmh
        if (!stopped) {
            resetAll()
            return false
        }
        if (egoStoppedSince < 0L) egoStoppedSince = nowMs

        if (state == State.COOLDOWN) {
            if (nowMs < cooldownUntil) return false
            resetLead()
        }

        if (!candFound) {
            if (lastSeen >= 0L && nowMs - lastSeen > cfg.maxGapMs) resetLead()
            return false
        }
        lastSeen = nowMs
        smoothH = if (smoothH <= 0f) candH else smoothH + 0.5f * (candH - smoothH)

        when (state) {
            State.IDLE -> {
                if (refH <= 0f || abs(smoothH - refH) / refH > cfg.stationaryTol) {
                    refH = smoothH
                    stableSince = nowMs
                } else {
                    refH += 0.1f * (smoothH - refH)
                }
                if (nowMs - stableSince >= cfg.armStableMs &&
                    nowMs - egoStoppedSince >= cfg.egoStopHoldMs
                ) {
                    state = State.ARMED
                    departingSince = -1L
                }
            }

            State.ARMED -> {
                if (smoothH > refH * (1f + cfg.closerGrow)) {
                    // closer vehicle appeared or we crept forward: start over
                    state = State.IDLE
                    refH = smoothH
                    stableSince = nowMs
                    departingSince = -1L
                } else if (smoothH <= refH * (1f - cfg.departShrink)) {
                    if (departingSince < 0L) departingSince = nowMs
                    if (nowMs - departingSince >= cfg.departHoldMs) {
                        state = State.COOLDOWN
                        cooldownUntil = nowMs + cfg.cooldownMs
                        return true
                    }
                } else {
                    departingSince = -1L
                }
            }

            State.COOLDOWN -> Unit
        }
        return false
    }

    private fun resetLead() {
        state = State.IDLE
        refH = 0f
        smoothH = 0f
        stableSince = -1L
        lastSeen = -1L
        departingSince = -1L
    }

    private fun resetAll() {
        resetLead()
        egoStoppedSince = -1L
        cooldownUntil = 0L
    }
}
