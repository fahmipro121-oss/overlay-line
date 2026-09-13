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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var lineWindow: FrameLayout? = null
    private var menuView: View? = null
    private lateinit var lineParams: WindowManager.LayoutParams

    private val channelId = "overlay_line_channel"

    private var centerX = 0f
    private var centerY = 0f
    private var angleDeg = -90f
    private var lengthPx = 0f
    private var boxSize = 0

    // gesture state
    private var mode = "idle"
    private var refTouchX = 0f
    private var refTouchY = 0f
    private var refCenterX = 0f
    private var refCenterY = 0f
    private var refAngle = 0f
    private var refLineAngle = 0f
    private var refMidX = 0f
    private var refMidY = 0f
    private var downTime = 0L
    private var moved = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupGeometry()
        addLineWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }

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
            .setContentText("1 jari geser, 2 jari putar, tap buka menu")
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

    private fun setupGeometry() {
        val density = resources.displayMetrics.density
        lengthPx = resources.displayMetrics.heightPixels * 0.8f
        boxSize = (lengthPx + 80 * density).toInt()
        centerX = resources.displayMetrics.widthPixels / 2f
        centerY = resources.displayMetrics.heightPixels / 2f
    }

    private fun addLineWindow() {
        val density = resources.displayMetrics.density
        val thicknessPx = (4 * density).toInt()

        val container = FrameLayout(this)
        val bar = View(this).apply { setBackgroundColor(Color.WHITE) }
        val barParams = FrameLayout.LayoutParams(thicknessPx, lengthPx.toInt())
        barParams.gravity = Gravity.CENTER
        container.addView(bar, barParams)
        bar.rotation = angleDeg + 90f
        lineWindow = container

        val params = WindowManager.LayoutParams(
            boxSize,
            boxSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (centerX - boxSize / 2f).toInt()
        params.y = (centerY - boxSize / 2f).toInt()
        lineParams = params

        container.setOnTouchListener { _, event ->
            handleTouch(event, container, bar)
        }

        windowManager.addView(container, params)
    }

    private fun handleTouch(event: MotionEvent, container: FrameLayout, bar: View): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = "drag"
                refTouchX = event.rawX
                refTouchY = event.rawY
                refCenterX = centerX
                refCenterY = centerY
                downTime = System.currentTimeMillis()
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mode = "rotate"
                    val x0 = event.getX(0); val y0 = event.getY(0)
                    val x1 = event.getX(1); val y1 = event.getY(1)
                    refAngle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                    refLineAngle = angleDeg
                    refMidX = (x0 + x1) / 2f
                    refMidY = (y0 + y1) / 2f
                    refCenterX = centerX
                    refCenterY = centerY
                    moved = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == "rotate" && event.pointerCount >= 2) {
                    val x0 = event.getX(0); val y0 = event.getY(0)
                    val x1 = event.getX(1); val y1 = event.getY(1)
                    val currentAngle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                    val delta = currentAngle - refAngle
                    angleDeg = refLineAngle + delta
                    val midX = (x0 + x1) / 2f
                    val midY = (y0 + y1) / 2f
                    centerX = refCenterX + (midX - refMidX)
                    centerY = refCenterY + (midY - refMidY)
                    applyGeometry(container, bar)
                } else if (mode == "drag") {
                    val dx = event.rawX - refTouchX
                    val dy = event.rawY - refTouchY
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    centerX = refCenterX + dx
                    centerY = refCenterY + dy
                    applyGeometry(container, bar)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = "drag"
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    refTouchX = lineParams.x + event.getX(remainingIndex)
                    refTouchY = lineParams.y + event.getY(remainingIndex)
                }
                refCenterX = centerX
                refCenterY = centerY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val elapsed = System.currentTimeMillis() - downTime
                if (!moved && elapsed < 500) {
                    toggleMenu()
                }
                mode = "idle"
            }
        }
        return true
    }

    private fun applyGeometry(container: FrameLayout, bar: View) {
        lineParams.x = (centerX - boxSize / 2f).toInt()
        lineParams.y = (centerY - boxSize / 2f).toInt()
        windowManager.updateViewLayout(container, lineParams)
        bar.rotation = angleDeg + 90f
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
            setBackgroundColor(0xEE000000.toInt())
            setPadding(24, 24, 24, 24)
        }

        val stopItem = TextView(this).apply {
            text = "❌ Matikan Garis"
            setTextColor(0xFFFF6666.toInt())
            textSize = 15f
            setPadding(24, 22, 24, 22)
            setOnClickListener { stopOverlayCompletely() }
        }
        container.addView(stopItem)

        for (app in apps) {
            val label = app.loadLabel(pm).toString()
            val pkg = app.activityInfo.packageName
            val item = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setPadding(24, 22, 24, 22)
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

        val density = resources.displayMetrics.density
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        menuParams.gravity = Gravity.TOP or Gravity.START
        menuParams.x = (40 * density).toInt()
        menuParams.y = (80 * density).toInt()

        windowManager.addView(container, menuParams)
        menuView = container
    }

    private fun stopOverlayCompletely() {
        menuView?.let { windowManager.removeView(it) }
        menuView = null
        lineWindow?.let { windowManager.removeView(it) }
        lineWindow = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        lineWindow?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
    }
}
