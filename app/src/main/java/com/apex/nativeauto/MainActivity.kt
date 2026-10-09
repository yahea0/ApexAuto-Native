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
import com.apex.nativeauto.adb.AdbMdnsDiscovery
import com.apex.nativeauto.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var adbClient: AdbClient
    private lateinit var mdnsDiscovery: AdbMdnsDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adbClient = AdbClient(this)
        mdnsDiscovery = AdbMdnsDiscovery(this)

        val editPairPort = findViewById<EditText>(R.id.edit_pair_port)
        val editPairCode = findViewById<EditText>(R.id.edit_pair_code)
        val btnPair = findViewById<Button>(R.id.btn_pair)
        val btnStartOverlay = findViewById<Button>(R.id.btn_start_overlay)

        // البحث التلقائي: إذا وجد المنفذ يملأ الخانة تلقائياً
        mdnsDiscovery.discoverServices(object : AdbMdnsDiscovery.DiscoveryCallback {
            override fun onPortDiscovered(port: Int, isPairingService: Boolean) {
                runOnUiThread {
                    if (isPairingService && editPairPort.text.isEmpty()) {
                        editPairPort.setText(port.toString())
                        Toast.makeText(this@MainActivity, "تم اكتشاف منفذ الإقران تلقائياً: $port", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        btnPair.setOnClickListener {
            val portText = editPairPort.text.toString().trim()
            val codeText = editPairCode.text.toString().trim()

            if (portText.isEmpty() || codeText.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال كل من المنفذ (Port) والرمز المكون من 6 أرقام", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val port = portText.toIntOrNull()
            if (port == null) {
                Toast.makeText(this, "رقم المنفذ غير صحيح", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scope.launch {
                btnPair.isEnabled = false
                btnPair.text = "جاري الاقتران..."

                val result = adbClient.pair(port, codeText)
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()

                btnPair.isEnabled = true
                btnPair.text = "⚡ اقتران وتشغيل الخادم الأصلي (Pair & Deploy)"
            }
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
                Toast.makeText(this, "تم تشغيل الأيقونة العائمة بنجاح", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
