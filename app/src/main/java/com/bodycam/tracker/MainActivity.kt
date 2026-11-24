package com.bodycam.tracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_LOCATION_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("bodycam_config", Context.MODE_PRIVATE)

        val etNombre   = findViewById<EditText>(R.id.etNombre)
        val tvCodigo   = findViewById<TextView>(R.id.tvCodigo)
        val btnGuardar = findViewById<Button>(R.id.btnGuardar)
        val btnIniciar = findViewById<Button>(R.id.btnIniciar)
        val btnDetener = findViewById<Button>(R.id.btnDetener)
        val tvEstado   = findViewById<TextView>(R.id.tvEstado)

        // Android ID como código único del dispositivo
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        tvCodigo.text = "ID dispositivo: $androidId"

        // Cargar configuración guardada
        val nombreGuardado = prefs.getString("bodycam_nombre", "")
        etNombre.setText(nombreGuardado)
        
        // Bloquear si ya tiene nombre y auto-iniciar
        if (!nombreGuardado.isNullOrEmpty()) {
            etNombre.isEnabled = false
            btnGuardar.visibility = android.view.View.GONE
            
            tvEstado.text = "Estado: Buscando GPS..."
            pedirPermisosEIniciar()
        }

        // Gesto secreto: Mantener presionado el ID para desbloquear configuración
        tvCodigo.setOnLongClickListener {
            etNombre.isEnabled = true
            btnGuardar.visibility = android.view.View.VISIBLE
            Toast.makeText(this, "Modo configuración desbloqueado 🔓", Toast.LENGTH_SHORT).show()
            true
        }

        btnGuardar.setOnClickListener {
            val nombre    = etNombre.text.toString().trim()
            val serverUrl = BuildConfig.SERVER_URL
            val token     = BuildConfig.API_TOKEN

            if (nombre.isEmpty()) {
                Toast.makeText(this, "El nombre de la bodycam es obligatorio", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("bodycam_codigo", androidId)
                putString("bodycam_nombre", nombre)
                putString("server_url", serverUrl)
                putString("token", token)
                apply()
            }

            Toast.makeText(this, "Configuración guardada localmente ✅", Toast.LENGTH_SHORT).show()
            tvEstado.text = "Estado: Conectando para registrar cámara..."
            
            // Bloquear de nuevo después de guardar
            etNombre.isEnabled = false
            btnGuardar.visibility = android.view.View.GONE

            registrarBodycamEnServidor(serverUrl, token, androidId, nombre)
        }

        btnIniciar.setOnClickListener {
            if (!prefs.contains("bodycam_codigo")) {
                Toast.makeText(this, "Primero guarda la configuración", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pedirPermisosEIniciar()
        }

        btnDetener.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            tvEstado.text = "Estado: Servicio detenido ⏹"
        }

        findViewById<Button>(R.id.btnValidar).setOnClickListener {
            startActivity(Intent(this, ValidarActivity::class.java))
        }
    }

    private fun registrarBodycamEnServidor(serverUrl: String, token: String, codigo: String, nombre: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/bodycams/registrar")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val body = JSONObject().apply {
                    put("codigo", codigo)
                    put("nombre", nombre)
                }.toString()

                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    val tvEstado = findViewById<TextView>(R.id.tvEstado)
                    if (code == 200 || code == 201) {
                        tvEstado.text = "Estado: Cámara registrada ✅. Buscando GPS..."
                        pedirPermisosEIniciar()
                    } else {
                        tvEstado.text = "Estado: Error al registrar (Código HTTP $code) ❌ Verifique URL/Token."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.tvEstado).text = "Estado: Error de red al intentar registrar cámara ❌ Verifique conexión/IP."
                }
            }
        }
    }

    private fun pedirPermisosEIniciar() {
        val permisos = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (faltantes.isEmpty()) {
            iniciarServicio()
        } else {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_LOCATION_PERMISSION)
        }
    }

    private fun iniciarServicio() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        findViewById<TextView>(R.id.tvEstado).text = "Estado: ✅ Activo - Buscando GPS..."
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            findViewById<TextView>(R.id.tvEstado).text = "Estado: $status"
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.bodycam.tracker.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                iniciarServicio()
            } else {
                Toast.makeText(this, "Se necesitan permisos de ubicación", Toast.LENGTH_LONG).show()
            }
        }
    }
}