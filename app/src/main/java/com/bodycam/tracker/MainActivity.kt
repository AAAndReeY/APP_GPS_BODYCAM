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

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_LOCATION_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("bodycam_config", Context.MODE_PRIVATE)

        val etNombre   = findViewById<EditText>(R.id.etNombre)
        val etServerUrl = findViewById<EditText>(R.id.etServerUrl)
        val etToken    = findViewById<EditText>(R.id.etToken)
        val tvCodigo   = findViewById<TextView>(R.id.tvCodigo)
        val btnGuardar = findViewById<Button>(R.id.btnGuardar)
        val btnIniciar = findViewById<Button>(R.id.btnIniciar)
        val btnDetener = findViewById<Button>(R.id.btnDetener)
        val tvEstado   = findViewById<TextView>(R.id.tvEstado)

        // Android ID como código único del dispositivo
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        tvCodigo.text = "ID dispositivo: $androidId"

        // Cargar configuración guardada
        etNombre.setText(prefs.getString("bodycam_nombre", ""))
        etServerUrl.setText(prefs.getString("server_url", "http://192.168.1.100:3000"))
        etToken.setText(prefs.getString("token", ""))

        btnGuardar.setOnClickListener {
            val nombre    = etNombre.text.toString().trim()
            val serverUrl = etServerUrl.text.toString().trim()
            val token     = etToken.text.toString().trim()

            if (nombre.isEmpty() || serverUrl.isEmpty()) {
                Toast.makeText(this, "Nombre y URL del servidor son obligatorios", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("bodycam_codigo", androidId)
                putString("bodycam_nombre", nombre)
                putString("server_url", serverUrl)
                putString("token", token)
                apply()
            }

            Toast.makeText(this, "Configuración guardada ✅", Toast.LENGTH_SHORT).show()
            tvEstado.text = "Estado: Configurado. Listo para iniciar."
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
        findViewById<TextView>(R.id.tvEstado).text = "Estado: ✅ Activo - Enviando ubicación"
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