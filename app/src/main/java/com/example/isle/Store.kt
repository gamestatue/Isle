package com.example.isle

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared state between the accessibility service, the overlay service and the settings screen.
 * Everything runs on the main thread in one process, so plain fields + listeners are enough.
 */
object Store {

    /** Single source of truth for default settings. */
    val DEFAULTS = mapOf(
        "reelEveryN" to 20,   // remind every N reels in a session
        "reelEveryMin" to 10, // remind every N minutes of reels in a session
        "focusMin" to 25,
        "breakMin" to 5,
        "eyeMin" to 20,
        "waterMin" to 45,
        "stretchMin" to 60
    )

    private lateinit var sp: SharedPreferences
    private var inited = false
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Set by OverlayService while it is running; used to pop a message out of the island. */
    var alertSink: ((String) -> Unit)? = null

    // Persisted daily stats
    private var day = ""
    private var reels = 0
    private var reelSec = 0L
    private var pomos = 0

    // Live state
    var inReels = false
        private set
    var sessionReels = 0
        private set
    var sessionSec = 0
        private set
    private var lastInReelsAt = 0L
    private var ticks = 0

    fun init(c: Context) {
        if (inited) return
        sp = c.applicationContext.getSharedPreferences("isle", Context.MODE_PRIVATE)
        day = sp.getString("day", "") ?: ""
        reels = sp.getInt("reels", 0)
        reelSec = sp.getLong("reelSec", 0L)
        pomos = sp.getInt("pomos", 0)
        inited = true
        rollover()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun rollover() {
        val t = today()
        if (day != t) {
            day = t; reels = 0; reelSec = 0L; pomos = 0
            save()
        }
    }

    private fun save() {
        sp.edit().putString("day", day).putInt("reels", reels)
            .putLong("reelSec", reelSec).putInt("pomos", pomos).apply()
    }

    fun flush() = save()

    // ---- listeners -------------------------------------------------------------------------
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }

    fun alert(msg: String) { alertSink?.invoke(msg) }

    // ---- settings --------------------------------------------------------------------------
    fun int(k: String, d: Int = DEFAULTS[k] ?: 0): Int = sp.getInt(k, d)
    fun setInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    fun bool(k: String): Boolean = sp.getBoolean(k, false)
    fun setBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    fun goal(): String = sp.getString("goal", "") ?: ""
    fun setGoal(v: String) { sp.edit().putString("goal", v).apply() }

    // ---- stats -----------------------------------------------------------------------------
    fun reelsToday(): Int { rollover(); return reels }
    fun reelMinutesToday(): Int { rollover(); return (reelSec / 60).toInt() }
    fun pomosToday(): Int { rollover(); return pomos }
    fun addPomo() { rollover(); pomos++; save() }

    // ---- reels -----------------------------------------------------------------------------
    fun setInReels(on: Boolean) {
        if (on == inReels) return
        inReels = on
        if (on) lastInReelsAt = SystemClock.elapsedRealtime()
        notifyChanged()
    }

    fun addReel() {
        rollover()
        reels++
        sessionReels++
        save()
        val n = int("reelEveryN")
        if (n > 0 && sessionReels % n == 0) {
            val level = sessionReels / n
            alert(
                when (level) {
                    1 -> "🎞 $sessionReels reels in a row. Maybe wrap it up?"
                    2 -> "🎞 $sessionReels reels. Your to-do list misses you."
                    else -> "🎞 $sessionReels reels. Seriously, put the phone down 🙃"
                }
            )
        }
        notifyChanged()
    }

    /** Manual correction from the island's +/- buttons. */
    fun adjustReels(delta: Int) {
        rollover()
        reels = (reels + delta).coerceAtLeast(0)
        sessionReels = (sessionReels + delta).coerceAtLeast(0)
        save()
        notifyChanged()
    }

    /** Called once per second by the overlay service. */
    fun tickReelTime() {
        val now = SystemClock.elapsedRealtime()
        if (inReels) {
            lastInReelsAt = now
            rollover()
            reelSec++
            sessionSec++
            val m = int("reelEveryMin")
            if (m > 0 && sessionSec % (m * 60) == 0) {
                alert("⏱ ${sessionSec / 60} min of reels this session. Time check!")
            }
        } else if (now - lastInReelsAt > 10 * 60_000L && (sessionReels > 0 || sessionSec > 0)) {
            // Away from reels for 10 minutes: a new session starts next time.
            sessionReels = 0
            sessionSec = 0
        }
        if (++ticks % 15 == 0) save()
    }
}
