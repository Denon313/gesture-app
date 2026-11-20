package com.denon.gesture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Air Gesture"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        val b1 = Button(this).apply {
            text = "1) Enable Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val b2 = Button(this).apply {
            text = "2) Start"
            setOnClickListener { askPermissionsThenStart() }
        }

        val b3 = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@MainActivity, GestureService::class.java).apply {
                    action = GestureService.ACTION_STOP
                })
            }
        }

        layout.addView(title)
        layout.addView(b1)
        layout.addView(b2)
        layout.addView(b3)
        setContentView(layout)
    }

    private fun askPermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startGestureService()
        else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startGestureService()
        }
    }

    private fun startGestureService() {
        startForegroundService(Intent(this, GestureService::class.java))
    }
}
