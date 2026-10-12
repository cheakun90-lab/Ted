package com.ted.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ted", MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ted_neutral)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad * 8)
        }
        val title = TextView(this).apply {
            text = "Ted, your little assistant"
            textSize = 22f
        }
        val keyBox = EditText(this).apply {
            hint = "API key (optional, leave empty for offline Ted)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("apikey", ""))
        }
        val permBtn = Button(this).apply { text = "1. Allow Ted to appear over other apps" }
        val startBtn = Button(this).apply { text = "2. Wake Ted up" }
        val stopBtn = Button(this).apply { text = "Send Ted to sleep" }

        permBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else toast("Already allowed")
        }
        startBtn.setOnClickListener {
            prefs.edit().putString("apikey", keyBox.text.toString().trim()).apply()
            if (!Settings.canDrawOverlays(this)) {
                toast("Do step 1 first"); return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, OverlayService::class.java))
            toast("Ted is awake. Tap him to chat.")
        }
        stopBtn.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }

        listOf(img, title, keyBox, permBtn, startBtn, stopBtn).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
