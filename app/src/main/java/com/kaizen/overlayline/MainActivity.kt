package com.kaizen.overlayline

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val overlayPermissionCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val actionButton = findViewById<Button>(R.id.actionButton)

        fun refreshUi() {
            if (Settings.canDrawOverlays(this)) {
                statusText.text = "Izin overlay aktif. Tap tombol untuk mulai."
                actionButton.text = "Start Overlay"
            } else {
                statusText.text = "Butuh izin \"Display over other apps\" dulu."
                actionButton.text = "Berikan Izin"
            }
        }

        refreshUi()

        actionButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, overlayPermissionCode)
            } else {
                val serviceIntent = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayPermissionCode) {
            recreate()
        }
    }
}
