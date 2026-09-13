package com.kaizen.overlayline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val channelId = "overlay_line_channel"

    // The two endpoints of the line, in absolute screen coordinates.
    private var p1x = 0f
    private var p1y = 0f
    private var p2x = 0f
    private var p2y = 0f

    private var thicknessPx = 0
    private var handleSizePx = 0

    private var lineVisual: FrameLayout? = null
    private var lineBar: View? = null
    private lateinit var lineParams: WindowManager.LayoutParams

    private var handle1: View? = null
    private lateinit var handle1Params: WindowManager.LayoutParams
    private var handle2: View? = null
    private lateinit var handle2Params: WindowManager.LayoutParams
    private var handleMid: View? = null
    private lateinit var handleMidParams: WindowManager.LayoutParams

    private var menuView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()

        val density = resources.displayMetrics.density
        thicknessPx = (4 * density).toInt()
        handleSizePx = (40 * density).toInt()

        val cx = resources.displayMetrics.widthPixels / 2f
        val cy = resources.displayMetrics.heightPixels / 2f
        val halfLen = resources.displayMetrics.heightPixels * 0.4f
        p1x = cx; p1y = cy - halfLen
        p2x = cx; p2y = cy + halfLen

        addLineVisual()
        addHandle1()
        addHandle2()
        addHandleMid()
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
            .setContentText("Seret titik ujung = putar, titik tengah = geser")
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

    private fun makeHandleView(): View {
        val v = View(this)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(2, Color.DKGRAY)
        }
        return v
    }

    // ---------- visual line: pure decoration, click-through ----------

    private fun addLineVisual() {
        val box = FrameLayout(this)
        val bar = View(this).apply { setBackgroundColor(Color.WHITE) }
        box.addView(bar, FrameLayout.LayoutParams(thicknessPx, thicknessPx))
        lineVisual = box
        lineBar = bar

        val params = WindowManager.LayoutParams(
            thicknessPx, thicknessPx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        lineParams = params
        windowManager.addView(box, params)
        refreshLineVisual()
    }

    private fun refreshLineVisual() {
        val box = lineVisual ?: return
        val bar = lineBar ?: return

        val dx = p2x - p1x
        val dy = p2y - p1y
        val length = hypot(dx, dy)
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        val rad = Math.toRadians(angle.toDouble())
        val boxW = max((length * abs(sin(rad)) + thicknessPx * abs(cos(rad))).toInt(), thicknessPx)
        val boxH = max((length * abs(cos(rad)) + thicknessPx * abs(sin(rad))).toInt(), thicknessPx)

        val barParams = FrameLayout.LayoutParams(thicknessPx, max(length.toInt(), 1))
        barParams.gravity = Gravity.CENTER
        bar.layoutParams = barParams
        bar.rotation = angle + 90f

        val cx = (p1x + p2x) / 2f
        val cy = (p1y + p2y) / 2f
        lineParams.width = boxW
        lineParams.height = boxH
        lineParams.x = (cx - boxW / 2f).toInt()
        lineParams.y = (cy - boxH / 2f).toInt()
        windowManager.updateViewLayout(box, lineParams)
    }

    // ---------- endpoint handles: drag freely, pivots the other end ----------

    private fun addHandle1() {
        val h = makeHandleView()
        handle1 = h
        val params = WindowManager.LayoutParams(
            handleSizePx, handleSizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        handle1Params = params
        var offX = 0f; var offY = 0f
        h.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { offX = event.rawX - p1x; offY = event.rawY - p1y }
                MotionEvent.ACTION_MOVE -> {
                    p1x = event.rawX - offX
                    p1y = event.rawY - offY
                    repositionHandle(handle1, handle1Params, p1x, p1y)
                    repositionHandle(handleMid, handleMidParams, (p1x + p2x) / 2f, (p1y + p2y) / 2f)
                    refreshLineVisual()
                }
                else -> {}
            }
            true
        }
        repositionHandle(h, params, p1x, p1y)
        windowManager.addView(h, params)
    }

    private fun addHandle2() {
        val h = makeHandleView()
        handle2 = h
        val params = WindowManager.LayoutParams(
            handleSizePx, handleSizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        handle2Params = params
        var offX = 0f; var offY = 0f
        h.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { offX = event.rawX - p2x; offY = event.rawY - p2y }
                MotionEvent.ACTION_MOVE -> {
                    p2x = event.rawX - offX
                    p2y = event.rawY - offY
                    repositionHandle(handle2, handle2Params, p2x, p2y)
                    repositionHandle(handleMid, handleMidParams, (p1x + p2x) / 2f, (p1y + p2y) / 2f)
                    refreshLineVisual()
                }
                else -> {}
            }
            true
        }
        repositionHandle(h, params, p2x, p2y)
        windowManager.addView(h, params)
    }

    // ---------- middle handle: drag = move whole line, tap = menu ----------

    private fun addHandleMid() {
        val h = makeHandleView()
        handleMid = h
        val params = WindowManager.LayoutParams(
            handleSizePx, handleSizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        handleMidParams = params

        var startTouchX = 0f; var startTouchY = 0f
        var startP1x = 0f; var startP1y = 0f; var startP2x = 0f; var startP2y = 0f
        var downTime = 0L
        var moved = false
        val tapDist = 20 * resources.displayMetrics.density

        h.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX; startTouchY = event.rawY
                    startP1x = p1x; startP1y = p1y; startP2x = p2x; startP2y = p2y
                    downTime = System.currentTimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    p1x = startP1x + dx; p1y = startP1y + dy
                    p2x = startP2x + dx; p2y = startP2y + dy
                    repositionHandle(handle1, handle1Params, p1x, p1y)
                    repositionHandle(handle2, handle2Params, p2x, p2y)
                    repositionHandle(handleMid, handleMidParams, (p1x + p2x) / 2f, (p1y + p2y) / 2f)
                    refreshLineVisual()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDx = event.rawX - startTouchX
                    val totalDy = event.rawY - startTouchY
                    val dist = sqrt(totalDx * totalDx + totalDy * totalDy)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!moved && dist < tapDist && elapsed < 500) {
                        toggleMenu()
                    }
                }
                else -> {}
            }
            true
        }
        repositionHandle(h, params, (p1x + p2x) / 2f, (p1y + p2y) / 2f)
        windowManager.addView(h, params)
    }

    private fun repositionHandle(view: View?, params: WindowManager.LayoutParams, x: Float, y: Float) {
        if (view == null) return
        params.x = (x - handleSizePx / 2f).toInt()
        params.y = (y - handleSizePx / 2f).toInt()
        windowManager.updateViewLayout(view, params)
    }

    // ---------- menu ----------

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
        lineVisual?.let { windowManager.removeView(it) }
        lineVisual = null
        handle1?.let { windowManager.removeView(it) }
        handle1 = null
        handle2?.let { windowManager.removeView(it) }
        handle2 = null
        handleMid?.let { windowManager.removeView(it) }
        handleMid = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        lineVisual?.let { windowManager.removeView(it) }
        handle1?.let { windowManager.removeView(it) }
        handle2?.let { windowManager.removeView(it) }
        handleMid?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
    }
}
