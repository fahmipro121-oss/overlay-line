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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var lineWindow: FrameLayout? = null
    private var moveHandle: View? = null
    private var rotateHandle: View? = null
    private var menuView: View? = null

    private lateinit var lineParams: WindowManager.LayoutParams
    private lateinit var moveParams: WindowManager.LayoutParams
    private lateinit var rotateParams: WindowManager.LayoutParams

    private val channelId = "overlay_line_channel"

    private var centerX = 0f
    private var centerY = 0f
    private var angleDeg = -90f // -90 = pointing straight up (default vertical line)
    private var halfLengthPx = 0f
    private var handleSizePx = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupGeometry()
        addLineVisual()
        addMoveHandle()
        addRotateHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
            .setContentText("Seret bulatan tengah = geser, bulatan ujung = putar")
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
        halfLengthPx = (resources.displayMetrics.heightPixels * 0.8f) / 2f
        handleSizePx = (44 * density).toInt()
        centerX = resources.displayMetrics.widthPixels / 2f
        centerY = resources.displayMetrics.heightPixels / 2f
    }

    private fun endpointX() = centerX + halfLengthPx * cos(Math.toRadians(angleDeg.toDouble())).toFloat()
    private fun endpointY() = centerY + halfLengthPx * sin(Math.toRadians(angleDeg.toDouble())).toFloat()

    // ---------- visual line (click-through, purely decorative) ----------

    private fun addLineVisual() {
        val density = resources.displayMetrics.density
        val visualThicknessPx = (4 * density).toInt()
        val boxSize = (halfLengthPx * 2 + handleSizePx * 2).toInt()

        val box = FrameLayout(this)
        val bar = View(this).apply { setBackgroundColor(Color.WHITE) }
        val barParams = FrameLayout.LayoutParams(visualThicknessPx, (halfLengthPx * 2).toInt())
        barParams.gravity = Gravity.CENTER
        box.addView(bar, barParams)
        bar.rotation = angleDeg + 90f
        lineWindow = box

        val params = WindowManager.LayoutParams(
            boxSize,
            boxSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (centerX - boxSize / 2f).toInt()
        params.y = (centerY - boxSize / 2f).toInt()
        lineParams = params

        windowManager.addView(box, params)
    }

    private fun refreshLineVisual() {
        val box = lineWindow ?: return
        val boxSize = (halfLengthPx * 2 + handleSizePx * 2).toInt()
        lineParams.x = (centerX - boxSize / 2f).toInt()
        lineParams.y = (centerY - boxSize / 2f).toInt()
        windowManager.updateViewLayout(box, lineParams)
        (box.getChildAt(0))?.rotation = angleDeg + 90f
    }

    // ---------- move handle (center) ----------

    private fun makeHandleView(colorArgb: Int): View {
        val v = View(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorArgb)
            setStroke(3, Color.WHITE)
        }
        v.background = bg
        return v
    }

    private fun addMoveHandle() {
        val handle = makeHandleView(0x99333333.toInt())
        moveHandle = handle

        val params = WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (centerX - handleSizePx / 2f).toInt()
        params.y = (centerY - handleSizePx / 2f).toInt()
        moveParams = params

        var startTouchX = 0f
        var startTouchY = 0f
        var startCenterX = 0f
        var startCenterY = 0f
        var downTime = 0L
        val tapDistancePx = 24 * resources.displayMetrics.density

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startCenterX = centerX
                    startCenterY = centerY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    centerX = startCenterX + dx
                    centerY = startCenterY + dy
                    moveParams.x = (centerX - handleSizePx / 2f).toInt()
                    moveParams.y = (centerY - handleSizePx / 2f).toInt()
                    windowManager.updateViewLayout(moveHandle, moveParams)
                    refreshLineVisual()
                    refreshRotateHandlePosition()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDx = event.rawX - startTouchX
                    val totalDy = event.rawY - startTouchY
                    val distance = sqrt(totalDx * totalDx + totalDy * totalDy)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (distance < tapDistancePx && elapsed < 500) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(handle, params)
    }

    // ---------- rotate handle (end tip) ----------

    private fun addRotateHandle() {
        val handle = makeHandleView(0x996699FF.toInt())
        rotateHandle = handle

        val params = WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (endpointX() - handleSizePx / 2f).toInt()
        params.y = (endpointY() - handleSizePx / 2f).toInt()
        rotateParams = params

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - centerX
                    val dy = event.rawY - centerY
                    angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    refreshRotateHandlePosition()
                    refreshLineVisual()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        windowManager.addView(handle, params)
    }

    private fun refreshRotateHandlePosition() {
        rotateParams.x = (endpointX() - handleSizePx / 2f).toInt()
        rotateParams.y = (endpointY() - handleSizePx / 2f).toInt()
        rotateHandle?.let { windowManager.updateViewLayout(it, rotateParams) }
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
        lineWindow?.let { windowManager.removeView(it) }
        lineWindow = null
        moveHandle?.let { windowManager.removeView(it) }
        moveHandle = null
        rotateHandle?.let { windowManager.removeView(it) }
        rotateHandle = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        lineWindow?.let { windowManager.removeView(it) }
        moveHandle?.let { windowManager.removeView(it) }
        rotateHandle?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
    }
}
