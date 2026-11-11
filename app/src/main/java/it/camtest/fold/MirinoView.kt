package it.camtest.fold

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Guida all'inquadratura sopra l'anteprima. La vista copre esattamente l'area che finirà nella foto:
 * gli angoli ne segnano i bordi, la griglia (regola dei terzi) aiuta a comporre,
 * la croce segna il centro (dove l'autofocus misura) e l'etichetta dice con quale obiettivo scatti.
 */
class MirinoView(context: Context) : View(context) {

    var etichetta: String = ""
        set(valore) {
            field = valore
            postInvalidate()
        }

    private val densita = resources.displayMetrics.density

    private val lineeGriglia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = densita
        style = Paint.Style.STROKE
    }
    private val lineeAngoli = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * densita
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lineeCroce = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 2f * densita
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pennelloTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * densita
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pennelloEtichetta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Regola dei terzi.
        canvas.drawLine(w / 3f, 0f, w / 3f, h, lineeGriglia)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, lineeGriglia)
        canvas.drawLine(0f, h / 3f, w, h / 3f, lineeGriglia)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, lineeGriglia)

        // Croce al centro.
        val braccio = 12f * densita
        canvas.drawLine(w / 2f - braccio, h / 2f, w / 2f + braccio, h / 2f, lineeCroce)
        canvas.drawLine(w / 2f, h / 2f - braccio, w / 2f, h / 2f + braccio, lineeCroce)

        // Angoli: i bordi della foto.
        val margine = 10f * densita
        val lunghezza = 28f * densita
        canvas.drawLine(margine, margine, margine + lunghezza, margine, lineeAngoli)
        canvas.drawLine(margine, margine, margine, margine + lunghezza, lineeAngoli)
        canvas.drawLine(w - margine, margine, w - margine - lunghezza, margine, lineeAngoli)
        canvas.drawLine(w - margine, margine, w - margine, margine + lunghezza, lineeAngoli)
        canvas.drawLine(margine, h - margine, margine + lunghezza, h - margine, lineeAngoli)
        canvas.drawLine(margine, h - margine, margine, h - margine - lunghezza, lineeAngoli)
        canvas.drawLine(w - margine, h - margine, w - margine - lunghezza, h - margine, lineeAngoli)
        canvas.drawLine(w - margine, h - margine, w - margine, h - margine - lunghezza, lineeAngoli)

        // Etichetta in alto a sinistra.
        if (etichetta.isNotEmpty()) {
            val riempimento = 8f * densita
            val larghezzaTesto = pennelloTesto.measureText(etichetta)
            val altezzaTesto = pennelloTesto.textSize
            val sinistra = margine + 6f * densita
            val alto = margine + 6f * densita
            canvas.drawRoundRect(
                RectF(sinistra, alto, sinistra + larghezzaTesto + 2f * riempimento, alto + altezzaTesto + 1.2f * riempimento),
                6f * densita, 6f * densita, pennelloEtichetta
            )
            canvas.drawText(etichetta, sinistra + riempimento, alto + altezzaTesto + 0.1f * riempimento, pennelloTesto)
        }
    }
}
