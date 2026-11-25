package com.bodycam.tracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Cliente del endpoint POST /api/reconocimiento del backend. */
object ReconocimientoClient {

    private const val MAX_LADO_PX = 1280

    /**
     * Normaliza la foto: aplica la rotación EXIF (el backend de biometría la ignora),
     * la reduce a un máximo de 1280 px y la guarda como JPEG en [destino].
     */
    fun prepararFoto(original: File, destino: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.absolutePath, bounds)

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LADO_PX) sample *= 2

        val bmp = BitmapFactory.decodeFile(
            original.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalStateException("No se pudo leer la foto")

        val grados = when (
            ExifInterface(original.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val final = if (grados != 0f) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(grados) }, true)
        } else bmp

        destino.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (final !== bmp) final.recycle()
        bmp.recycle()
    }

    /** Envía la foto como multipart/form-data (campo "foto"). Devuelve (código HTTP, JSON). */
    fun enviarFoto(context: Context, foto: File): Pair<Int, JSONObject> {
        val boundary = "----BodycamBoundary${System.currentTimeMillis()}"
        val conn = Red.abrir(context, URL("${BuildConfig.SERVER_URL}/api/reconocimiento"))
        try {
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                setChunkedStreamingMode(0)
                connectTimeout = 10000
                readTimeout = 45000 // el backend espera hasta 30 s a biometría
            }

            conn.outputStream.use { out ->
                out.write(
                    ("--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"foto\"; filename=\"foto.jpg\"\r\n" +
                        "Content-Type: image/jpeg\r\n\r\n").toByteArray()
                )
                foto.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val texto = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(texto) } catch (e: Exception) { JSONObject() }
            return code to json
        } finally {
            conn.disconnect()
        }
    }

    /** Texto legible del resultado para mostrar al usuario. */
    fun mensaje(codigo: Int, json: JSONObject): String = when {
        codigo !in 200..299 -> "Error (HTTP $codigo): ${json.optString("error", "sin detalle")}"
        json.optBoolean("reconocido") -> {
            val p = json.optJSONObject("persona") ?: JSONObject()
            "✅ Persona reconocida\n\n" +
                "Nombre: ${p.optString("nombre_completo", "-")}\n" +
                "DNI: ${p.optString("dni", "-")}\n" +
                "ID empleado: ${p.optString("empleado_id", "-")}\n" +
                "Similitud: ${json.optString("similitud", "-")}%"
        }
        else -> "❌ Persona no reconocida\n\n" +
            json.optString("mensaje").ifEmpty { json.optString("motivo", "") }
    }
}
