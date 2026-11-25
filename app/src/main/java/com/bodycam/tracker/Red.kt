package com.bodycam.tracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Abre conexiones HTTP eligiendo la red correcta.
 *
 * Las bodycams tienen Wi-Fi y datos móviles a la vez y Android alterna entre ambas como red
 * principal. Un servidor en la red local (192.168.x.x, 10.x.x.x...) solo se alcanza por el
 * Wi-Fi: si el tráfico sale por datos móviles falla con "No route to host".
 */
object Red {

    fun abrir(context: Context, url: URL): HttpURLConnection {
        if (esRedLocal(url.host)) {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi != null) return wifi.openConnection(url) as HttpURLConnection
        }
        return url.openConnection() as HttpURLConnection
    }

    private fun esRedLocal(host: String): Boolean {
        val p = host.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31)
    }
}
