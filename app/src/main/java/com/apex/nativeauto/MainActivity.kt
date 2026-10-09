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
    private var detectedPort = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adbClient = AdbClient(this)
        mdnsDiscovery = AdbMdnsDiscovery(this)

        val editPairCode = findViewById<EditText>(R.id.edit_pair_code)
        val btnPair = findViewById<Button>(R.id.btn_pair)
        val btnStartOverlay = findViewById<Button>(R.id.btn_start_overlay)

        mdnsDiscovery.discoverServices(object : AdbMdnsDiscovery.DiscoveryCallback {
            override fun onPortDiscovered(port: Int, isPairingService: Boolean) {
                detectedPort = port
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "تم اكتشاف منفذ التصحيح: $port", Toast.LENGTH_SHORT).show()
                }
            }
        })

        btnPair.setOnClickListener {
            val code = editPairCode.text.toString().trim()
            if (code.isNotEmpty() && detectedPort != 0) {
                scope.launch {
                    val success = adbClient.pair(detectedPort, code)
                    if (success) {
                        Toast.makeText(this@MainActivity, "تم الاقتران وتشغيل المحرك بنجاح!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "فشل الاقتران، تأكد من الرمز", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "يرجى كتابة رمز الاقتران أو التأكد من تفعيل التصحيح اللاسلكي", Toast.LENGTH_SHORT).show()
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
            }
        }
    }
}
