package com.kaizen.overlayline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var touchContainer: FrameLayout? = null
    private var menuView: View? = null
    private lateinit var lineParams: WindowManager.LayoutParams

    private val channelId = "overlay_line_channel"
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isVertical = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addLineView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Overlay Line", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Overlay Line aktif")
            .setContentText("Tap garis untuk opsi, tahan 1 detik untuk putar arah")
            .setSmallIcon(android.R.drawable.presence_online)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun addLineView() {
        val density = resources.displayMetrics.density

        val visualThicknessPx = (4 * density).toInt()
        val touchThicknessPx = (48 * density).toInt()
        val lengthPx = (resources.displayMetrics.heightPixels * 0.8).toInt()

        val container = FrameLayout(this)
        val visualLine = View(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val innerParams = if (isVertical) {
            FrameLayout.LayoutParams(visualThicknessPx, FrameLayout.LayoutParams.MATCH_PARENT)
        } else {
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, visualThicknessPx)
        }
        innerParams.gravity = Gravity.CENTER
        container.addView(visualLine, innerParams)
        touchContainer = container

        val params = WindowManager.LayoutParams(
            if (isVertical) touchThicknessPx else lengthPx,
            if (isVertical) lengthPx else touchThicknessPx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (resources.displayMetrics.widthPixels - params.width) / 2
        params.y = (resources.displayMetrics.heightPixels - params.height) / 2

        lineParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L
        var longPressTriggered = false

        val dragSlopPx = (24 * density)

        val longPressRunnable = Runnable {
            longPressTriggered = true
            toggleOrientation()
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lineParams.x
                    initialY = lineParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    downTime = System.currentTimeMillis()
                    longPressHandler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX)
                    val dy = (event.rawY - initialTouchY)
                    if (!isDragging && (abs(dx) > dragSlopPx || abs(dy) > dragSlopPx)) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        lineParams.x = initialX + dx.toInt()
                        lineParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(touchContainer, lineParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!isDragging && !longPressTriggered && elapsed < 400) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
    }

    private fun toggleOrientation() {
        isVertical = !isVertical

        val density = resources.displayMetrics.density
        val touchThicknessPx = (48 * density).toInt()
        val lengthPx = (resources.displayMetrics.heightPixels * 0.8).toInt()

        val centerX = lineParams.x + lineParams.width / 2
        val centerY = lineParams.y + lineParams.height / 2

        lineParams.width = if (isVertical) touchThicknessPx else lengthPx
        lineParams.height = if (isVertical) lengthPx else touchThicknessPx
        lineParams.x = centerX - lineParams.width / 2
        lineParams.y = centerY - lineParams.height / 2

        val container = touchContainer ?: return
        container.removeAllViews()
        val visualThicknessPx = (4 * density).toInt()
        val visualLine = View(this).apply { setBackgroundColor(Color.WHITE) }
        val innerParams = if (isVertical) {
            FrameLayout.LayoutParams(visualThicknessPx, FrameLayout.LayoutParams.MATCH_PARENT)
        } else {
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, visualThicknessPx)
        }
        innerParams.gravity = Gravity.CENTER
        container.addView(visualLine, innerParams)

        windowManager.updateViewLayout(container, lineParams)
    }

    private fun toggleMenu() {
        val existingMenu = menuView
        if (existingMenu != null) {
            windowManager.removeView(existingMenu)
            menuView = null
            return
        }

        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .sortedBy { it.loadLabel(pm).toString() }
            .take(8)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 16, 16, 16)
        }

        val stopItem = TextView(this).apply {
            text = "❌ Matikan Garis"
            setTextColor(0xFFFF5555.toInt())
            textSize = 14f
            setPadding(24, 20, 24, 20)
            setOnClickListener {
                stopOverlayCompletely()
            }
        }
        container.addView(stopItem)

        for (app in apps) {
            val label = app.loadLabel(pm).toString()
            val pkg = app.activityInfo.packageName
            val item = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(24, 20, 24, 20)
                setOnClickListener {
                    val launch = pm.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                    }
                    menuView?.let { v -> windowManager.removeView(v) }
                    menuView = null
                }
            }
            container.addView(item)
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        menuParams.gravity = Gravity.TOP or Gravity.START
        menuParams.x = lineParams.x + lineParams.width + 20
        menuParams.y = lineParams.y

        windowManager.addView(container, menuParams)
        menuView = container
    }

    private fun stopOverlayCompletely() {
        menuView?.let { windowManager.removeView(it) }
        menuView = null
        touchContainer?.let { windowManager.removeView(it) }
        touchContainer = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressHandler.removeCallbacksAndMessages(null)
        touchContainer?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
    }
}
