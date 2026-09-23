package com.monitor.screencast

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val REQ_CAST = 1001
    }

    private lateinit var ipEdit: EditText
    private lateinit var fpsEdit: EditText
    private lateinit var qEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var btn: Button
    private var casting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipEdit = findViewById(R.id.ip)
        fpsEdit = findViewById(R.id.fps)
        qEdit = findViewById(R.id.q)
        statusView = findViewById(R.id.status)
        btn = findViewById(R.id.toggle)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 50)
        }

        val prefs = getPreferences(MODE_PRIVATE)
        ipEdit.setText(prefs.getString("ip", "192.168.1.102"))
        fpsEdit.setText(prefs.getString("fps", "10"))
        qEdit.setText(prefs.getString("q", "60"))

        btn.setOnClickListener {
            if (!casting) {
                prefs.edit()
                    .putString("ip", ipEdit.text.toString().trim())
                    .putString("fps", fpsEdit.text.toString().trim())
                    .putString("q", qEdit.text.toString().trim())
                    .apply()
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAST)
            } else {
                stopService(Intent(this, CastService::class.java))
                casting = false
                btn.text = "START CASTING"
                statusView.text = "Stopped"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAST) return

        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CastService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
                .putExtra("ip", ipEdit.text.toString().trim())
                .putExtra("fps", fpsEdit.text.toString().trim().toIntOrNull() ?: 10)
                .putExtra("quality", qEdit.text.toString().trim().toIntOrNull() ?: 60)
            startForegroundService(intent)
            casting = true
            btn.text = "STOP CASTING"
            statusView.text = "Casting... check notification"
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
        }
    }
}