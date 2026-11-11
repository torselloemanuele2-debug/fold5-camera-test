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
 * Ritratto con sfondo sfocato, in quattro passi:
 *  1. ML Kit riconosce la persona (maschera grossolana);
 *  2. un filtro guidato dalla foto aggancia il bordo della maschera ai contorni reali;
 *  3. lo sfondo viene sfocato SENZA far "colare" i colori del soggetto (niente aloni);
 *  4. il soggetto nitido viene sovrapposto allo sfondo sfocato, sulla foto a piena risoluzione.
 * Da chiamare SEMPRE fuori dal thread principale.
 */
object SfocaturaSfondo {

    // Oltre questa risoluzione la foto viene ridotta (evita di finire la memoria con i 50 MP).
    private const val PIXEL_MASSIMI = 12_600_000L

    // Risoluzione (lato lungo) a cui si calcolano maschera e sfondo sfocato.
    private const val LATO_LAVORO = 768

    // Parametri provati su immagini di prova (vedi prototipo): bordo preciso, pochi aloni.
    private const val RAGGIO_GUIDA = 16
    private const val EPS_GUIDA = 1e-3f
    private const val RAGGIO_SFOCATURA = 14
    private const val RAGGIO_ESCLUSIONE = 5

    /** Riceve il JPEG originale e restituisce il JPEG con lo sfondo sfocato. */
    fun applica(jpeg: ByteArray): ByteArray {
        val originale = decodificaRaddrizzata(jpeg)
        var piccola: Bitmap? = null
        var sfondoBmp: Bitmap? = null
        var mascheraBmp: Bitmap? = null
        var risultato: Bitmap? = null
        try {
            val w = originale.width
            val h = originale.height
            val scala = LATO_LAVORO.toFloat() / maxOf(w, h)
            val piccola0 = if (scala < 1f) {
                Bitmap.createScaledBitmap(
                    originale,
                    (w * scala).toInt().coerceAtLeast(8),
                    (h * scala).toInt().coerceAtLeast(8),
                    true
                )
            } else {
                originale
            }
            piccola = piccola0
            val sw = piccola0.width
            val sh = piccola0.height
            val n = sw * sh

            val pixel = IntArray(n)
            piccola0.getPixels(pixel, 0, sw, 0, 0, sw, sh)
            val rosso = FloatArray(n)
            val verde = FloatArray(n)
            val blu = FloatArray(n)
            val luce = FloatArray(n)
            for (i in 0 until n) {
                val c = pixel[i]
                rosso[i] = ((c shr 16) and 0xFF) / 255f
                verde[i] = ((c shr 8) and 0xFF) / 255f
                blu[i] = (c and 0xFF) / 255f
                luce[i] = 0.299f * rosso[i] + 0.587f * verde[i] + 0.114f * blu[i]
            }

            // 1 + 2: maschera grossolana, poi bordo agganciato ai contorni della foto.
            val grezza = confidenzaPersona(piccola0)
            val guidata = filtroGuidato(luce, grezza, sw, sh, RAGGIO_GUIDA, EPS_GUIDA)
            val alfa = FloatArray(n)
            var soggetto = 0
            for (i in 0 until n) {
                alfa[i] = morbido(guidata[i], 0.35f, 0.65f)
                if (alfa[i] > 0.5f) soggetto++
            }
            if (soggetto < n / 50) {
                throw IllegalStateException("non ho riconosciuto nessuna persona nella foto")
            }

            // 3: sfondo sfocato senza contributo del soggetto (media pesata).
            val vicinoSoggetto = media(alfa, sw, sh, RAGGIO_ESCLUSIONE)
            val peso = FloatArray(n) { (1f - vicinoSoggetto[it] * 6f).coerceIn(0f, 1f) }
            val pesoSfocato = media3(peso, sw, sh, RAGGIO_SFOCATURA)
            val canali = arrayOf(rosso, verde, blu)
            val sfocati = Array(3) { c ->
                val pesata = FloatArray(n) { canali[c][it] * peso[it] }
                media3(pesata, sw, sh, RAGGIO_SFOCATURA)
            }
            val coloriSfondo = IntArray(n)
            val coloriMaschera = IntArray(n)
            for (i in 0 until n) {
                val d = maxOf(pesoSfocato[i], 1e-3f)
                val r = (sfocati[0][i] / d).coerceIn(0f, 1f)
                val g = (sfocati[1][i] / d).coerceIn(0f, 1f)
                val b = (sfocati[2][i] / d).coerceIn(0f, 1f)
                coloriSfondo[i] = (0xFF shl 24) or
                    ((r * 255f + 0.5f).toInt() shl 16) or
                    ((g * 255f + 0.5f).toInt() shl 8) or
                    (b * 255f + 0.5f).toInt()
                coloriMaschera[i] = (alfa[i] * 255f + 0.5f).toInt() shl 24
            }
            sfondoBmp = Bitmap.createBitmap(coloriSfondo, sw, sh, Bitmap.Config.ARGB_8888)
            mascheraBmp = Bitmap.createBitmap(coloriMaschera, sw, sh, Bitmap.Config.ARGB_8888)

            // 4: composizione a piena risoluzione.
            risultato = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tela = Canvas(risultato)
            val area = Rect(0, 0, w, h)
            tela.drawBitmap(sfondoBmp, null, area, Paint(Paint.FILTER_BITMAP_FLAG))
            val strato = tela.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
            tela.drawBitmap(originale, 0f, 0f, null)
            val pennelloMaschera = Paint(Paint.FILTER_BITMAP_FLAG)
            pennelloMaschera.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            tela.drawBitmap(mascheraBmp, null, area, pennelloMaschera)
            tela.restoreToCount(strato)

            val uscita = ByteArrayOutputStream()
            risultato.compress(Bitmap.CompressFormat.JPEG, 95, uscita)
            return uscita.toByteArray()
        } finally {
            if (piccola !== originale) piccola?.recycle()
            sfondoBmp?.recycle()
            mascheraBmp?.recycle()
            risultato?.recycle()
            originale.recycle()
        }
    }

