package com.krish.spendsense.ui

import kotlin.math.pow
import kotlin.math.roundToInt

/** Coin mascot mood, chosen from today's spend as a percentage of the daily limit. */
enum class Mood(val label: String) {
    BIG_SMILE("very happy"),
    SMILE("happy"),
    NEUTRAL("neutral"),
    WORRIED("worried"),
    CRYING("stressed"),
}

/** Which sound a spend-based notification plays. */
enum class SpendLevel { CALM, WARNING, OVER }

/**
 * Pure presentation rules for spend-based notification styling (no Android types, so it is
 * unit-tested on the JVM). Colours are ARGB ints.
 */
object SpendStyle {
    const val SURFACE_LIGHT = 0xFFF7FAF9.toInt()
    const val SURFACE_DARK = 0xFF0E1514.toInt()
    const val LIGHT_RED = 0xFFFFCDD2.toInt()
    const val STRONG_RED = 0xFFE53935.toInt()
    const val DEEP_RED = 0xFFB71C1C.toInt()
    const val TEAL = 0xFF0F766E.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF000000.toInt()

    const val CH_CALM = "txn_calm"
    const val CH_WARNING = "txn_warning"
    const val CH_OVER = "txn_over"
    const val CH_SUMMARY = "summaries_v2"

    /** "Money sounds" off: same notifications and importance, system default sound. */
    const val CH_PLAIN = "txn_plain"
    const val CH_PLAIN_ALERT = "txn_plain_alert"
    const val CH_SUMMARY_PLAIN = "summaries_plain"

    /** Channels from earlier versions, removed on upgrade. */
    val OLD_CHANNELS = listOf("transactions", "limits", "summaries")

    /** Today's spend ÷ daily limit, in whole percent (0 when no limit is set). */
    fun percent(spent: Long, limit: Long): Int =
        if (limit <= 0 || spent <= 0) 0 else (spent * 100 / limit).coerceAtMost(10_000).toInt()

    /**
     * Below 50%: the surface colour. 50–100%: a smooth blend surface → light red (at 75%) →
     * strong red (at 100%). Over 100%: deep red.
     */
    fun background(pct: Int, surface: Int): Int = when {
        pct < 50 -> surface
        pct <= 75 -> blend(surface, LIGHT_RED, (pct - 50) / 25f)
        pct <= 100 -> blend(LIGHT_RED, STRONG_RED, (pct - 75) / 25f)
        else -> DEEP_RED
    }

    /** Accent for setColor(): the spend colour once it turns red, the app teal while on track. */
    fun accent(pct: Int, surface: Int): Int = if (pct < 50) TEAL else background(pct, surface)

    /** White or black, whichever contrasts more; either always clears WCAG AA (4.5:1). */
    fun contentColor(background: Int): Int =
        if (contrast(WHITE, background) >= contrast(INK, background)) WHITE else INK

    fun mood(pct: Int): Mood = when {
        pct < 50 -> Mood.BIG_SMILE
        pct < 80 -> Mood.SMILE
        pct <= 100 -> Mood.NEUTRAL
        pct <= 120 -> Mood.WORRIED
        else -> Mood.CRYING
    }

    fun level(pct: Int): SpendLevel = when {
        pct < 80 -> SpendLevel.CALM
        pct <= 100 -> SpendLevel.WARNING
        else -> SpendLevel.OVER
    }

    fun channel(pct: Int, moneySounds: Boolean): String = when (level(pct)) {
        SpendLevel.CALM -> if (moneySounds) CH_CALM else CH_PLAIN
        SpendLevel.WARNING -> if (moneySounds) CH_WARNING else CH_PLAIN_ALERT
        SpendLevel.OVER -> if (moneySounds) CH_OVER else CH_PLAIN_ALERT
    }

    fun summaryChannel(moneySounds: Boolean): String = if (moneySounds) CH_SUMMARY else CH_SUMMARY_PLAIN

    fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * f).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG relative luminance. */
    fun luminance(color: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((color shr 16) and 0xFF) + 0.7152 * lin((color shr 8) and 0xFF) + 0.0722 * lin(color and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
