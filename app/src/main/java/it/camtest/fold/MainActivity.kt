package it.camtest.fold

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private val report = StringBuilder()
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 64, 24, 24)

        startButton = Button(this)
        startButton.text = "Avvia test fotocamere"
        startButton.setOnClickListener { onStartClicked() }

        val copyButton = Button(this)
        copyButton.text = "Copia report"
        copyButton.setOnClickListener { copyReport() }

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 12f
        logView.setTextIsSelectable(true)
        logView.text = "Premi \"Avvia test fotocamere\". Il test dura circa 1 minuto."

        val scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(startButton)
        root.addView(copyButton)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun onStartClicked() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        startTest()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startTest()
        } else {
            log("Permesso fotocamera negato: senza non si può fare il test.")
        }
    }

    private fun log(s: String) {
        synchronized(report) { report.append(s).append('\n') }
        uiHandler.post {
            logView.text = synchronized(report) { report.toString() }
        }
    }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = synchronized(report) { report.toString() }
        cm.setPrimaryClip(ClipData.newPlainText("report", text))
        Toast.makeText(this, "Report copiato", Toast.LENGTH_SHORT).show()
    }

    private fun startTest() {
        running = true
        startButton.isEnabled = false
        synchronized(report) { report.setLength(0) }
        Thread {
            try {
                runAll()
            } catch (t: Throwable) {
                log("ERRORE GENERALE: $t")
            }
            running = false
            uiHandler.post { startButton.isEnabled = true }
            log("\n=== TEST FINITO ===")
        }.start()
    }

    private fun runAll() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        log("Telefono: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        val ids = manager.cameraIdList
        log("Fotocamere visibili al sistema: ${ids.joinToString()}")

        var backLogical: String? = null
        for (id in ids) {
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "POSTERIORE"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONTALE"
                else -> "ALTRA"
            }
            val phys = c.physicalCameraIds
            log("- id $id: $facing, obiettivi fisici collegati: [${phys.joinToString()}]")
            describe(c, "    ")
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                phys.isNotEmpty() && backLogical == null
            ) {
                backLogical = id
            }
        }

        val backId = backLogical ?: "0"
        val logicalChars = manager.getCameraCharacteristics(backId)
        val physIds = logicalChars.physicalCameraIds.sorted()

        log("\n=== TEST FOTOCAMERA POSTERIORE (id $backId) ===")

        log("\n--- Fotocamera principale (come la vede un'app normale) ---")
        testCapture(manager, backId, null, null, "logica")

        for (pid in physIds) {
            log("\n--- Obiettivo fisico id $pid ---")
            val pc: CameraCharacteristics? = try {
                manager.getCameraCharacteristics(pid)
            } catch (t: Throwable) {
                log("  (il sistema non fornisce i dati di questo obiettivo: $t)")
                null
            }
            if (pc != null) describe(pc, "  ")
            testCapture(manager, backId, pid, pc, "fisica$pid")
        }

        log("\nI file DNG (RAW) sono nella Galleria, cartella Pictures/FoldRawTest.")
    }

    private fun describe(c: CameraCharacteristics, ind: String) {
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        log("${ind}livello accesso: $level")

        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal != null && focal.isNotEmpty()) {
            val f = focal[0]
            var eq = ""
            if (physSize != null) {
                val w = physSize.width.toDouble()
                val h = physSize.height.toDouble()
                val diag = Math.sqrt(w * w + h * h)
                eq = " (equivalente a circa ${Math.round(f * 43.27 / diag)}mm)"
            }
            log("${ind}lunghezza focale: ${f}mm$eq")
        }

        val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (pix != null) log("${ind}sensore: ${pix.width}x${pix.height} pixel")

        val raw = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
        val rawText = if (raw == null || raw.isEmpty()) {
            "non dichiarato"
        } else {
            raw.joinToString { "${it.width}x${it.height}" }
        }
        log("${ind}formati RAW: $rawText")
    }

    private fun testCapture(
        manager: CameraManager,
        logicalId: String,
        physId: String?,
        chars: CameraCharacteristics?,
        label: String
    ) {
        val sizeSource = chars ?: manager.getCameraCharacteristics(logicalId)
        val rawSizes = sizeSource.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes == null || rawSizes.isEmpty()) {
            log("  RAW: nessuna misura dichiarata, salto questo scatto.")
            return
        }
        val size = rawSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return
        log("  Provo uno scatto RAW a ${size.width}x${size.height}...")

        val thread = HandlerThread("cam-$label")
        thread.start()
        val handler = Handler(thread.looper)
        val executor = Executor { r -> handler.post(r) }

        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        try {
            // 1) Apro la fotocamera
            val openLatch = CountDownLatch(1)
            var openError: String? = null
            manager.openCamera(logicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camera = c
                    openLatch.countDown()
                }

                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    openError = "si è disconnessa"
                    openLatch.countDown()
                }

                override fun onError(c: CameraDevice, error: Int) {
                    c.close()
                    openError = "errore $error"
                    openLatch.countDown()
                }
            })
            if (!openLatch.await(8, TimeUnit.SECONDS) || camera == null) {
                log("  X Fotocamera non aperta: ${openError ?: "tempo scaduto"}")
                return
            }
            val cam = camera!!

            // 2) Preparo la ricezione delle immagini RAW
            val rdr = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4)
            reader = rdr
            val frameCount = AtomicInteger(0)
            val wantStill = AtomicBoolean(false)
            val stillQueue = LinkedBlockingQueue<Image>()
            rdr.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage()
                if (img != null) {
                    if (wantStill.get()) {
                        stillQueue.offer(img)
                    } else {
                        frameCount.incrementAndGet()
                        img.close()
                    }
                }
            }, handler)

            // 3) Apro la sessione, indicando (se serve) l'obiettivo fisico
            val outCfg = OutputConfiguration(rdr.surface)
            if (physId != null) outCfg.setPhysicalCameraId(physId)
            val sessLatch = CountDownLatch(1)
            var sessError: String? = null
            val sessCfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outCfg),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        sessLatch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessError = "il sistema ha rifiutato la configurazione"
                        sessLatch.countDown()
                    }
                }
            )
            cam.createCaptureSession(sessCfg)
            if (!sessLatch.await(8, TimeUnit.SECONDS) || session == null) {
                log("  X Sessione non creata: ${sessError ?: "tempo scaduto"}")
                return
            }
            val sess = session!!

            // 4) Riscaldamento: qualche frame perché l'esposizione automatica si assesti
            val warm = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            warm.addTarget(rdr.surface)
            sess.setRepeatingRequest(warm.build(), null, handler)
            val t0 = System.currentTimeMillis()
            while (frameCount.get() < 20 && System.currentTimeMillis() - t0 < 6000) {
                Thread.sleep(50)
            }
            log("  Frame RAW ricevuti nel riscaldamento: ${frameCount.get()}")
            if (frameCount.get() < 3) {
                log("  X Questo obiettivo non produce frame RAW.")
                return
            }
            sess.stopRepeating()

            // 5) Scatto vero e proprio
            wantStill.set(true)
            val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            still.addTarget(rdr.surface)
            val resLatch = CountDownLatch(1)
            var result: TotalCaptureResult? = null
            var failReason: String? = null
            sess.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    res: TotalCaptureResult
                ) {
                    result = res
                    resLatch.countDown()
                }

                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    f: CaptureFailure
                ) {
                    failReason = "motivo ${f.reason}"
                    resLatch.countDown()
                }
            }, handler)
            if (!resLatch.await(10, TimeUnit.SECONDS) || result == null) {
                log("  X Scatto fallito: ${failReason ?: "tempo scaduto"}")
                return
            }
            val res = result!!
            val physRes: CaptureResult? =
                if (physId != null) res.physicalCameraResults[physId] else null
            val usedRes: CaptureResult = physRes ?: res
            val ts: Long? = usedRes.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: res.get(CaptureResult.SENSOR_TIMESTAMP)

            var image: Image? = null
            val t1 = System.currentTimeMillis()
            while (image == null && System.currentTimeMillis() - t1 < 8000) {
                val img = stillQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (ts == null || img.timestamp == ts) {
                    image = img
                } else {
                    img.close()
                }
            }
            if (image == null) {
                log("  X Immagine RAW dello scatto non ricevuta.")
                return
            }
            val imgW = image.width
            val imgH = image.height

            // 6) Salvo il file DNG nella Galleria
            val dngChars = chars ?: manager.getCameraCharacteristics(logicalId)
            val name = "raw_test_${label}_${System.currentTimeMillis()}.dng"
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FoldRawTest")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                log("  X Non riesco a creare il file nella Galleria.")
                image.close()
                return
            }
            val out = contentResolver.openOutputStream(uri)
            if (out == null) {
                log("  X Non riesco a scrivere il file.")
                image.close()
                return
            }
            out.use { stream ->
                val dng = DngCreator(dngChars, usedRes)
                dng.writeImage(stream, image)
                dng.close()
            }
            image.close()

            // 7) Dati dello scatto
            val expo = usedRes.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = usedRes.get(CaptureResult.SENSOR_SENSITIVITY)
            val focalUsed = usedRes.get(CaptureResult.LENS_FOCAL_LENGTH)
            val active = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            val expoText = if (expo != null && expo > 0) "1/${Math.round(1e9 / expo)} s" else "?"
            log("  OK Scatto RAW riuscito: ${imgW}x${imgH}")
            log("     tempo di scatto: $expoText, ISO: ${iso ?: "?"}, focale: ${focalUsed ?: "?"}mm")
            log("     obiettivo realmente attivo: ${active ?: "non indicato"}")
            log("     file: $name")
        } catch (t: Throwable) {
            log("  X Errore: $t")
        } finally {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            try {
                camera?.close()
            } catch (_: Throwable) {
            }
            try {
                reader?.close()
            } catch (_: Throwable) {
            }
            thread.quitSafely()
            try {
                Thread.sleep(600)
            } catch (_: Throwable) {
            }
        }
    }
}