    // --------------------------------------------------------
    // Decodifica
    // --------------------------------------------------------

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

    // --------------------------------------------------------
    // Riconoscimento della persona (ML Kit)
    // --------------------------------------------------------

    /** Per ogni pixel di [immagine]: probabilità (0..1) che appartenga a una persona. */
    private fun confidenzaPersona(immagine: Bitmap): FloatArray {
        val opzioni = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        val segmentatore = Segmentation.getClient(opzioni)
        try {
            val attesa = CountDownLatch(1)
            var ottenuta: SegmentationMask? = null
            var errore: Throwable? = null
            segmentatore.process(InputImage.fromBitmap(immagine, 0))
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
            val dati = FloatArray(mw * mh)
            for (i in dati.indices) dati[i] = buffer.getFloat()

            val w = immagine.width
            val h = immagine.height
            if (mw == w && mh == h) return dati
            // Dimensioni diverse: riporto la maschera alla misura dell'immagine (punto più vicino).
            val uscita = FloatArray(w * h)
            for (y in 0 until h) {
                val sy = (y * mh / h).coerceAtMost(mh - 1)
                for (x in 0 until w) {
                    val sx = (x * mw / w).coerceAtMost(mw - 1)
                    uscita[y * w + x] = dati[sy * mw + sx]
                }
            }
            return uscita
        } finally {
            segmentatore.close()
        }
    }

    // --------------------------------------------------------
    // Elaborazione dell'immagine (float, bordi replicati)
    // --------------------------------------------------------

    /** Passaggio da 0..1 con curva morbida tra [basso] e [alto]. */
    private fun morbido(x: Float, basso: Float, alto: Float): Float {
        val t = ((x - basso) / (alto - basso)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Media mobile di raggio [r] su una linea di [n] valori. */
    private fun mediaLinea(src: FloatArray, dst: FloatArray, inizio: Int, passo: Int, n: Int, r: Int) {
        val fattore = 1.0 / (2 * r + 1)
        var somma = 0.0
        for (k in -r..r) somma += src[inizio + k.coerceIn(0, n - 1) * passo]
        for (x in 0 until n) {
            dst[inizio + x * passo] = (somma * fattore).toFloat()
            somma += src[inizio + (x + r + 1).coerceAtMost(n - 1) * passo] -
                src[inizio + (x - r).coerceAtLeast(0) * passo]
        }
    }

    /** Sfocatura a scatola (media su finestra quadrata di lato 2r+1). */
    private fun media(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val temp = FloatArray(src.size)
        val uscita = FloatArray(src.size)
        for (y in 0 until h) mediaLinea(src, temp, y * w, 1, w, r)
        for (x in 0 until w) mediaLinea(temp, uscita, x, w, h, r)
        return uscita
    }

    /** Tre passaggi di sfocatura a scatola: approssimano una sfocatura gaussiana. */
    private fun media3(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        var a = src
        repeat(3) { a = media(a, w, h, r) }
        return a
    }

    /**
     * Filtro guidato (He et al.): ritocca [input] in modo che i suoi bordi seguano quelli
     * della [guida] (la luminosità della foto).
     */
    private fun filtroGuidato(guida: FloatArray, input: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = guida.size
        val mGuida = media(guida, w, h, r)
        val mInput = media(input, w, h, r)
        val corrIncrociata = media(FloatArray(n) { guida[it] * input[it] }, w, h, r)
        val corrGuida = media(FloatArray(n) { guida[it] * guida[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val covarianza = corrIncrociata[i] - mGuida[i] * mInput[i]
            val varianza = corrGuida[i] - mGuida[i] * mGuida[i]
            a[i] = covarianza / (varianza + eps)
            b[i] = mInput[i] - a[i] * mGuida[i]
        }
        val mA = media(a, w, h, r)
        val mB = media(b, w, h, r)
        return FloatArray(n) { (mA[it] * guida[it] + mB[it]).coerceIn(0f, 1f) }
    }
}
