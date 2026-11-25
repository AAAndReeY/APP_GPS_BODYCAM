package com.bodycam.tracker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pantalla de validación facial.
 *
 * Modo 1 (normal): cámara propia con CameraX → Capturar → valida.
 * Modo 2 (bodycam): si la cámara está ocupada por la app grabadora del equipo
 * (caso de las bodycams Hytera/Meige), abre la cámara de la grabadora; al pulsar el
 * botón físico de foto, la grabadora guarda la imagen en DCIM, la app la detecta,
 * vuelve al frente y la valida automáticamente.
 */
class ValidarActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvEstado: TextView
    private lateinit var tvGuia: TextView
    private lateinit var btnCapturar: Button
    private lateinit var btnGirar: Button

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var usarFrontal = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Modo bodycam
    private var modoBodycam = false
    private var vigilarJob: Job? = null
    private var mensajePendiente: String? = null
    private var enPrimerPlano = false

    private companion object {
        const val GUIA_PASOS =
            "Esta bodycam usa su propia cámara.\n\n" +
            "1. Tome la foto con el BOTÓN DE FOTO de la bodycam.\n" +
            "2. Espere: la app validará sola."
    }

    private val pedirPermisoCamara =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) iniciarCamara()
            else activarModoBodycam("Sin permiso de cámara propia.")
        }

    private val pedirPermisoAlmacenamiento =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) abrirCamaraBodycam()
            else tvEstado.text = "Se necesita permiso de almacenamiento para leer la foto"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_validar)

        previewView = findViewById(R.id.previewView)
        // TextureView: más compatible con cámaras de equipos industriales/antiguos
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        tvEstado = findViewById(R.id.tvEstadoCamara)
        tvGuia = findViewById(R.id.tvGuia)
        btnCapturar = findViewById(R.id.btnCapturar)
        btnGirar = findViewById(R.id.btnGirar)

        findViewById<Button>(R.id.btnVolver).setOnClickListener { finish() }
        btnCapturar.setOnClickListener { if (modoBodycam) abrirCamaraBodycam() else capturar() }
        btnGirar.setOnClickListener {
            usarFrontal = !usarFrontal
            vincularCamara()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara()
        } else {
            pedirPermisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    // ─── Modo 1: cámara propia (CameraX) ──────────────────────────────────────

    private fun iniciarCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                cameraProvider = futuro.get()
                vincularCamara()
            } catch (e: Exception) {
                activarModoBodycam("No se pudo iniciar la cámara.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun vincularCamara() {
        val provider = cameraProvider ?: return
        if (modoBodycam) return

        fun tiene(selector: CameraSelector) = try { provider.hasCamera(selector) } catch (e: Exception) { false }

        var selector = if (usarFrontal) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!tiene(selector)) {
            usarFrontal = !usarFrontal
            selector = if (usarFrontal) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (!tiene(selector)) {
            activarModoBodycam("Sin cámara disponible para la app.")
            return
        }

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val captura = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, selector, preview, captura)
            imageCapture = captura
            btnCapturar.isEnabled = true
            btnGirar.isEnabled = true
            tvEstado.text = "Encuadre el rostro y pulse Capturar"

            // Si otra app (la grabadora de la bodycam) tiene la cámara, pasar al modo bodycam
            camera.cameraInfo.cameraState.observe(this) { state ->
                val codigo = state.error?.code ?: return@observe
                if (codigo == CameraState.ERROR_CAMERA_IN_USE ||
                    codigo == CameraState.ERROR_MAX_CAMERAS_IN_USE ||
                    codigo == CameraState.ERROR_CAMERA_DISABLED ||
                    codigo == CameraState.ERROR_CAMERA_FATAL_ERROR
                ) {
                    activarModoBodycam("La cámara está ocupada por la grabadora del equipo.")
                }
            }
        } catch (e: Exception) {
            activarModoBodycam("No se pudo abrir la cámara.")
        }
    }

    private fun capturar() {
        val captura = imageCapture ?: return
        bloquear(true, "Capturando...")

        val original = File(cacheDir, "captura.jpg")
        val opciones = ImageCapture.OutputFileOptions.Builder(original).build()

        captura.takePicture(
            opciones,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    validar(original)
                }

                override fun onError(exc: ImageCaptureException) {
                    bloquear(false, "Error al capturar: ${exc.message}")
                }
            }
        )
    }

    // ─── Modo 2: cámara de la grabadora de la bodycam ─────────────────────────

    private fun activarModoBodycam(motivo: String) {
        if (modoBodycam) return
        modoBodycam = true
        try { cameraProvider?.unbindAll() } catch (e: Exception) { /* ignorar */ }
        previewView.visibility = View.GONE
        btnGirar.visibility = View.GONE
        btnCapturar.text = "Otra foto"
        btnCapturar.isEnabled = true
        tvGuia.visibility = View.VISIBLE
        tvGuia.text = GUIA_PASOS
        tvEstado.text = "Abriendo la cámara de la bodycam..."
        abrirCamaraBodycam()
    }

    private fun abrirCamaraBodycam() {
        val permiso = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
            pedirPermisoAlmacenamiento.launch(permiso)
            return
        }

        val antes = fotosActuales()
        try {
            startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        } catch (e: ActivityNotFoundException) {
            tvEstado.text = "Este equipo no tiene app de cámara"
            return
        }

        tvGuia.text = GUIA_PASOS
        tvEstado.text = "Esperando la foto..."
        vigilarJob?.cancel()
        vigilarJob = scope.launch { esperarFotoNueva(antes) }
    }

    /** Espera (hasta 3 min) a que aparezca un archivo de imagen nuevo en las carpetas de fotos. */
    private suspend fun esperarFotoNueva(antes: Set<String>) {
        val limite = System.currentTimeMillis() + 3 * 60 * 1000L
        var candidata: File? = null
        var tamanoAnterior = -1L
        var avisada = false

        while (scope.isActive && System.currentTimeMillis() < limite) {
            delay(500)
            val nueva = (fotosActuales() - antes).map { File(it) }.maxByOrNull { it.lastModified() }
            if (nueva == null) continue

            // En cuanto aparece la foto: volver a la app ya, aunque la grabadora siga escribiéndola
            if (!avisada) {
                avisada = true
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    traerAlFrente()
                    tvGuia.text = "📷 Foto recibida\n\nProcesando, espere..."
                    tvEstado.text = "Foto recibida. Procesando..."
                }
            }

            val tamano = nueva.length()
            // la foto está completa cuando su tamaño deja de cambiar
            if (tamano > 0 && nueva == candidata && tamano == tamanoAnterior) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    validar(nueva)
                }
                return
            }
            candidata = nueva
            tamanoAnterior = tamano
        }

        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                tvEstado.text = "No se recibió foto. Pulse Abrir cámara."
            }
        }
    }

    /** Carpetas donde las cámaras suelen guardar fotos (memoria interna y tarjeta SD). */
    private fun carpetasDeFotos(): List<File> {
        val raices = mutableSetOf<File>(Environment.getExternalStorageDirectory())
        // volúmenes externos (tarjeta SD) a partir de las carpetas propias de la app
        getExternalFilesDirs(null).filterNotNull().forEach {
            val ruta = it.absolutePath.substringBefore("/Android/data")
            if (ruta != it.absolutePath) raices.add(File(ruta))
        }
        return raices.flatMap { r ->
            listOf("DCIM/Photo", "DCIM/Camera", "DCIM", "Pictures").map { File(r, it) }
        }.filter { it.isDirectory }
    }

    private fun fotosActuales(): Set<String> = carpetasDeFotos().flatMap { dir ->
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.toList().orEmpty()
    }.map { it.absolutePath }.toSet()

    /** Devuelve esta pantalla al frente (la cámara de la grabadora quedó encima). */
    private fun traerAlFrente() {
        try {
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (e: Exception) { /* sin permiso: se intenta con startActivity */ }
        try {
            startActivity(
                Intent(this, ValidarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) { /* el usuario puede volver manualmente */ }
    }

    // ─── Validación (común a ambos modos) ─────────────────────────────────────

    private fun validar(original: File) {
        tvEstado.text = "Validando persona..."
        if (modoBodycam) tvGuia.text = "🔎 Validando persona...\n\nEspere un momento"
        btnCapturar.isEnabled = false
        scope.launch {
            var fallo: Throwable? = null
            val resultado: Pair<Int, org.json.JSONObject>? = try {
                val reducida = File(cacheDir, "validar.jpg")
                ReconocimientoClient.prepararFoto(original, reducida)
                ReconocimientoClient.enviarFoto(this@ValidarActivity, reducida)
            } catch (e: Throwable) {
                fallo = e
                android.util.Log.e("ValidarActivity", "Fallo al validar la foto", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                val mensaje = if (resultado == null) {
                    val detalle = "${fallo?.javaClass?.simpleName}: ${fallo?.message}"
                    when (fallo) {
                        is java.net.SocketTimeoutException ->
                            "El servidor tardó demasiado en responder.\n\n$detalle"
                        is java.io.IOException ->
                            "No se pudo conectar con el servidor. Verifique la conexión.\n\n$detalle"
                        else -> "No se pudo procesar la foto.\n\n$detalle"
                    }
                } else {
                    ReconocimientoClient.mensaje(resultado.first, resultado.second)
                }
                btnCapturar.isEnabled = true
                if (modoBodycam) {
                    tvGuia.text = "Listo.\n\nPulse «Otra foto» para validar a otra persona"
                    tvEstado.text = "Validación terminada"
                } else {
                    tvEstado.text = "Encuadre el rostro y pulse Capturar"
                }
                mostrarResultado(mensaje)
            }
        }
    }

    private fun mostrarResultado(mensaje: String) {
        // Si la pantalla aún no está visible (volviendo de la cámara), se muestra en onResume
        if (!enPrimerPlano) {
            mensajePendiente = mensaje
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Validar persona")
            .setMessage(mensaje)
            .setPositiveButton("Aceptar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        mensajePendiente?.let {
            mensajePendiente = null
            mostrarResultado(it)
        }
    }

    override fun onPause() {
        super.onPause()
        enPrimerPlano = false
    }

    private fun bloquear(bloqueado: Boolean, estado: String) {
        btnCapturar.isEnabled = !bloqueado
        btnGirar.isEnabled = !bloqueado
        tvEstado.text = estado
    }

    override fun onDestroy() {
        super.onDestroy()
        vigilarJob?.cancel()
        scope.cancel()
    }
}
