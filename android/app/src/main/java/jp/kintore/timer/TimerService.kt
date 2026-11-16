package jp.kintore.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class TimerService : Service() {
    companion object {
        const val ACT_PAUSE = "pause"
        const val ACT_PREV = "prev"
        const val ACT_NEXT = "next"
        const val ACT_STOP = "stop"
        const val EXTRA_SEGS = "segs"
        const val CH = "timer"
        @Volatile var running = false
    }

    private var segs: List<Seg> = emptyList()
    private val starts = ArrayList<Long>()
    private var totalMs = 0L
    private var acc = 0L
    private var base = 0L
    private var paused = false
    private var doneAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bar: LinearLayout
    private lateinit var tv: TextView
    private lateinit var fillV: View
    private lateinit var restV: View
    private var lastText = ""
    private var wake: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 200) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PAUSE -> { togglePause(); return START_NOT_STICKY }
            ACT_PREV -> { jump(-1); return START_NOT_STICKY }
            ACT_NEXT -> { jump(1); return START_NOT_STICKY }
            ACT_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        val json = intent?.getStringExtra(EXTRA_SEGS)
        if (json == null) { stopSelf(); return START_NOT_STICKY }
        segs = segsFromJson(json)
        starts.clear()
        var t = 0L
        segs.forEach { starts.add(t); t += it.sec * 1000L }
        totalMs = t
        acc = 0; base = SystemClock.elapsedRealtime(); paused = false; doneAt = 0

        if (!running) {
            running = true
            setupForeground()
            setupOverlay()
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kintore:timer").apply { acquire(4 * 60 * 60 * 1000L) }
            handler.post(ticker)
        }
        return START_NOT_STICKY
    }

    private fun elapsed() = if (paused) acc else acc + (SystemClock.elapsedRealtime() - base)

    private fun togglePause() {
        if (doneAt > 0) return
        if (paused) { base = SystemClock.elapsedRealtime(); paused = false }
        else { acc = elapsed(); paused = true }
        tick()
    }

    private fun jump(dir: Int) {
        val e = elapsed()
        if (e >= totalMs) return
        val i = starts.indexOfLast { it <= e }.coerceAtLeast(0)
        val target = if (dir > 0) (if (i + 1 < segs.size) starts[i + 1] else totalMs)
        else (if (e - starts[i] > 1000 || i == 0) starts[i] else starts[i - 1])
        acc = target; base = SystemClock.elapsedRealtime()
        tick()
    }

    private fun tick() {
        val e = elapsed()
        val text: String
        val color: Int
        val ratio: Float
        if (e >= totalMs) {
            if (doneAt == 0L) doneAt = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - doneAt > 6000) { stopSelf(); return }
            text = "完了！おつかれさま"; color = Color.rgb(52, 194, 122); ratio = 1f
        } else {
            val i = starts.indexOfLast { it <= e }.coerceAtLeast(0)
            val s = segs[i]
            val remain = ((starts[i] + s.sec * 1000L - e + 999) / 1000).toInt()
            val set = if (s.work && s.sub.isNotEmpty()) " " + s.sub else ""
            text = (if (paused) "⏸ " else "") + "${s.label}$set：残り${fmt(remain)}"
            color = if (s.work) Color.rgb(255, 138, 43) else Color.rgb(52, 194, 122)
            ratio = ((e - starts[i]).toFloat() / (s.sec * 1000f)).coerceIn(0f, 1f)
        }
        if (text != lastText) {
            lastText = text
            tv.text = text
            tv.setTextColor(color)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification(text))
        }
        fillV.setBackgroundColor(color)
        (fillV.layoutParams as LinearLayout.LayoutParams).weight = ratio
        (restV.layoutParams as LinearLayout.LayoutParams).weight = 1f - ratio
        fillV.requestLayout()
    }

    private fun act(a: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, TimerService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("筋トレタイマー").setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "⏮", act(ACT_PREV, 1)).build())
            .addAction(Notification.Action.Builder(null, "⏯", act(ACT_PAUSE, 2)).build())
            .addAction(Notification.Action.Builder(null, "⏭", act(ACT_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(null, "停止", act(ACT_STOP, 4)).build())
            .build()
    }

    private fun setupForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "タイマー", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("開始します")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun setupOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        tv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.WHITE)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        fillV = View(this)
        restV = View(this)
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(fillV, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f))
            addView(restV, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            setBackgroundColor(Color.rgb(45, 52, 61))
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 17, 20, 24))
            addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * dp).toInt()))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (32 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.alpha = 0.8f  // Android 12以降：これ以下なら下のアプリへのタッチを通せる
        wm.addView(bar, lp)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(bar) } catch (e: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
