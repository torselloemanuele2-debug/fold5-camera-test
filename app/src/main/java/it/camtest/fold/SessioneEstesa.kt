package it.camtest.fold

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Anteprima e scatto con l'elaborazione del produttore del telefono (Camera Extensions):
 * BOKEH (sfocatura dello sfondo vera) e AUTOMATICO (HDR o notte scelti dal telefono).
 *
 * Tutti i metodi che attendono (apri, scatta, chiudi) vanno chiamati dal thread di lavoro;
 * i callback arrivano su [esecutore], che deve essere un thread DIVERSO.
 */
class SessioneEstesa(
    private val cam: CameraDevice,
    private val gestore: CameraManager,
    private val idCamera: String,
    private val esecutore: Executor,
    private val gestoreCallback: Handler
) {
    private var sessione: CameraExtensionSession? = null
    private var lettore: ImageReader? = null
    private var chiusura: CountDownLatch? = null
    private var richiestaAnteprima: CaptureRequest.Builder? = null
    private val immagini = LinkedBlockingQueue<ByteArray>()

    var estensione: Int = -1
        private set
    var zoomUsato: Float? = null
        private set
    var luceSupportata: Boolean = false
        private set

    // Callback "vuoto": per l'anteprima non serve ascoltare nulla.
    private val ascoltoAnteprima = object : CameraExtensionSession.ExtensionCaptureCallback() {}

    /** Risoluzione dell'anteprima: la più alta (fino a 1920) con proporzioni 4:3. */
    fun tagliaAnteprima(estensione: Int): Size? {
        val elenco = gestore.getCameraExtensionCharacteristics(idCamera)
            .getExtensionSupportedSizes(estensione, SurfaceTexture::class.java)
            .filter { it.width <= 1920 }
        return elenco
            .filter { kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.05 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: elenco.maxByOrNull { it.width.toLong() * it.height }
    }

    /** Vero se questa estensione accetta la chiave [chiave] nelle richieste (verificabile da Android 13). */
    fun supporta(estensione: Int, chiave: CaptureRequest.Key<*>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            gestore.getCameraExtensionCharacteristics(idCamera)
                .getAvailableCaptureRequestKeys<Any>(estensione)
                .any { it.name == chiave.name }
        } catch (_: Throwable) {
            false
        }
    }

    /** Avvia la sessione e l'anteprima. Restituisce "" se è andato tutto bene, altrimenti il motivo. */
    fun apri(estensione: Int, anteprima: Surface, zoom: Float?, luce: Int?): String {
        val caratteristiche = gestore.getCameraExtensionCharacteristics(idCamera)
        val taglieJpeg = caratteristiche.getExtensionSupportedSizes(estensione, ImageFormat.JPEG)
        val jpeg = taglieJpeg
            .filter { kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.02 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: taglieJpeg.maxByOrNull { it.width.toLong() * it.height }
            ?: return "il telefono non indica nessuna dimensione JPEG per questa modalità"

        val nuovoLettore = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        nuovoLettore.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireNextImage()
                if (img != null) {
                    try {
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        immagini.offer(bytes)
                    } finally {
                        img.close()
                    }
                }
            } catch (_: Throwable) {
            }
        }, gestoreCallback)

        val pronta = CountDownLatch(1)
        val chiusuraNuova = CountDownLatch(1)
        chiusura = chiusuraNuova
        var creata: CameraExtensionSession? = null
        var errore = ""
        val configurazione = ExtensionSessionConfiguration(
            estensione,
            listOf(OutputConfiguration(anteprima), OutputConfiguration(nuovoLettore.surface)),
            esecutore,
            object : CameraExtensionSession.StateCallback() {
                override fun onConfigured(session: CameraExtensionSession) {
                    creata = session
                    pronta.countDown()
                }

                override fun onConfigureFailed(session: CameraExtensionSession) {
                    errore = "il telefono ha rifiutato la configurazione"
                    pronta.countDown()
                }

                override fun onClosed(session: CameraExtensionSession) {
                    chiusuraNuova.countDown()
                }
            }
        )
        try {
            cam.createExtensionSession(configurazione)
        } catch (t: Throwable) {
            errore = t.message ?: t.javaClass.simpleName
            pronta.countDown()
        }
        if (!pronta.await(15, TimeUnit.SECONDS)) {
            nuovoLettore.close()
            return "tempo scaduto in attesa del telefono"
        }
        val s = creata
        if (s == null) {
            nuovoLettore.close()
            return errore.ifEmpty { "motivo sconosciuto" }
        }

        sessione = s
        lettore = nuovoLettore
        this.estensione = estensione
        zoomUsato = zoom
        luceSupportata = supporta(estensione, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)

        return try {
            val richiesta = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            richiesta.addTarget(anteprima)
            if (zoom != null) richiesta.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            if (luce != null && luceSupportata) richiesta.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, luce)
            richiestaAnteprima = richiesta
            s.setRepeatingRequest(richiesta.build(), esecutore, ascoltoAnteprima)
            ""
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    /** Cambia la luce dell'anteprima (se la modalità lo permette). */
    fun aggiornaLuce(luce: Int) {
        if (!luceSupportata) return
        val s = sessione ?: return
        val richiesta = richiestaAnteprima ?: return
        try {
            richiesta.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, luce)
            s.setRepeatingRequest(richiesta.build(), esecutore, ascoltoAnteprima)
        } catch (_: Throwable) {
        }
    }

    /** Scatta una foto e ne restituisce i byte JPEG. Può richiedere diversi secondi (HDR, notte, bokeh). */
    fun scatta(orientamento: Int, luce: Int?, attesaMs: Long): ByteArray {
        val s = sessione ?: throw IllegalStateException("la sessione non è attiva")
        val l = lettore ?: throw IllegalStateException("il lettore delle foto non è attivo")
        immagini.clear()

        val richiesta = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        richiesta.addTarget(l.surface)
        richiesta.set(CaptureRequest.JPEG_ORIENTATION, orientamento)
        richiesta.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        val zoom = zoomUsato
        if (zoom != null) richiesta.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        if (luce != null && luceSupportata) richiesta.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, luce)

        var fallita = false
        val esito = CountDownLatch(1)
        s.capture(richiesta.build(), esecutore, object : CameraExtensionSession.ExtensionCaptureCallback() {
            override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                fallita = true
                esito.countDown()
            }

            override fun onCaptureSequenceAborted(session: CameraExtensionSession, sequenceId: Int) {
                fallita = true
                esito.countDown()
            }

            override fun onCaptureSequenceCompleted(session: CameraExtensionSession, sequenceId: Int) {
                esito.countDown()
            }
        })

        return immagini.poll(attesaMs, TimeUnit.MILLISECONDS)
            ?: throw IllegalStateException(
                if (fallita) "il telefono ha segnalato un errore durante lo scatto"
                else "nessuna immagine ricevuta dal telefono entro ${attesaMs / 1000} secondi"
            )
    }

    fun chiudi() {
        try {
            sessione?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            sessione?.close()
        } catch (_: Throwable) {
        }
        sessione = null
        try {
            chiusura?.await(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
        try {
            lettore?.close()
        } catch (_: Throwable) {
        }
        lettore = null
        richiestaAnteprima = null
    }
}
