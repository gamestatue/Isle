package com.example.isle

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {

    private val bg = 0xFF0B0B0F.toInt()
    private val cardBg = 0xFF17171C.toInt()
    private val gray = 0xFF8E8E93.toInt()
    private val green = 0xFF32D74B.toInt()
    private val red = 0xFFFF453A.toInt()
    private val blue = 0xFF0A84FF.toInt()

    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var goalField: EditText
    private lateinit var stOverlay: TextView
    private lateinit var stA11y: TextView
    private lateinit var stListener: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun txt(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int, r: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardBg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun section(t: String) =
        txt(t.uppercase(), 12f, gray, true).apply { setPadding(0, dp(18), 0, dp(8)) }

    private fun setupRow(parent: LinearLayout, title: String, desc: String, open: () -> Unit): TextView {
        val c = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        names.addView(txt(title, 15f, Color.WHITE, true))
        names.addView(txt(desc, 12f, gray))
        val status = txt("", 13f, gray, true)
        top.addView(names)
        top.addView(status)
        c.addView(top)
        c.setOnClickListener { open() }
        parent.addView(c)
        return status
    }

    private fun numRow(parent: LinearLayout, label: String, key: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val l = txt(label, 14f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val f = EditText(this).apply {
            setText(Store.int(key).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fields[key] = f
        row.addView(l)
        row.addView(f)
        parent.addView(row)
    }

    private fun primaryButton(label: String, color: Int = blue, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = rounded(color, 14)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        col.addView(txt("Isle", 34f, Color.WHITE, true))
        col.addView(
            txt("A dynamic-island style overlay for focus, study and reel control", 14f, gray)
                .apply { setPadding(0, dp(4), 0, dp(8)) }
        )

        // ---- run ----
        btnToggle = primaryButton("Start island") { toggleIsland() }
        col.addView(btnToggle)
        col.addView(primaryButton("Send test alert", 0xFF2C2C2E.toInt()) {
            if (OverlayService.isRunning) Store.alert("👋 Hello from Isle!")
            else Toast.makeText(this, "Start the island first", Toast.LENGTH_SHORT).show()
        })
        tvStats = txt("", 13f, gray).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(tvStats)

        // ---- permissions ----
        col.addView(section("Permissions"))
        stOverlay = setupRow(col, "Display over other apps", "Draws the island on top of everything") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        stA11y = setupRow(col, "Accessibility service", "Counts reel / Shorts swipes. Never reads your screen") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        stListener = setupRow(col, "Notification access", "Shows the track title and controls any music app") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val tip = card()
        tip.addView(txt("Switch greyed out?", 14f, Color.WHITE, true))
        tip.addView(
            txt(
                "Android blocks accessibility and notification access for apps installed outside the Play Store. " +
                    "Tap here → ⋮ menu (top right) → “Allow restricted settings”, then try again.",
                12f, gray
            )
        )
        tip.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        col.addView(tip)

        // ---- limits & reminders ----
        col.addView(section("Reel limits"))
        val c1 = card()
        numRow(c1, "Remind every N reels", "reelEveryN")
        numRow(c1, "Remind every N min of reels", "reelEveryMin")
        col.addView(c1)

        col.addView(section("Focus timer"))
        val c2 = card()
        numRow(c2, "Focus (min)", "focusMin")
        numRow(c2, "Break (min)", "breakMin")
        goalField = EditText(this).apply {
            setText(Store.goal())
            hint = "Focus goal, e.g. Finish chapter 4 notes"
            setHintTextColor(gray)
            setTextColor(Color.WHITE)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        c2.addView(goalField)
        col.addView(c2)

        col.addView(section("Health reminders (toggle them in the island)"))
        val c3 = card()
        numRow(c3, "20-20-20 eye break (min)", "eyeMin")
        numRow(c3, "Water (min)", "waterMin")
        numRow(c3, "Stretch (min)", "stretchMin")
        col.addView(c3)

        col.addView(primaryButton("Save settings", green) { save() })

        col.addView(
            txt(
                "Tip: drag the island anywhere — for example over your camera hole. Tap it to expand. " +
                    "Counting works for Instagram Reels and YouTube Shorts; see ReelConfig.kt if an app update breaks detection.",
                12f, gray
            ).apply { setPadding(0, dp(18), 0, 0) }
        )

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(col)
        }
    }

    private fun save() {
        for ((k, f) in fields) {
            val min = if (k == "focusMin" || k == "breakMin") 1 else 0
            f.text.toString().toIntOrNull()?.let { Store.setInt(k, it.coerceIn(min, 999)) }
        }
        Store.setGoal(goalField.text.toString().trim())
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun toggleIsland() {
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
        } else if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            OverlayService.start(this)
        }
        Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 500)
    }

    // ---------------------------------------------------------------------------------------
    private fun a11yOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, ReelAccessibilityService::class.java)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.contains(me)
    }

    private fun listenerOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun setStatus(tv: TextView, ok: Boolean) {
        tv.text = if (ok) "ON" else "OFF"
        tv.setTextColor(if (ok) green else red)
    }

    private fun refreshStatus() {
        setStatus(stOverlay, Settings.canDrawOverlays(this))
        setStatus(stA11y, a11yOn())
        setStatus(stListener, listenerOn())
        btnToggle.text = if (OverlayService.isRunning) "Stop island" else "Start island"
        tvStats.text = "Today: 🎞 ${Store.reelsToday()} reels · ${Store.reelMinutesToday()} min" +
            "     🍅 ${Store.pomosToday()} focus sessions"
    }
}
