package com.example.isle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

private val BLACK = 0xFF000000.toInt()
private val CARD2 = 0xFF2C2C2E.toInt()
private val GRAY = 0xFF8E8E93.toInt()
private val RED = 0xFFFF453A.toInt()
private val GREEN = 0xFF32D74B.toInt()
private val BLUE = 0xFF0A84FF.toInt()
private val ORANGE = 0xFFFF9F0A.toInt()

/**
 * The dynamic-island pill.
 *  - collapsed: tiny capsule showing the most relevant live info
 *  - tap: expands into the full panel (focus timer, reel counter, music, quick tools)
 *  - drag: move it (e.g. over your camera punch-hole); position is remembered
 *  - alerts: reminders pop out of the pill by themselves, with a short vibration
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.isle.STOP"
        private const val CHANNEL = "isle_fg"
        private const val NOTIF_ID = 42

        @Volatile
        var isRunning = false

        fun start(c: Context) {
            c.startForegroundService(Intent(c, OverlayService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private var expanded = false

    // views
    private lateinit var dotBg: GradientDrawable
    private lateinit var tvMini: TextView
    private lateinit var alertView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tvMode: TextView
    private lateinit var tvGoal: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnPlay: TextView
    private lateinit var tvReels: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvTrack: TextView
    private lateinit var btnMusicPlay: TextView
    private lateinit var chipTorch: TextView
    private lateinit var chipEye: TextView
    private lateinit var chipWater: TextView
    private lateinit var chipStretch: TextView

    // pomodoro
    private var isFocus = true
    private var pomoRunning = false
    private var pomoTouched = false
    private var remaining = 0

    // reminders: elapsedRealtime at which they next fire, 0 = off
    private var eyeAt = 0L
    private var waterAt = 0L
    private var stretchAt = 0L

    // music
    private var controller: MediaController? = null
    private var trackText = ""
    private var musicPlaying = false
    private var musicAccess = true

    private var torchOn = false
    private var tickCount = 0
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // drag state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val uiListener: () -> Unit = { if (::root.isInitialized) refreshUi() }
    private val hideAlert = Runnable { alertView.visibility = View.GONE }

    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            tickPomo()
            Store.tickReelTime()
            tickReminders()
            if (tickCount % 2 == 0) refreshMusic()
            refreshUi()
            h.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        remaining = Store.int("focusMin") * 60
        buildUi()
        wm.addView(root, params)
        Store.alertSink = { showAlert(it) }
        Store.addListener(uiListener)
        armReminders()
        refreshMusic()
        h.post(tick)
    }

    override fun onDestroy() {
        isRunning = false
        h.removeCallbacksAndMessages(null)
        Store.alertSink = null
        Store.removeListener(uiListener)
        Store.flush()
        if (torchOn) toggleTorch()
        if (::root.isInitialized) {
            try { wm.removeView(root) } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Isle overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Isle is running")
            .setContentText("Tap to open settings")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------------------------------
    // UI building
    // ---------------------------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rect(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun lbl(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun circleBtn(label: String, size: Int = 44, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = if (size >= 44) 18f else 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = oval(CARD2)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rect(CARD2, 14)
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun hrow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(CARD2)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rect(BLACK, 28)
        }

        // ---- collapsed capsule ----
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(36)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        dotBg = oval(GRAY)
        val dot = View(this).apply {
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        tvMini = lbl("", 13f, Color.WHITE, true).apply { setPadding(dp(8), 0, 0, 0) }
        row.addView(dot)
        row.addView(tvMini)
        root.addView(row)

        // ---- alert line (pops out under the capsule) ----
        alertView = lbl("", 13f).apply {
            setPadding(dp(16), dp(2), dp(16), dp(12))
            maxWidth = dp(300)
            maxLines = 3
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(alertView)

        // ---- expanded panel ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
            minimumWidth = dp(320)
            visibility = View.GONE
        }

        // focus header
        val header = hrow()
        tvMode = lbl("FOCUS", 11f, RED, true)
        tvClock = lbl("", 11f, GRAY)
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        header.addView(tvMode); header.addView(spacer); header.addView(tvClock)
        panel.addView(header)

        tvGoal = lbl("", 13f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        panel.addView(tvGoal)

        val timerRow = hrow().apply { setPadding(0, dp(8), 0, 0) }
        tvTimer = lbl("25:00", 38f, Color.WHITE, true).apply {
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        btnPlay = circleBtn("▶") { togglePomo() }
        timerRow.addView(tvTimer)
        timerRow.addView(btnPlay)
        timerRow.addView(circleBtn("↺") { resetPomo() })
        timerRow.addView(circleBtn("⏭") { skipPomo() })
        panel.addView(timerRow)
        panel.addView(divider())

        // reels
        val reelsRow = hrow()
        val reelsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tvReels = lbl("", 14f, Color.WHITE, true)
        tvSession = lbl("", 12f, GRAY).apply { setPadding(0, dp(3), 0, 0) }
        reelsCol.addView(tvReels)
        reelsCol.addView(tvSession)
        reelsRow.addView(reelsCol)
        reelsRow.addView(circleBtn("−", 32) { Store.adjustReels(-1) })
        reelsRow.addView(circleBtn("+", 32) { Store.adjustReels(1) })
        panel.addView(reelsRow)
        panel.addView(divider())

        // music
        val musicRow = hrow()
        tvTrack = lbl("", 13f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        btnMusicPlay = circleBtn("▶", 40) { musicCmd(1) }
        musicRow.addView(tvTrack)
        musicRow.addView(circleBtn("⏮", 40) { musicCmd(0) })
        musicRow.addView(btnMusicPlay)
        musicRow.addView(circleBtn("⏭", 40) { musicCmd(2) })
        panel.addView(musicRow)
        panel.addView(divider())

        // quick tools
        val chips = hrow()
        chipTorch = chip("🔦") { toggleTorch(); refreshUi() }
        chipEye = chip("👁") { toggleReminder("eyeOn") }
        chipWater = chip("💧") { toggleReminder("waterOn") }
        chipStretch = chip("🧘") { toggleReminder("stretchOn") }
        val chipSettings = chip("⚙") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            setExpanded(false)
        }
        (chipTorch.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        chips.addView(chipTorch); chips.addView(chipEye); chips.addView(chipWater)
        chips.addView(chipStretch); chips.addView(chipSettings)
        panel.addView(chips)

        root.addView(panel)

        // ---- window ----
        params = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = Store.int("posX", 0)
            y = Store.int("posY", dp(6))
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root.setOnTouchListener { _, e -> handleTouch(e) }
        refreshUi()
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = params.x; startY = params.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > dp(8)) moved = true
                if (moved) {
                    params.x = startX + dx.toInt()
                    params.y = (startY + dy.toInt()).coerceAtLeast(0)
                    wm.updateViewLayout(root, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    Store.setInt("posX", params.x)
                    Store.setInt("posY", params.y)
                } else {
                    onTap()
                }
            }
            MotionEvent.ACTION_OUTSIDE -> if (expanded) setExpanded(false)
            else -> return false
        }
        return true
    }

    private fun onTap() {
        if (alertView.visibility == View.VISIBLE) {
            h.removeCallbacks(hideAlert)
            hideAlert.run()
        } else {
            setExpanded(!expanded)
        }
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        if (v) {
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(180).start()
            refreshMusic()
            refreshUi()
        } else {
            panel.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------
    // alerts
    // ---------------------------------------------------------------------------------------
    private fun showAlert(msg: String) {
        if (!::root.isInitialized) return
        alertView.text = msg
        alertView.alpha = 0f
        alertView.visibility = View.VISIBLE
        alertView.animate().alpha(1f).setDuration(200).start()
        buzz()
        h.removeCallbacks(hideAlert)
        h.postDelayed(hideAlert, 7000)
    }

    private fun buzz() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1))
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------------------------------
    // pomodoro
    // ---------------------------------------------------------------------------------------
    private fun fullSec() = Store.int(if (isFocus) "focusMin" else "breakMin") * 60

    private fun togglePomo() {
        pomoRunning = !pomoRunning
        if (pomoRunning) pomoTouched = true
        refreshUi()
    }

    private fun resetPomo() {
        pomoRunning = false
        pomoTouched = false
        isFocus = true
        remaining = fullSec()
        refreshUi()
    }

    private fun skipPomo() {
        isFocus = !isFocus
        remaining = fullSec()
        refreshUi()
    }

    private fun tickPomo() {
        if (!pomoRunning) {
            if (!pomoTouched) remaining = fullSec()
            return
        }
        remaining--
        if (remaining <= 0) finishPhase()
    }

    private fun finishPhase() {
        if (isFocus) {
            Store.addPomo()
            isFocus = false
            remaining = fullSec()
            pomoRunning = true // break starts automatically
            showAlert("🍅 Focus session done! ${Store.int("breakMin")} min break — step away from the screen.")
        } else {
            isFocus = true
            remaining = fullSec()
            pomoRunning = false
            pomoTouched = false
            showAlert("☕ Break's over. Ready for the next focus session?")
        }
    }

    // ---------------------------------------------------------------------------------------
    // reminders (20-20-20 eyes, water, stretch)
    // ---------------------------------------------------------------------------------------
    private fun interval(key: String) = Store.int(key) * 60_000L

    private fun toggleReminder(key: String) {
        Store.setBool(key, !Store.bool(key))
        armReminders()
        refreshUi()
    }

    private fun armReminders() {
        val now = SystemClock.elapsedRealtime()
        eyeAt = if (!Store.bool("eyeOn")) 0L else if (eyeAt == 0L) now + interval("eyeMin") else eyeAt
        waterAt = if (!Store.bool("waterOn")) 0L else if (waterAt == 0L) now + interval("waterMin") else waterAt
        stretchAt = if (!Store.bool("stretchOn")) 0L else if (stretchAt == 0L) now + interval("stretchMin") else stretchAt
    }

    private fun tickReminders() {
        val now = SystemClock.elapsedRealtime()
        if (eyeAt != 0L && now >= eyeAt) {
            eyeAt = now + interval("eyeMin")
            showAlert("👁 20-20-20: look at something 20 ft away for 20 seconds")
        }
        if (waterAt != 0L && now >= waterAt) {
            waterAt = now + interval("waterMin")
            showAlert("💧 Time for a few sips of water")
        }
        if (stretchAt != 0L && now >= stretchAt) {
            stretchAt = now + interval("stretchMin")
            showAlert("🧘 Stand up, stretch, relax your shoulders")
        }
    }

    // ---------------------------------------------------------------------------------------
    // music
    // ---------------------------------------------------------------------------------------
    private fun refreshMusic() {
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
            controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull()
            musicAccess = true
        } catch (e: SecurityException) {
            controller = null
            musicAccess = false
        }
        val c = controller
        val md = c?.metadata
        musicPlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        trackText = when {
            !musicAccess -> "Enable notification access for track info"
            title.isNullOrBlank() -> "Nothing playing"
            artist.isNullOrBlank() -> title
            else -> "$title — $artist"
        }
    }

    /** kind: 0 = previous, 1 = play/pause, 2 = next */
    private fun musicCmd(kind: Int) {
        val c = controller
        if (c != null) {
            val t = c.transportControls
            when (kind) {
                0 -> t.skipToPrevious()
                1 -> if (musicPlaying) t.pause() else t.play()
                else -> t.skipToNext()
            }
        } else {
            val code = when (kind) {
                0 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                1 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                else -> KeyEvent.KEYCODE_MEDIA_NEXT
            }
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        h.postDelayed({ refreshMusic(); refreshUi() }, 300)
    }

    // ---------------------------------------------------------------------------------------
    // torch
    // ---------------------------------------------------------------------------------------
    private fun toggleTorch() {
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) {
            torchOn = false
        }
    }

    // ---------------------------------------------------------------------------------------
    // rendering
    // ---------------------------------------------------------------------------------------
    private fun fmt(s: Int): String {
        val v = s.coerceAtLeast(0)
        return "%02d:%02d".format(v / 60, v % 60)
    }

    private fun clock() = clockFmt.format(Date())

    private fun battery(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun setActive(v: TextView, on: Boolean) {
        v.background = rect(if (on) BLUE else CARD2, 14)
    }

    private fun refreshUi() {
        if (!::root.isInitialized) return
        val timeStr = fmt(remaining)

        // collapsed capsule
        val parts = ArrayList<String>()
        if (musicPlaying) parts.add("♪")
        if (pomoRunning || pomoTouched) parts.add((if (isFocus) "🍅 " else "☕ ") + timeStr)
        if (Store.inReels) parts.add("🎞 ${Store.sessionReels}")
        if (parts.isEmpty()) parts.add(clock())
        tvMini.text = parts.joinToString("  ·  ")
        dotBg.setColor(
            when {
                pomoRunning && isFocus -> RED
                pomoRunning && !isFocus -> GREEN
                Store.inReels -> ORANGE
                else -> GRAY
            }
        )

        if (!expanded) return

        // expanded panel
        tvMode.text = (if (isFocus) "FOCUS" else "BREAK") + "  ·  🍅 ${Store.pomosToday()} today"
        tvMode.setTextColor(if (isFocus) RED else GREEN)
        val g = Store.goal()
        tvGoal.text = if (g.isBlank()) "Set a focus goal in the Isle app" else g
        tvClock.text = "${clock()}  ·  🔋 ${battery()}%"
        tvTimer.text = timeStr
        btnPlay.text = if (pomoRunning) "⏸" else "▶"

        tvReels.text = "🎞 ${Store.reelsToday()} reels today · ${Store.reelMinutesToday()} min"
        tvSession.text = "This session: ${Store.sessionReels} reels · ${Store.sessionSec / 60} min"

        tvTrack.text = trackText
        btnMusicPlay.text = if (musicPlaying) "⏸" else "▶"

        setActive(chipTorch, torchOn)
        setActive(chipEye, Store.bool("eyeOn"))
        setActive(chipWater, Store.bool("waterOn"))
        setActive(chipStretch, Store.bool("stretchOn"))
    }
}
