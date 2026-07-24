package com.pombo.android.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * Faithful port of the Pombo web AvatarGenerator.js — generates the SAME deterministic SVG
 * from an Ethereum address (organic blobs + pareidolia face). All
 * arithmetic is 32-bit (wrapping Int) to match the JS. Rendered with
 * Coil-SVG.
 */
object PomboAvatar {

    // ---- PRNG / hash (32-bit, same as JS) ----

    private fun hashString(str: String): Int {
        var hash = 0
        for (c in str) {
            hash = ((hash shl 5) - hash) + c.code
            // (hash & hash) in JS = 32-bit coercion; in Kotlin it is already Int
        }
        return abs(hash)
    }

    private class Rng(seed: Int) {
        private var state = seed
        fun next(): Double {
            state += 0x6D2B79F5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
            val u = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
            return u.toDouble() / 4294967296.0
        }
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))

    // ---- HSL colors ----

    private fun hslToHex(h: Double, s0: Double, l0: Double): String {
        val s = s0 / 100.0
        val l = l0 / 100.0
        val a = s * min(l, 1 - l)
        fun f(n: Int): String {
            val k = (n + h / 30.0) % 12.0
            val color = l - a * max(min(min(k - 3, 9 - k), 1.0), -1.0)
            val v = round(255 * color).toInt().coerceIn(0, 255)
            return v.toString(16).padStart(2, '0')
        }
        return "#${f(0)}${f(8)}${f(4)}"
    }

    private data class Col(val hex: String, val hue: Double)

    private fun colorFromSpectrum(
        rng: Rng, hueMin: Double, hueMax: Double,
        satMin: Double, satMax: Double, lightMin: Double, lightMax: Double
    ): Col {
        val hue = if (hueMin <= hueMax) hueMin + rng.next() * (hueMax - hueMin) else {
            val range = (360 - hueMin) + hueMax
            val h = rng.next() * range
            if (h < (360 - hueMin)) hueMin + h else h - (360 - hueMin)
        }
        val sat = satMin + rng.next() * (satMax - satMin)
        val light = lightMin + rng.next() * (lightMax - lightMin)
        return Col(hslToHex(hue, sat, light), hue)
    }

    // COLOR_HARMONY: analogous, variation 50
    private fun harmonicColor(
        rng: Rng, baseHue: Double, satMin: Double, satMax: Double, lightMin: Double, lightMax: Double
    ): String {
        val v = (rng.next() - 0.5) * 2 * 50.0
        val hueOffset = (if (rng.next() > 0.5) 40.0 else -40.0) + v
        val hue = (baseHue + hueOffset + 360) % 360
        val sat = satMin + rng.next() * (satMax - satMin)
        val light = lightMin + rng.next() * (lightMax - lightMin)
        return hslToHex(hue, sat, light)
    }

    // ---- geometry (blobs) ----

    private data class P(val x: Double, val y: Double)

    private fun fmt(d: Double): String {
        // JS toFixed(2). Locale.ROOT is mandatory: the default locale formats
        // decimals with a comma in pt/es/fr/de, which produces invalid SVG path
        // data ("12,34" instead of "12.34") and renders as garbage.
        val r = (round(d * 100) / 100)
        return String.format(java.util.Locale.ROOT, "%.2f", r)
    }

    private fun buildClosedPath(points: List<P>, smoothness: Double): String {
        val n = points.size
        val sb = StringBuilder("M${fmt(points[0].x)},${fmt(points[0].y)}")
        for (i in 0 until n) {
            val p0 = points[((i - 1 + n) % n)]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val p3 = points[(i + 2) % n]
            val tension = 1 - smoothness * 0.5
            val t = tension / 6
            val cp1x = p1.x + (p2.x - p0.x) * t
            val cp1y = p1.y + (p2.y - p0.y) * t
            val cp2x = p2.x - (p3.x - p1.x) * t
            val cp2y = p2.y - (p3.y - p1.y) * t
            sb.append(" C${fmt(cp1x)},${fmt(cp1y)} ${fmt(cp2x)},${fmt(cp2y)} ${fmt(p2.x)},${fmt(p2.y)}")
        }
        return sb.toString()
    }

    private fun blobPath(
        rng: Rng, cx: Double, cy: Double, radius: Double,
        amp1: Double, amp2: Double, smoothness: Double, sampleCount: Int
    ): String {
        val freq1 = 3 + floor(rng.next() * 3)
        val freq2 = 5 + floor(rng.next() * 3)
        val phase1 = rng.next() * Math.PI * 2
        val phase2 = rng.next() * Math.PI * 2
        val rotation = rng.next() * Math.PI * 2
        val points = ArrayList<P>(sampleCount)
        for (i in 0 until sampleCount) {
            val theta = rotation + (i.toDouble() / sampleCount) * Math.PI * 2
            val wave = 1 + amp1 * sin(freq1 * theta + phase1) + amp2 * sin(freq2 * theta + phase2)
            val cr = radius * wave
            points.add(P(cx + cos(theta) * cr, cy + sin(theta) * cr))
        }
        return buildClosedPath(points, smoothness)
    }

    private fun generateShape(rng: Rng, cx: Double, cy: Double, size: Double): String {
        val margin = size * 0.08
        val usableRadius = (size / 2) - margin
        val scaleA = clamp(0.90 + rng.next() * 0.50, 0.45, 1.25)
        val scaleB = clamp(0.20 + rng.next() * 0.29, 0.35, 1.1)
        val irregA = 0.50 + rng.next() * 0.50
        val irregB = 0.50 + rng.next() * 0.50
        val spikeA = 0.50 + rng.next() * 0.30
        val spikeB = 0.50 + rng.next() * 0.50
        val smoothness = clamp(0.30 + 0.45, 0.45, 0.95)
        val overlap = 0.50 + rng.next() * 0.50
        val radiusA = usableRadius * scaleA * 0.82
        val radiusB = usableRadius * scaleB * 0.72
        val offsetDistance = usableRadius * clamp(0.46 - overlap * 0.28, 0.06, 0.24)
        val offsetAngle = rng.next() * Math.PI * 2
        val cxB = cx + cos(offsetAngle) * offsetDistance
        val cyB = cy + sin(offsetAngle) * offsetDistance
        val pathA = blobPath(
            rng, cx, cy, radiusA,
            clamp(0.06 + irregA * 0.45, 0.05, 0.24),
            clamp(0.04 + spikeA * 0.22, 0.03, 0.16),
            smoothness, 20
        )
        val pathB = blobPath(
            rng, cxB, cyB, radiusB,
            clamp(0.06 + irregB * 0.42, 0.05, 0.22),
            clamp(0.04 + spikeB * 0.20, 0.03, 0.14),
            smoothness, 18
        )
        return "$pathA $pathB"
    }

    // ---- face (pareidolia) ----

    private val EYE_TYPES = listOf("dot", "round", "oval", "sleepy", "blink")
    private val MOUTH_TYPES = listOf("smile", "grin", "grinDark", "smirk", "neutral", "line")
    private val EXPRESSIONS = listOf("normal", "tilt", "happy", "curious")

    private fun n(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun generateEye(
        type: String, x: Double, y: Double, radius: Double, color: String, opacity: Double,
        radiusScale: Double, tilt: Double, archHeight: Double, archWidth: Double, pupilScale: Double
    ): String {
        val r = radius * radiusScale
        val leftX = x - r * archWidth
        val rightX = x + r * archWidth
        val leftY = y - r * 0.18 * tilt
        val rightY = y + r * 0.18 * tilt
        return when (type) {
            "dot" -> "<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"${n(r)}\" fill=\"$color\" opacity=\"${n(opacity)}\"/>"
            "round" -> "<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"${n(r)}\" fill=\"$color\" opacity=\"${n(opacity)}\"/>" +
                "<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"${n(r * pupilScale)}\" fill=\"${if (color == "#1a1a24") "#f0f0f5" else "#1a1a24"}\" opacity=\"${n(opacity)}\"/>"
            "oval" -> "<ellipse cx=\"${n(x)}\" cy=\"${n(y)}\" rx=\"${n(r * 0.7)}\" ry=\"${n(r)}\" fill=\"$color\" opacity=\"${n(opacity)}\"/>"
            "sleepy" -> "<line x1=\"${n(leftX)}\" y1=\"${n(leftY)}\" x2=\"${n(rightX)}\" y2=\"${n(rightY)}\" stroke=\"$color\" stroke-width=\"${n(r * 0.8)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            "blink" -> "<path d=\"M${n(leftX)} ${n(leftY)} Q${n(x)} ${n(y - r * archHeight)} ${n(rightX)} ${n(rightY)}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${n(r * 0.6)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            else -> "<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"${n(r)}\" fill=\"$color\" opacity=\"${n(opacity)}\"/>"
        }
    }

    private fun generateMouth(
        type: String, x: Double, y: Double, width: Double, height: Double, color: String, opacity: Double,
        widthScale: Double, tilt: Double, curveDepth: Double, openScale: Double, topLift: Double
    ): String {
        val w = width; val h = height
        val leftY = y - h * 0.25 * tilt
        val rightY = y + h * 0.25 * tilt
        return when (type) {
            "smile" -> "<path d=\"M${n(x - w * widthScale)} ${n(leftY)} Q${n(x)} ${n(y + h * (1.8 + curveDepth))} ${n(x + w * widthScale)} ${n(rightY)}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${n(h * 0.7)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            "grin" -> "<path d=\"M${n(x - w * 0.95 * widthScale)} ${n(y - h * (0.08 + topLift * 0.2) - h * 0.15 * tilt)} Q${n(x)} ${n(y + h * (1.6 + curveDepth * 0.5))} ${n(x + w * 0.95 * widthScale)} ${n(y - h * (0.08 + topLift * 0.2) + h * 0.15 * tilt)}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${n(h * 0.65)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            "grinDark" -> {
                val gdw = w * (0.9 + widthScale * 0.35)
                val gdh = h * (1.5 + openScale * 1.0)
                val leftTopY = y - gdh * (0.18 + topLift * 0.25) - h * 0.18 * tilt
                val leftBottomY = y + gdh * (0.45 + openScale * 0.12) - h * 0.10 * tilt
                val rightTopY = y - gdh * (0.10 + topLift * 0.12) + h * 0.12 * tilt
                val rightBottomY = y + gdh * (0.38 + openScale * 0.10) + h * 0.14 * tilt
                val bellyY = y + gdh * (0.95 + curveDepth * 0.18)
                "<path d=\"M${n(x - gdw)} ${n(leftTopY)} L${n(x - gdw)} ${n(leftBottomY)} Q${n(x)} ${n(bellyY)} ${n(x + gdw)} ${n(rightBottomY)} L${n(x + gdw)} ${n(rightTopY)} Z\" fill=\"$color\" opacity=\"${n(opacity * 0.9)}\"/>"
            }
            "smirk" -> "<path d=\"M${n(x - w * 0.8 * widthScale)} ${n(y + h * (0.30 - tilt * 0.25))} Q${n(x)} ${n(y + h * (1.10 + curveDepth * 0.55))} ${n(x + w * 0.8 * widthScale)} ${n(y - h * (0.30 + tilt * 0.45))}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${n(h * 0.6)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            "neutral" -> "<line x1=\"${n(x - w * 0.8 * widthScale)}\" y1=\"${n(leftY)}\" x2=\"${n(x + w * 0.8 * widthScale)}\" y2=\"${n(rightY)}\" stroke=\"$color\" stroke-width=\"${n(h * 0.7)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            "line" -> "<path d=\"M${n(x - w * 0.6 * widthScale)} ${n(leftY - h * 0.2)} Q${n(x)} ${n(y + h * (0.7 + curveDepth * 0.5))} ${n(x + w * 0.6 * widthScale)} ${n(rightY - h * 0.2)}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${n(h * 0.6)}\" stroke-linecap=\"round\" opacity=\"${n(opacity)}\"/>"
            else -> ""
        }
    }

    private fun generateFace(rng: Rng, size: Double, bgColor: String): String {
        val eyeType = EYE_TYPES[floor(rng.next() * EYE_TYPES.size).toInt()]
        val mouthType = MOUTH_TYPES[floor(rng.next() * MOUTH_TYPES.size).toInt()]
        val expression = EXPRESSIONS[floor(rng.next() * EXPRESSIONS.size).toInt()]
        val faceColor = bgColor  // FACE.color = 'bg'
        val opacity = 1.0
        val cx = size / 2; val cy = size / 2
        val eyeRadius = size * 0.12 / 2
        val eyeSpacing = size * 0.26 / 2
        val eyeY = size * 0.38

        // layout variation
        val faceShiftX = (rng.next() - 0.5) * size * 0.05
        val faceShiftY = (rng.next() - 0.5) * size * 0.03
        val mouthShiftX = (rng.next() - 0.5) * size * 0.04
        var mouthShiftY = (rng.next() - 0.5) * size * 0.025
        val eyeSpread = 0.92 + rng.next() * 0.18
        var eyeTiltOffset = (rng.next() - 0.5) * size * 0.018
        if (expression == "happy") mouthShiftY += size * 0.005
        if (expression == "curious") eyeTiltOffset *= 1.25

        var tiltAngle = 0.0
        var eyeOffsetY = 0.0
        var mouthWidthMod = 1.0
        when (expression) {
            "tilt" -> tiltAngle = (if (rng.next() > 0.5) 1.0 else -1.0) * (5 + rng.next() * 10)
            "happy" -> { eyeOffsetY = -size * 0.02; mouthWidthMod = 1.2 }
            "curious" -> eyeOffsetY = (if (rng.next() > 0.5) 1.0 else -1.0) * size * 0.015
        }

        val leftEyeX = cx - eyeSpacing * eyeSpread + faceShiftX
        val rightEyeX = cx + eyeSpacing * eyeSpread + faceShiftX
        val leftEyeY = eyeY + faceShiftY + eyeTiltOffset + (if (expression == "curious") eyeOffsetY else 0.0)
        val rightEyeY = eyeY + faceShiftY - eyeTiltOffset + (if (expression == "curious") -eyeOffsetY else 0.0)

        // eye variations (left, right)
        val lRadiusScale = 0.88 + rng.next() * 0.24
        val lArchHeight = (0.65 + rng.next() * 0.65) + (if (expression == "happy") 0.15 else 0.0)
        val lArchWidth = 0.90 + rng.next() * 0.25
        var lTilt = (rng.next() - 0.5) * 0.7
        val lPupil = 0.28 + rng.next() * 0.16
        val rRadiusScale = 0.88 + rng.next() * 0.24
        val rArchHeight = (0.65 + rng.next() * 0.65) + (if (expression == "happy") 0.15 else 0.0)
        val rArchWidth = 0.90 + rng.next() * 0.25
        var rTilt = (rng.next() - 0.5) * 0.7
        val rPupil = 0.28 + rng.next() * 0.16
        rTilt *= -1  // side 'right'

        val sb = StringBuilder()
        sb.append(generateEye(eyeType, leftEyeX, leftEyeY, eyeRadius, faceColor, opacity, lRadiusScale, lTilt, lArchHeight, lArchWidth, lPupil))
        sb.append(generateEye(eyeType, rightEyeX, rightEyeY, eyeRadius, faceColor, opacity, rRadiusScale, rTilt, rArchHeight, rArchWidth, rPupil))

        // mouth variation
        val mWidthScale = 0.9 + rng.next() * 0.3
        var mTilt = (rng.next() - 0.5) * 0.9
        var mCurveDepth = 0.7 + rng.next() * 0.9
        val mOpenScale = 0.8 + rng.next() * 0.8
        val mTopLift = rng.next() * 0.45
        if (expression == "happy") mCurveDepth += 0.2
        if (expression == "tilt") mTilt *= 1.25
        if (mouthType == "neutral" || mouthType == "line") mCurveDepth *= 0.4

        val mouthY = size * 0.58 + mouthShiftY
        val mouthWidth = size * 0.15 * mouthWidthMod
        sb.append(generateMouth(mouthType, cx + mouthShiftX, mouthY, mouthWidth, eyeRadius * 1.0, faceColor, opacity, mWidthScale, mTilt, mCurveDepth, mOpenScale, mTopLift))

        return if (tiltAngle != 0.0) {
            "<g transform=\"rotate(${String.format(java.util.Locale.ROOT, "%.1f", tiltAngle)} ${n(cx)} ${n(cy)})\" opacity=\"${n(opacity)}\">$sb</g>"
        } else sb.toString()
    }

    /**
     * Flat colour derived from the same seed — used as a placeholder behind the
     * SVG so a decode failure never leaves an empty slot.
     */
    fun fallbackColor(address0: String?): androidx.compose.ui.graphics.Color {
        val address = (address0 ?: "0x0000000000000000000000000000000000000000").lowercase()
        val rng = Rng(hashString(address))
        val bg = colorFromSpectrum(rng, 0.0, 360.0, 35.0, 50.0, 10.0, 15.0)
        return androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(bg.hex))
    }

    /** Deterministic SVG (same as the web generateAvatar). size in px, borderRadius 0-1. */
    fun svg(address0: String?, size: Int = 64, borderRadius: Double = 0.2): String {
        val address = (address0 ?: "0x0000000000000000000000000000000000000000").lowercase()
        val seed = hashString(address)
        val rng = Rng(seed)
        val sz = size.toDouble()

        val bg = colorFromSpectrum(rng, 0.0, 360.0, 35.0, 50.0, 10.0, 15.0)
        val shapeColor = harmonicColor(rng, bg.hue, 40.0, 55.0, 45.0, 65.0)
        val cx = sz / 2; val cy = sz / 2

        val shapeRng = Rng(seed xor 0x51f15eed)
        val path = generateShape(shapeRng, cx, cy, sz)
        val rotation = floor(shapeRng.next() * 360).toInt()
        val rx = sz * borderRadius

        val faceRng = Rng((seed xor 0x51f15eed) + 0xFACE)
        val face = generateFace(faceRng, sz, bg.hex)

        val clipId = "avatar-clip-${Integer.toString(seed, 36)}"
        val fw = sz * 0.06
        val innerSize = sz - fw * 2
        val frame = "<path d=\"M0,0 h${n(sz)} v${n(sz)} h-${n(sz)} Z M${n(fw)},${n(fw)} v${n(innerSize)} h${n(innerSize)} v-${n(innerSize)} Z\" fill=\"${bg.hex}\" fill-rule=\"evenodd\"/>"

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${n(sz)} ${n(sz)}\" width=\"${n(sz)}\" height=\"${n(sz)}\">" +
            "<defs><clipPath id=\"$clipId\"><rect width=\"${n(sz)}\" height=\"${n(sz)}\" rx=\"${n(rx)}\"/></clipPath></defs>" +
            "<g clip-path=\"url(#$clipId)\">" +
            "<rect width=\"${n(sz)}\" height=\"${n(sz)}\" fill=\"${bg.hex}\"/>" +
            "<g transform=\"rotate($rotation ${n(cx)} ${n(cy)})\"><path d=\"$path\" fill=\"$shapeColor\"/></g>" +
            face + frame + "</g></svg>"
    }

    /** Address color (same sequence as the web getAddressColor) — for names. */
    fun addressColor(address0: String?): String {
        val address = (address0 ?: "0x0000000000000000000000000000000000000000").lowercase()
        val rng = Rng(hashString(address))
        val bg = colorFromSpectrum(rng, 0.0, 360.0, 35.0, 50.0, 10.0, 15.0)
        return harmonicColor(rng, bg.hue, 40.0, 55.0, 45.0, 65.0)
    }
}
