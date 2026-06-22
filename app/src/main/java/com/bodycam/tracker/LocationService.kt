package com.bodycam.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LocationService : Service() {

    private lateinit var locationManager: android.location.LocationManager

    private lateinit var locationListener: android.location.LocationListener
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "bodycam_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "LocationService"
        const val INTERVALO_MS = 10_000L // Intervalo cambiado a 10 segundos
        const val INTERVALO_MINIMO_MS = 5_000L
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        crearCanalNotificacion()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BodycamTracker::LocationWakeLock"
        )
        wakeLock.acquire(24 * 60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, construirNotificacion())
        sendStatusUpdate("Iniciando servicio de GPS...")
        iniciarActualizacionesGPS()
        Log.d(TAG, "Servicio GPS iniciado")
        return START_STICKY
    }

    private fun sendStatusUpdate(message: String) {
        val intent = Intent("com.bodycam.tracker.STATUS_UPDATE")
        intent.putExtra("status", message)
        sendBroadcast(intent)
    }

    @Suppress("DEPRECATION")
    private fun iniciarActualizacionesGPS() {
        locationListener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                val msg = "GPS recibido: ${location.latitude}, ${location.longitude}"
                Log.d(TAG, msg)
                sendStatusUpdate(msg)
                serviceScope.launch {
                    enviarUbicacion(location.latitude, location.longitude, location.accuracy)
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "Proveedor activado: $provider")
                sendStatusUpdate("Proveedor activado: $provider")
            }
            override fun onProviderDisabled(provider: String) {
                Log.e(TAG, "Proveedor GPS desactivado: $provider")
                sendStatusUpdate("GPS DESACTIVADO: $provider")
            }
        }

        try {
            val proveedores = listOf(
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.GPS_PROVIDER
            )
            var registrado = false
            for (proveedor in proveedores) {
                if (locationManager.isProviderEnabled(proveedor)) {
                    locationManager.requestLocationUpdates(
                        proveedor,
                        INTERVALO_MS,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                    Log.d(TAG, "Registrado con proveedor: $proveedor")
                    registrado = true
                }
            }
            if (!registrado) {
                Log.e(TAG, "Ningún proveedor disponible")
                sendStatusUpdate("ERROR: GPS o Red no disponibles")
            } else {
                sendStatusUpdate("Buscando señal GPS...")
            }

            val ultimaGPS = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val ultimaRed = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            Log.d(TAG, "Última GPS conocida: $ultimaGPS")
            Log.d(TAG, "Última RED conocida: $ultimaRed")

        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permiso: ${e.message}")
        }
    }

    private fun guardarPendiente(lat: Double, lng: Double, precision: Float) {
        val prefs = getSharedPreferences("bodycam_config", Context.MODE_PRIVATE)
        val pendientesJson = prefs.getString("pendientes", "[]")
        val pendientes = org.json.JSONArray(pendientesJson)

        val item = JSONObject().apply {
            put("latitud", lat)
            put("longitud", lng)
            put("precision_m", precision)
            put("timestamp", System.currentTimeMillis())
        }
        pendientes.put(item)

        val limite = if (pendientes.length() > 100) {
            org.json.JSONArray().also { nuevo ->
                for (i in pendientes.length() - 100 until pendientes.length()) {
                    nuevo.put(pendientes.get(i))
                }
            }
        } else pendientes

        prefs.edit().putString("pendientes", limite.toString()).apply()
        Log.d(TAG, "Guardado offline. Pendientes: ${limite.length()}")
    }

    private suspend fun enviarUbicacion(lat: Double, lng: Double, precision: Float) {
        val prefs = getSharedPreferences("bodycam_config", Context.MODE_PRIVATE)
        val codigo    = prefs.getString("bodycam_codigo", null) ?: return
        val serverUrl = prefs.getString("server_url", null) ?: return
        val token     = prefs.getString("token", "") ?: ""

        enviarPendientes(prefs, serverUrl, token, codigo)

        val exito = enviarCoordenada(serverUrl, token, codigo, lat, lng, precision)
        if (!exito) {
            guardarPendiente(lat, lng, precision)
            sendStatusUpdate("Fallo envío, guardado local. Total: \${obtenerCantidadPendientes()}")
        } else {
            sendStatusUpdate("Enviado correctamente (Precisión: \${precision}m)")
        }
    }

    private fun obtenerCantidadPendientes(): Int {
        val prefs = getSharedPreferences("bodycam_config", Context.MODE_PRIVATE)
        val json = prefs.getString("pendientes", "[]")
        return org.json.JSONArray(json).length()
    }

    private suspend fun enviarPendientes(
        prefs: android.content.SharedPreferences,
        serverUrl: String,
        token: String,
        codigo: String
    ) {
        val pendientesJson = prefs.getString("pendientes", "[]")
        val pendientes = org.json.JSONArray(pendientesJson)
        if (pendientes.length() == 0) return

        Log.d(TAG, "Enviando ${pendientes.length()} pendientes...")
        val enviados = mutableListOf<Int>()

        for (i in 0 until pendientes.length()) {
            val item = pendientes.getJSONObject(i)
            val exito = enviarCoordenada(
                serverUrl, token, codigo,
                item.getDouble("latitud"),
                item.getDouble("longitud"),
                item.getDouble("precision_m").toFloat()
            )
            if (exito) enviados.add(i) else break
        }

        if (enviados.isNotEmpty()) {
            val restantes = org.json.JSONArray()
            for (i in 0 until pendientes.length()) {
                if (i !in enviados) restantes.put(pendientes.get(i))
            }
            prefs.edit().putString("pendientes", restantes.toString()).apply()
            Log.d(TAG, "Enviados ${enviados.size} pendientes. Quedan: ${restantes.length()}")
        }
    }

    private suspend fun enviarCoordenada(
        serverUrl: String,
        token: String,
        codigo: String,
        lat: Double,
        lng: Double,
        precision: Float
    ): Boolean {
        return try {
            val url = URL("$serverUrl/api/ubicacion")
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
                put("latitud", lat)
                put("longitud", lng)
                put("precision_m", precision)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            (code == 200 || code == 201)
        } catch (e: Exception) {
            Log.e(TAG, "Error de red: \${e.message}")
            sendStatusUpdate("Error de red: \${e.message}")
            false
        }
    }

    private fun construirNotificacion(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bodycam activa")
            .setContentText("Rastreo GPS en ejecución")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreo GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de ubicación en segundo plano"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
        locationManager.removeUpdates(locationListener)
        serviceScope.cancel()
        Log.d(TAG, "Servicio detenido")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}