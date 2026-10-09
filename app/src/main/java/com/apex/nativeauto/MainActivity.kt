package com.apex.nativeauto

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.apex.nativeauto.adb.AdbClient
import com.apex.nativeauto.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var adbClient: AdbClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adbClient = AdbClient(this)

        val btnAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnStartOverlay = findViewById<Button>(R.id.btn_start_overlay)
        val editPairPort = findViewById<EditText>(R.id.edit_pair_port)
        val editPairCode = findViewById<EditText>(R.id.edit_pair_code)
        val btnPair = findViewById<Button>(R.id.btn_pair)

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "ابحث عن ApexAuto Native في القائمة وقم بتفعيلها", Toast.LENGTH_LONG).show()
        }

        btnStartOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "تم تشغيل لوحة الأتمتة العائمة!", Toast.LENGTH_SHORT).show()
            }
        }

        btnPair.setOnClickListener {
            val portText = editPairPort.text.toString().trim()
            val codeText = editPairCode.text.toString().trim()

            val port = portText.toIntOrNull()
            if (port != null && codeText.isNotEmpty()) {
                scope.launch {
                    val res = adbClient.pair(port, codeText)
                    Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "أدخل المنفذ والرمز بشكل صحيح", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
