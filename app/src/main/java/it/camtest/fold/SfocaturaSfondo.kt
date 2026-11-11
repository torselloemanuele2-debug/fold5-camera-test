package it.camtest.fold

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ritratto con sfondo sfocato: riconosce la persona (ML Kit), tiene nitido il soggetto
 * e sfoca tutto il resto. Da chiamare SEMPRE fuori dal thread principale.
 */
object SfocaturaSfondo {

    // Oltre questa risoluzione la foto viene ridotta (evita di finire la memoria con i 50 MP).
    private const val PIXEL_MASSIMI = 12_600_000L
    private const val LATO_SEGMENTAZIONE = 768
    private const val LATO_SFONDO = 480
    private const val RAGGIO_SFOCATURA = 6
    private const val PASSAGGI_SFOCATURA = 3

    /** Riceve il JPEG originale e restituisce il JPEG con lo sfondo sfocato. */
    fun applica(jpeg: ByteArray): ByteArray {
        val originale = decodificaRaddrizzata(jpeg)
        var maschera: Bitmap? = null
        var sfondo: Bitmap? = null
        var risultato: Bitmap? = null
        try {
            val w = originale.width
            val h = originale.height
            maschera = calcolaMaschera(originale)
            sfondo = sfondoSfocato(originale)

            risultato = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tela = Canvas(risultato)
            val area = Rect(0, 0, w, h)
            tela.drawBitmap(sfondo, null, area, Paint(Paint.FILTER_BITMAP_FLAG))

            // Strato col soggetto: foto originale ritagliata con la maschera, poi sovrapposta allo sfondo.
            val strato = tela.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
            tela.drawBitmap(originale, 0f, 0f, null)
            val pennelloMaschera = Paint(Paint.FILTER_BITMAP_FLAG)
            pennelloMaschera.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            tela.drawBitmap(maschera, null, area, pennelloMaschera)
            tela.restoreToCount(strato)

            val uscita = ByteArrayOutputStream()
            risultato.compress(Bitmap.CompressFormat.JPEG, 95, uscita)
            return uscita.toByteArray()
        } finally {
            maschera?.recycle()
            sfondo?.recycle()
            risultato?.recycle()
            originale.recycle()
        }
    }

    /** Decodifica il JPEG (ridotto se enorme) e lo ruota in verticale secondo l'EXIF. */
    private fun decodificaRaddrizzata(jpeg: ByteArray): Bitmap {
        val limiti = BitmapFactory.Options()
        limiti.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, limiti)

        var campione = 1
        while ((limiti.outWidth.toLong() / campione) * (limiti.outHeight.toLong() / campione) > PIXEL_MASSIMI) {
            campione *= 2
        }
        val opzioni = BitmapFactory.Options()
        opzioni.inSampleSize = campione
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opzioni)
            ?: throw IllegalStateException("impossibile leggere il JPEG")

        val orientamento = ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val gradi = when (orientamento) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (gradi == 0f) return bmp

        val matrice = Matrix()
        matrice.postRotate(gradi)
        val ruotata = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrice, true)
        if (ruotata !== bmp) bmp.recycle()
        return ruotata
    }

    /** Bitmap con trasparenza = quanto ogni punto appartiene alla persona (opaco = soggetto). */
    private fun calcolaMaschera(origine: Bitmap): Bitmap {
        val scala = LATO_SEGMENTAZIONE.toFloat() / maxOf(origine.width, origine.height)
        val piccola = if (scala < 1f) {
            Bitmap.createScaledBitmap(
                origine,
                (origine.width * scala).toInt().coerceAtLeast(1),
                (origine.height * scala).toInt().coerceAtLeast(1),
                true
            )
        } else {
            origine
        }

        val opzioni = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        val segmentatore = Segmentation.getClient(opzioni)
        try {
            val attesa = CountDownLatch(1)
            var ottenuta: SegmentationMask? = null
            var errore: Throwable? = null
            segmentatore.process(InputImage.fromBitmap(piccola, 0))
                .addOnSuccessListener {
                    ottenuta = it
                    attesa.countDown()
                }
                .addOnFailureListener {
                    errore = it
                    attesa.countDown()
                }
            if (!attesa.await(30, TimeUnit.SECONDS)) {
                throw IllegalStateException("il riconoscimento della persona ha impiegato troppo tempo")
            }
            val maschera = ottenuta
                ?: throw IllegalStateException("riconoscimento della persona non riuscito: ${errore?.message}")

            val mw = maschera.width
            val mh = maschera.height
            val buffer = maschera.buffer
            buffer.rewind()
            val colori = IntArray(mw * mh)
            var soggetto = 0
            for (i in colori.indices) {
                val confidenza = buffer.getFloat()
                // Curva morbida: sotto 0.35 è sfondo, sopra 0.75 è soggetto pieno.
                val a = ((confidenza - 0.35f) / 0.4f).coerceIn(0f, 1f)
                if (a > 0.5f) soggetto++
                colori[i] = (a * 255f).toInt() shl 24
            }
            if (soggetto < colori.size / 50) {
                throw IllegalStateException("non ho riconosciuto nessuna persona nella foto")
            }
            return Bitmap.createBitmap(colori, mw, mh, Bitmap.Config.ARGB_8888)
        } finally {
            segmentatore.close()
            if (piccola !== origine) piccola.recycle()
        }
    }

    /** Versione molto sfocata della foto (a bassa risoluzione: viene poi ingrandita in morbidezza). */
    private fun sfondoSfocato(origine: Bitmap): Bitmap {
        val scala = LATO_SFONDO.toFloat() / maxOf(origine.width, origine.height)
        val sw = (origine.width * scala).toInt().coerceAtLeast(8)
        val sh = (origine.height * scala).toInt().coerceAtLeast(8)
        val piccola = Bitmap.createScaledBitmap(origine, sw, sh, true)
        val pixel = IntArray(sw * sh)
        piccola.getPixels(pixel, 0, sw, 0, 0, sw, sh)
        if (piccola !== origine) piccola.recycle()
        repeat(PASSAGGI_SFOCATURA) { sfocaBox(pixel, sw, sh, RAGGIO_SFOCATURA) }
        return Bitmap.createBitmap(pixel, sw, sh, Bitmap.Config.ARGB_8888)
    }

    /** Sfocatura a scatola, prima in orizzontale e poi in verticale. */
    private fun sfocaBox(pixel: IntArray, w: Int, h: Int, raggio: Int) {
        val temp = IntArray(pixel.size)
        val n = 2 * raggio + 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -raggio..raggio) {
                    val c = pixel[y * w + (x + k).coerceIn(0, w - 1)]
                    a += c ushr 24
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                temp[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -raggio..raggio) {
                    val c = temp[(y + k).coerceIn(0, h - 1) * w + x]
                    a += c ushr 24
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                pixel[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
