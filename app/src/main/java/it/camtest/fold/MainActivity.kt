package it.camtest.fold

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
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
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// ============================================================
// MODELLO: come è fatto uno scenario fotografico
// ============================================================

enum class Lente { ULTRA, PRINCIPALE, TELE }

enum class Fuoco { INFINITO, AUTOMATICO, VICINO }

enum class Bilanciamento(val modo: Int) {
    AUTO(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    LUCE_GIORNO(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    NUVOLOSO(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    OMBRA(CameraMetadata.CONTROL_AWB_MODE_SHADE),
    INCANDESCENZA(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENTE(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
}

data class Scenario(
    val nome: String,
    val categoria: String,
    val lente: Lente,
    val fuoco: Fuoco,
    val bilanciamento: Bilanciamento = Bilanciamento.AUTO,
    // Se impostato, il tempo di scatto è fisso (in secondi): l'ISO si adatta alla luce.
    val tempoFisso: Double? = null,
    // Se impostato, il tempo di scatto non supera questo valore (congela il movimento).
    val velocitaMinima: Double? = null,
    // Se impostato, il tempo di scatto non supera questo valore (limite per foto a mano libera).
    val tempoMassimoManuale: Double? = null,
    val isoMassimo: Int = 1600,
    // Se vero, dopo lo scatto viene creata anche una versione con lo sfondo sfocato (effetto ritratto).
    val sfocaSfondo: Boolean = false,
    val consiglio: String,
    val paroleChiave: List<String> = emptyList()
)

object Catalogo {
    val tutti: List<Scenario> = listOf(
        // --- NOTTE ---
        Scenario(
            "Scie di fari di notte", "Notte", Lente.PRINCIPALE, Fuoco.INFINITO,
            tempoFisso = 3.0, isoMassimo = 400,
            consiglio = "Appoggia il telefono su una superficie ferma o un treppiede: a mano libera, 3 secondi verranno mossi.",
            paroleChiave = listOf("scie", "fari", "traffico", "auto", "strada")
        ),
        Scenario(
            "Città di notte", "Notte", Lente.PRINCIPALE, Fuoco.INFINITO,
            tempoMassimoManuale = 1.0 / 8, isoMassimo = 1600,
            consiglio = "Tieni il telefono il più fermo possibile. Se l'immagine resta scura, avvicinati a una fonte di luce.",
            paroleChiave = listOf("città", "skyline", "edifici", "sera")
        ),
        Scenario(
            "Cielo stellato", "Notte", Lente.ULTRA, Fuoco.INFINITO,
            tempoFisso = 10.0, isoMassimo = 3200,
            consiglio = "Serve un appoggio fermo: telefono su un muretto, una roccia o un treppiede. Lontano dalle luci della città.",
            paroleChiave = listOf("stelle", "cielo", "astrofotografia", "notte")
        ),
        Scenario(
            "Fuochi d'artificio", "Notte", Lente.PRINCIPALE, Fuoco.INFINITO,
            tempoFisso = 2.0, isoMassimo = 200,
            consiglio = "Appoggia il telefono: 2 secondi a mano libera verranno mossi. Scatta un attimo prima dell'esplosione.",
            paroleChiave = listOf("fuochi", "artificio", "capodanno", "festa")
        ),
        Scenario(
            "Luna piena", "Notte", Lente.TELE, Fuoco.INFINITO,
            velocitaMinima = 1.0 / 250, isoMassimo = 400,
            consiglio = "La luna è luminosa: un tempo troppo lungo la farebbe diventare un disco bianco senza crateri.",
            paroleChiave = listOf("luna", "astro")
        ),
        Scenario(
            "Luci e decorazioni", "Notte", Lente.PRINCIPALE, Fuoco.VICINO,
            tempoMassimoManuale = 1.0 / 15, isoMassimo = 1600,
            consiglio = "Avvicinati alle luci per un effetto più intenso.",
            paroleChiave = listOf("luci", "natale", "decorazioni", "albero")
        ),

        // --- MOVIMENTO ---
        Scenario(
            "Sport o animali in corsa", "Movimento", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            velocitaMinima = 1.0 / 1000, isoMassimo = 3200,
            consiglio = "Tempo molto rapido per congelare il movimento: se la scena è buia l'immagine avrà più grana.",
            paroleChiave = listOf("sport", "corsa", "animali", "veloce", "azione")
        ),
        Scenario(
            "Bambini che giocano", "Movimento", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            velocitaMinima = 1.0 / 500, isoMassimo = 3200,
            consiglio = "Tempo rapido per evitare sfocature da movimento improvviso.",
            paroleChiave = listOf("bambini", "giochi", "movimento")
        ),
        Scenario(
            "Acqua effetto seta (cascata/fontana)", "Movimento", Lente.PRINCIPALE, Fuoco.INFINITO,
            tempoFisso = 1.0, isoMassimo = 100,
            consiglio = "Serve un appoggio fermo. In pieno giorno l'immagine potrebbe risultare troppo chiara: provare in condizioni di luce non troppo intensa.",
            paroleChiave = listOf("acqua", "cascata", "fontana", "seta", "lunga esposizione")
        ),
        Scenario(
            "Panning (soggetto nitido, sfondo mosso)", "Movimento", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            tempoFisso = 1.0 / 30, isoMassimo = 800,
            consiglio = "Segui il soggetto con il telefono durante lo scatto, muovendoti alla sua stessa velocità.",
            paroleChiave = listOf("panning", "inseguimento", "ciclismo")
        ),
        Scenario(
            "Onde del mare", "Movimento", Lente.PRINCIPALE, Fuoco.INFINITO,
            tempoFisso = 0.5, isoMassimo = 200,
            consiglio = "Appoggia il telefono per evitare il mosso.",
            paroleChiave = listOf("mare", "onde", "spiaggia", "acqua")
        ),
        Scenario(
            "Ciclisti o auto in movimento", "Movimento", Lente.TELE, Fuoco.AUTOMATICO,
            velocitaMinima = 1.0 / 750, isoMassimo = 3200,
            consiglio = "Tempo rapido con lo zoom: ogni piccolo movimento del telefono si nota di più.",
            paroleChiave = listOf("ciclisti", "bici", "auto", "veicoli")
        ),

        // --- RITRATTO ---
        Scenario(
            "Ritratto con sfondo sfocato", "Ritratto", Lente.TELE, Fuoco.VICINO,
            tempoMassimoManuale = 1.0 / 60, isoMassimo = 1600, sfocaSfondo = true,
            consiglio = "Inquadra una persona a mezzo busto. L'app salva la foto normale e una seconda con lo sfondo sfocato (_ritratto). Più lo sfondo è distante, meglio funziona.",
            paroleChiave = listOf("ritratto", "bokeh", "sfocato", "sfondo")
        ),
        Scenario(
            "Ritratto in controluce morbido", "Ritratto", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            tempoMassimoManuale = 1.0 / 30, isoMassimo = 800,
            consiglio = "Metti il soggetto tra te e la luce (es. il sole basso): i contorni si illumineranno.",
            paroleChiave = listOf("ritratto", "controluce", "soffuso")
        ),
        Scenario(
            "Ritratto con luce da finestra", "Ritratto", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            bilanciamento = Bilanciamento.LUCE_GIORNO,
            tempoMassimoManuale = 1.0 / 30, isoMassimo = 800,
            consiglio = "Posiziona il soggetto di lato rispetto alla finestra per un'ombra naturale sul viso.",
            paroleChiave = listOf("ritratto", "finestra", "luce naturale")
        ),
        Scenario(
            "Ritratto serale con luci artificiali", "Ritratto", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            bilanciamento = Bilanciamento.INCANDESCENZA,
            isoMassimo = 1600,
            consiglio = "Il bilanciamento è impostato per luci calde: se la scena è illuminata al neon i colori potrebbero virare.",
            paroleChiave = listOf("ritratto", "sera", "serata", "locale")
        ),
        Scenario(
            "Selfie o foto di gruppo", "Ritratto", Lente.ULTRA, Fuoco.AUTOMATICO,
            isoMassimo = 1600,
            consiglio = "L'obiettivo largo fa stare tutti nell'inquadratura: tieni le persone vicine al centro per evitare deformazioni ai bordi.",
            paroleChiave = listOf("selfie", "gruppo", "amici")
        ),
        Scenario(
            "Ritratto d'atmosfera", "Ritratto", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 1600,
            consiglio = "Buono anche per una successiva conversione in bianco e nero.",
            paroleChiave = listOf("ritratto", "atmosfera", "drammatico")
        ),

        // --- PAESAGGIO ---
        Scenario(
            "Alba o tramonto", "Paesaggio", Lente.PRINCIPALE, Fuoco.INFINITO,
            bilanciamento = Bilanciamento.LUCE_GIORNO,
            tempoMassimoManuale = 1.0 / 60, isoMassimo = 200,
            consiglio = "Punta verso il cielo più luminoso per non bruciare i colori del tramonto.",
            paroleChiave = listOf("alba", "tramonto", "sole", "cielo")
        ),
        Scenario(
            "Panorama ampio di giorno", "Paesaggio", Lente.ULTRA, Fuoco.INFINITO,
            isoMassimo = 100,
            consiglio = "L'obiettivo ultra grandangolare racchiude tutto il paesaggio in un solo scatto.",
            paroleChiave = listOf("panorama", "paesaggio", "vista", "ampio")
        ),
        Scenario(
            "Nebbia o foschia", "Paesaggio", Lente.PRINCIPALE, Fuoco.INFINITO,
            bilanciamento = Bilanciamento.NUVOLOSO,
            isoMassimo = 200,
            consiglio = "La nebbia tende a rendere l'immagine piatta: cerca un soggetto in primo piano per dare profondità.",
            paroleChiave = listOf("nebbia", "foschia", "bruma")
        ),
        Scenario(
            "Montagna con neve", "Paesaggio", Lente.PRINCIPALE, Fuoco.INFINITO,
            isoMassimo = 100,
            consiglio = "La neve molto luminosa può ingannare l'esposizione: se il risultato è troppo scuro, scatta di nuovo più vicino a un'area meno chiara.",
            paroleChiave = listOf("montagna", "neve", "inverno")
        ),
        Scenario(
            "Architettura e edifici", "Paesaggio", Lente.ULTRA, Fuoco.INFINITO,
            isoMassimo = 200,
            consiglio = "Allontanati e usa il grandangolo per includere l'intera facciata senza deformazioni eccessive.",
            paroleChiave = listOf("architettura", "edifici", "palazzo", "monumento")
        ),
        Scenario(
            "Campagna o natura in piena luce", "Paesaggio", Lente.ULTRA, Fuoco.INFINITO,
            isoMassimo = 100,
            consiglio = "Luce abbondante: la qualità dell'immagine sarà ottima.",
            paroleChiave = listOf("campagna", "natura", "verde", "prato")
        ),

        // --- VICINO / MACRO ---
        Scenario(
            "Fiori e piccoli dettagli", "Vicino", Lente.PRINCIPALE, Fuoco.VICINO,
            tempoMassimoManuale = 1.0 / 30, isoMassimo = 800,
            consiglio = "Avvicinati lentamente finché l'immagine non è a fuoco: un leggero movimento del telefono aiuta a trovare la distanza giusta.",
            paroleChiave = listOf("fiori", "macro", "dettaglio", "piccolo")
        ),
        Scenario(
            "Cibo al ristorante", "Vicino", Lente.PRINCIPALE, Fuoco.VICINO,
            isoMassimo = 1600,
            consiglio = "Avvicinati al piatto e, se possibile, usa la luce naturale di una finestra vicina.",
            paroleChiave = listOf("cibo", "piatto", "ristorante", "food")
        ),
        Scenario(
            "Oggetti o prodotti da vendere", "Vicino", Lente.PRINCIPALE, Fuoco.VICINO,
            bilanciamento = Bilanciamento.LUCE_GIORNO,
            isoMassimo = 400,
            consiglio = "Usa uno sfondo semplice e una luce uniforme per far risaltare l'oggetto.",
            paroleChiave = listOf("prodotto", "oggetto", "vendita", "annuncio")
        ),
        Scenario(
            "Insetti o dettagli naturali", "Vicino", Lente.PRINCIPALE, Fuoco.VICINO,
            velocitaMinima = 1.0 / 125, isoMassimo = 1600,
            consiglio = "Tempo abbastanza rapido perché i piccoli soggetti si muovono spesso all'improvviso.",
            paroleChiave = listOf("insetti", "farfalla", "natura", "dettaglio")
        ),
        Scenario(
            "Testo o documenti", "Vicino", Lente.PRINCIPALE, Fuoco.VICINO,
            isoMassimo = 400, tempoMassimoManuale = 1.0 / 50,
            consiglio = "Tieni il foglio ben illuminato e il telefono parallelo al testo per evitare sfocature ai bordi.",
            paroleChiave = listOf("documento", "testo", "foglio", "scansione")
        ),

        // --- SITUAZIONI DIFFICILI ---
        Scenario(
            "Concerto o palco", "Situazioni difficili", Lente.TELE, Fuoco.AUTOMATICO,
            velocitaMinima = 1.0 / 60, isoMassimo = 3200,
            consiglio = "Luce scarsa e in movimento: l'immagine avrà più grana ma il soggetto resterà leggibile.",
            paroleChiave = listOf("concerto", "palco", "musica", "spettacolo")
        ),
        Scenario(
            "Interni di chiesa o museo", "Situazioni difficili", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 1600, tempoMassimoManuale = 1.0 / 15,
            consiglio = "Appoggiati a una colonna o a un muro per tenere il telefono più fermo possibile.",
            paroleChiave = listOf("chiesa", "museo", "interno", "monumento")
        ),
        Scenario(
            "Spiaggia a mezzogiorno", "Situazioni difficili", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 100,
            consiglio = "Luce fortissima: occhio ai riflessi sull'acqua e sulla sabbia che possono ingannare l'esposizione.",
            paroleChiave = listOf("spiaggia", "mare", "sole", "estate")
        ),
        Scenario(
            "Neve piena di sole", "Situazioni difficili", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 100,
            consiglio = "Come per la spiaggia, la superficie chiarissima può confondere la misurazione automatica della luce.",
            paroleChiave = listOf("neve", "sole", "inverno", "bianco")
        ),
        Scenario(
            "Attraverso vetro o vetrina", "Situazioni difficili", Lente.PRINCIPALE, Fuoco.VICINO,
            isoMassimo = 1600,
            consiglio = "Avvicina l'obiettivo del telefono il più possibile al vetro per ridurre i riflessi.",
            paroleChiave = listOf("vetro", "vetrina", "acquario", "finestra")
        ),
        Scenario(
            "Controluce forte (sole in camera)", "Situazioni difficili", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 200,
            consiglio = "Se possibile, fai in modo che un elemento della scena (un ramo, un edificio) copra parzialmente il sole.",
            paroleChiave = listOf("controluce", "sole", "bagliore")
        ),
        Scenario(
            "Teatro o sala buia con palco illuminato", "Situazioni difficili", Lente.TELE, Fuoco.AUTOMATICO,
            velocitaMinima = 1.0 / 60, isoMassimo = 3200,
            consiglio = "Il forte contrasto tra palco illuminato e sala buia è normale: l'esposizione privilegia il palco.",
            paroleChiave = listOf("teatro", "spettacolo", "palco", "sala")
        ),
        Scenario(
            "Interno di auto o aereo", "Situazioni difficili", Lente.ULTRA, Fuoco.AUTOMATICO,
            isoMassimo = 3200, tempoMassimoManuale = 1.0 / 20,
            consiglio = "Spazio stretto e poca luce: il grandangolo aiuta a inquadrare tutto l'interno.",
            paroleChiave = listOf("auto", "aereo", "treno", "interno")
        ),

        // --- CREATIVO / ALTRO ---
        Scenario(
            "Nuvole in movimento di giorno", "Creativo", Lente.ULTRA, Fuoco.INFINITO,
            tempoFisso = 0.5, isoMassimo = 50,
            consiglio = "Serve un appoggio fermo. In piena luce diurna l'immagine potrebbe venire troppo chiara: provare con cielo nuvoloso o luce non intensa.",
            paroleChiave = listOf("nuvole", "cielo", "lunga esposizione", "diurna")
        ),
        Scenario(
            "Silhouette al tramonto", "Creativo", Lente.PRINCIPALE, Fuoco.INFINITO,
            isoMassimo = 100,
            consiglio = "Inquadra il soggetto davanti al cielo più luminoso: diventerà una sagoma scura ben definita.",
            paroleChiave = listOf("silhouette", "sagoma", "controluce", "tramonto")
        ),
        Scenario(
            "Riflessi su acqua calma", "Creativo", Lente.PRINCIPALE, Fuoco.INFINITO,
            isoMassimo = 200,
            consiglio = "Avvicinati alla superficie dell'acqua per un riflesso più ampio e nitido.",
            paroleChiave = listOf("riflesso", "acqua", "lago", "specchio")
        ),
        Scenario(
            "Fotografare uno schermo o una TV", "Creativo", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            tempoFisso = 1.0 / 50, isoMassimo = 1600,
            consiglio = "Tempo scelto per ridurre le bande scure che si formano fotografando gli schermi.",
            paroleChiave = listOf("schermo", "tv", "monitor", "proiezione")
        ),
        Scenario(
            "Luci al neon colorate di sera", "Creativo", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 1600,
            consiglio = "Lascia che i colori delle insegne restino saturi: evita di avvicinarti troppo a una sola luce.",
            paroleChiave = listOf("neon", "insegne", "colori", "sera")
        ),
        Scenario(
            "Pioggia su un vetro", "Creativo", Lente.PRINCIPALE, Fuoco.VICINO,
            isoMassimo = 800,
            consiglio = "Metti a fuoco le gocce più vicine: lo sfondo resterà naturalmente sfocato.",
            paroleChiave = listOf("pioggia", "gocce", "vetro", "maltempo")
        ),
        Scenario(
            "Falò o candele", "Creativo", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            bilanciamento = Bilanciamento.INCANDESCENZA,
            tempoMassimoManuale = 1.0 / 10, isoMassimo = 1600,
            consiglio = "La fiamma è molto più luminosa di quanto sembri: avvicinati senza farla diventare un'area bianca.",
            paroleChiave = listOf("falò", "candele", "fuoco", "fiamma")
        ),
        Scenario(
            "Interno con luce mista (negozio, ufficio)", "Creativo", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 800,
            consiglio = "Con luci di tipo diverso nella stessa scena, i colori possono risultare leggermente innaturali: è normale.",
            paroleChiave = listOf("negozio", "ufficio", "interno", "lavoro")
        ),
        Scenario(
            "Non so, scelta automatica intelligente", "Creativo", Lente.PRINCIPALE, Fuoco.AUTOMATICO,
            isoMassimo = 1600,
            consiglio = "Impostazioni vicine a quelle automatiche, con un leggero margine di sicurezza contro il mosso.",
            paroleChiave = listOf("automatico", "non so", "generico", "qualsiasi")
        )
    )
}

// ============================================================
// MOTORE: calcola i valori adatti alla luce reale misurata
// ============================================================

object Solver {
    data class Risultato(val tempoNs: Long, val iso: Int)

    fun risolvi(
        scenario: Scenario,
        autoExpoNs: Long,
        autoIso: Int,
        expoMinNs: Long,
        expoMaxNs: Long,
        isoMinDispositivo: Int,
        isoMaxDispositivo: Int
    ): Risultato {
        val autoExpoS = autoExpoNs / 1_000_000_000.0
        val isoBase = autoIso.coerceAtLeast(1)
        // Quantità di luce che la misurazione automatica ritiene corretta.
        val prodottoAuto = autoExpoS * isoBase

        var tempoTarget = when {
            scenario.tempoFisso != null -> scenario.tempoFisso
            scenario.velocitaMinima != null -> minOf(autoExpoS, scenario.velocitaMinima)
            scenario.tempoMassimoManuale != null -> minOf(autoExpoS, scenario.tempoMassimoManuale)
            else -> autoExpoS
        }
        if (tempoTarget <= 0.0) tempoTarget = autoExpoS.coerceAtLeast(1.0 / 1000)

        var isoTarget = (prodottoAuto / tempoTarget).roundToInt()

        if (isoTarget > scenario.isoMassimo) {
            if (scenario.tempoFisso == null) {
                // Allunga il tempo per restare entro l'ISO massimo dello scenario,
                // ma senza superare eventuali vincoli di velocità minima o tempo massimo.
                val tempoAllungato = prodottoAuto / scenario.isoMassimo
                val limite = scenario.velocitaMinima ?: scenario.tempoMassimoManuale
                tempoTarget = if (limite != null) minOf(tempoAllungato, maxOf(tempoTarget, limite * 8)) else tempoAllungato
                isoTarget = (prodottoAuto / tempoTarget).roundToInt().coerceAtMost(scenario.isoMassimo)
            } else {
                isoTarget = scenario.isoMassimo
            }
        }

        isoTarget = isoTarget.coerceIn(isoMinDispositivo, isoMaxDispositivo)
        var tempoNs = (tempoTarget * 1_000_000_000.0).toLong()
        tempoNs = tempoNs.coerceIn(expoMinNs, expoMaxNs)

        return Risultato(tempoNs, isoTarget)
    }
}

// ============================================================
// APP
// ============================================================

class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var textureView: TextureView
    private lateinit var campoRicerca: EditText
    private lateinit var listaScenari: LinearLayout
    private lateinit var statoView: TextView
    private lateinit var bottoneScatta: Button

    private lateinit var cameraManager: CameraManager
    private var backId: String = "0"
    private val fisici = mutableMapOf<Lente, String?>()
    private val caratteristiche = mutableMapOf<Lente, CameraCharacteristics>()

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chiusuraSessione: CountDownLatch? = null
    private var lenteAttuale: Lente? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null
    // Thread di lavoro: esegue le sequenze che si bloccano in attesa (apri, configura, scatta).
    private val cameraThread = HandlerThread("fotocamera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    // Thread dei callback: DEVE essere diverso da quello di lavoro, altrimenti i callback
    // restano in coda dietro l'attesa (latch.await) e la fotocamera non parte mai.
    private val callbackThread = HandlerThread("fotocamera-callback").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val callbackExecutor = Executor { r -> callbackHandler.post(r) }

    private val codaJpeg = LinkedBlockingQueue<Image>(2)

    @Volatile private var ultimoExpoNs: Long = 1_000_000_000L / 50
    @Volatile private var ultimoIso: Int = 200
    @Volatile private var ultimoAfState: Int? = null
    @Volatile private var ultimoFocusDistance: Float = 0f
    @Volatile private var sessionePronta = false

    private var scenarioSelezionato: Scenario? = null
    @Volatile private var inElaborazione = false

    private val logFile: File get() = File(filesDir, "report_scenari.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                logFile.appendText("\n!!! CRASH !!!\n$sw\n")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        costruisciInterfaccia()
        rilevaLenti()

        val vecchioCrash = try {
            if (logFile.exists()) logFile.readText() else ""
        } catch (_: Throwable) {
            ""
        }
        if (vecchioCrash.contains("CRASH")) {
            statoView.text = "### ERRORE DELLA VOLTA PRECEDENTE ###\n$vecchioCrash"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        chiudiTutto()
        cameraThread.quitSafely()
        callbackThread.quitSafely()
    }

    // --------------------------------------------------------
    // Interfaccia
    // --------------------------------------------------------

    private fun costruisciInterfaccia() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val larghezzaSchermo = resources.displayMetrics.widthPixels
        val altezzaPreview = (larghezzaSchermo * 4 / 3)

        val contenitorePreview = FrameLayout(this)
        contenitorePreview.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, altezzaPreview
        )
        textureView = TextureView(this)
        textureView.surfaceTextureListener = this
        contenitorePreview.addView(
            textureView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(contenitorePreview)

        campoRicerca = EditText(this)
        campoRicerca.hint = "Cerca uno scenario (es. notte, ritratto, sport)..."
        campoRicerca.setPadding(24, 16, 24, 16)
        root.addView(campoRicerca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listaScenari = LinearLayout(this)
        listaScenari.orientation = LinearLayout.VERTICAL
        listaScenari.setPadding(16, 8, 16, 8)
        val scroll = ScrollView(this)
        scroll.addView(listaScenari)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statoView = TextView(this)
        statoView.typeface = Typeface.MONOSPACE
        statoView.textSize = 12f
        statoView.setPadding(24, 8, 24, 8)
        statoView.setTextIsSelectable(true)
        statoView.text = "Scegli uno scenario dalla lista, poi premi Scatta."
        root.addView(statoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rigaBottoni = LinearLayout(this)
        rigaBottoni.orientation = LinearLayout.HORIZONTAL

        bottoneScatta = Button(this)
        bottoneScatta.text = "Scatta"
        bottoneScatta.isEnabled = false
        bottoneScatta.setOnClickListener { avviaScatto() }
        rigaBottoni.addView(bottoneScatta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val bottoneCopia = Button(this)
        bottoneCopia.text = "Copia report"
        bottoneCopia.setOnClickListener { copiaReport() }
        rigaBottoni.addView(bottoneCopia, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val bottoneDiagnostica = Button(this)
        bottoneDiagnostica.text = "Diagnostica"
        bottoneDiagnostica.setOnClickListener { avviaDiagnostica() }
        rigaBottoni.addView(bottoneDiagnostica, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(rigaBottoni)

        setContentView(root)

        campoRicerca.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                popolaLista(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        popolaLista("")

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) apriFotocamera()
        } else {
            statoView.text = "Serve il permesso della fotocamera per usare l'app."
        }
    }

    private fun popolaLista(filtro: String) {
        listaScenari.removeAllViews()
        val f = filtro.trim().lowercase(Locale.ITALY)
        val visibili = Catalogo.tutti.filter { sc ->
            f.isEmpty() ||
                sc.nome.lowercase(Locale.ITALY).contains(f) ||
                sc.categoria.lowercase(Locale.ITALY).contains(f) ||
                sc.paroleChiave.any { it.lowercase(Locale.ITALY).contains(f) }
        }
        var categoriaCorrente = ""
        for (sc in visibili) {
            if (sc.categoria != categoriaCorrente) {
                categoriaCorrente = sc.categoria
                val intestazione = TextView(this)
                intestazione.text = categoriaCorrente
                intestazione.setTypeface(null, Typeface.BOLD)
                intestazione.setPadding(8, 24, 8, 8)
                listaScenari.addView(intestazione)
            }
            val bottone = Button(this)
            bottone.text = sc.nome
            bottone.isAllCaps = false
            bottone.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (sc == scenarioSelezionato) {
                bottone.setBackgroundColor(Color.parseColor("#D0E8FF"))
            }
            bottone.setOnClickListener { selezionaScenario(sc) }
            listaScenari.addView(bottone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (visibili.isEmpty()) {
            val vuoto = TextView(this)
            vuoto.text = "Nessuno scenario trovato."
            vuoto.setPadding(8, 24, 8, 8)
            listaScenari.addView(vuoto)
        }
    }

    private fun selezionaScenario(sc: Scenario) {
        scenarioSelezionato = sc
        bottoneScatta.isEnabled = cameraDevice != null
        // Porta subito l'anteprima sull'obiettivo dello scenario: quello che vedi è quello che scatti.
        if (cameraDevice != null && !inElaborazione) {
            cameraHandler.post { configuraSessione(sc.lente) }
        }
        statoView.text = "Hai scelto: ${sc.nome}\nObiettivo: ${sc.lente}  Fuoco: ${sc.fuoco}\n${sc.consiglio}\n\nPremi Scatta quando sei pronto."
        popolaLista(campoRicerca.text?.toString() ?: "")
    }

    private fun copiaReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val registroCompleto = try {
            if (logFile.exists()) logFile.readText() else ""
        } catch (_: Throwable) {
            ""
        }
        val testoCompleto = buildString {
            append(statoView.text.toString())
            if (registroCompleto.isNotBlank()) {
                append("\n\n=== REGISTRO COMPLETO (tutte le operazioni) ===\n")
                append(registroCompleto)
            }
        }
        cm.setPrimaryClip(ClipData.newPlainText("report", testoCompleto))
        Toast.makeText(this, "Report copiato", Toast.LENGTH_SHORT).show()
    }

    private fun describeError(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().take(6).joinToString(" | ")
    }

    // --------------------------------------------------------
    // TextureView
    // --------------------------------------------------------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        try {
            val chars = caratteristiche[Lente.PRINCIPALE] ?: cameraManager.getCameraCharacteristics(backId)
            val mappa = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // La vista è verticale, il sensore orizzontale: confronto le proporzioni del lato lungo
            // sul corto e prendo la risoluzione più alta (fino a 1920) con proporzioni compatibili.
            val proporzione = maxOf(width, height).toDouble() / minOf(width, height).coerceAtLeast(1)
            val disponibili = mappa?.getOutputSizes(SurfaceTexture::class.java)?.filter { it.width <= 1920 }
            val taglia = disponibili
                ?.filter { kotlin.math.abs(it.width.toDouble() / it.height - proporzione) < 0.05 }
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: disponibili?.maxByOrNull { it.width.toLong() * it.height }
            if (taglia != null) {
                surface.setDefaultBufferSize(taglia.width, taglia.height)
            }
        } catch (t: Throwable) {
            logFile.appendText("Errore impostazione dimensione anteprima: ${describeError(t)}\n")
        }
        previewSurface = Surface(surface)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            apriFotocamera()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // --------------------------------------------------------
    // Rilevamento obiettivi disponibili
    // --------------------------------------------------------

    private fun equivalenteMm(c: CameraCharacteristics): Double {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 23.0
        val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return focal.toDouble()
        val diag = Math.sqrt((physSize.width * physSize.width + physSize.height * physSize.height).toDouble())
        if (diag <= 0.0) return focal.toDouble()
        return focal * 43.27 / diag
    }

    private fun rilevaLenti() {
        try {
            val ids = cameraManager.cameraIdList
            var trovato: String? = null
            for (id in ids) {
                val c = cameraManager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    c.physicalCameraIds.isNotEmpty()
                ) {
                    trovato = id
                    break
                }
            }
            backId = trovato ?: ids.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.first()

            val logicalChars = cameraManager.getCameraCharacteristics(backId)
            val physIds = logicalChars.physicalCameraIds.toList()

            if (physIds.size >= 3) {
                val conFocale = physIds.map { it to equivalenteMm(cameraManager.getCameraCharacteristics(it)) }
                    .sortedBy { it.second }
                val ultraId = conFocale.first().first
                val teleId = conFocale.last().first
                val principaleId = conFocale[conFocale.size / 2].first
                fisici[Lente.ULTRA] = ultraId
                fisici[Lente.PRINCIPALE] = principaleId
                fisici[Lente.TELE] = teleId
            } else if (physIds.size == 2) {
                val conFocale = physIds.map { it to equivalenteMm(cameraManager.getCameraCharacteristics(it)) }
                    .sortedBy { it.second }
                fisici[Lente.ULTRA] = conFocale.first().first
                fisici[Lente.TELE] = conFocale.last().first
                fisici[Lente.PRINCIPALE] = null
            } else {
                fisici[Lente.ULTRA] = null
                fisici[Lente.PRINCIPALE] = null
                fisici[Lente.TELE] = null
            }

            for (l in Lente.values()) {
                val fid = fisici[l]
                caratteristiche[l] = if (fid != null) cameraManager.getCameraCharacteristics(fid) else logicalChars
            }
        } catch (t: Throwable) {
            logFile.appendText("Errore rilevaLenti: ${describeError(t)}\n")
        }
    }

    // --------------------------------------------------------
    // Apertura fotocamera e sessione
    // --------------------------------------------------------

    private fun apriFotocamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraHandler.post {
            try {
                val latch = CountDownLatch(1)
                cameraManager.openCamera(backId, callbackExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) {
                        cameraDevice = c
                        latch.countDown()
                    }
                    override fun onDisconnected(c: CameraDevice) {
                        c.close()
                        cameraDevice = null
                    }
                    override fun onError(c: CameraDevice, error: Int) {
                        c.close()
                        cameraDevice = null
                        latch.countDown()
                    }
                })
                latch.await(8, TimeUnit.SECONDS)
                if (cameraDevice != null) {
                    configuraSessione(Lente.PRINCIPALE)
                    uiHandler.post {
                        if (scenarioSelezionato != null) bottoneScatta.isEnabled = true
                    }
                } else {
                    uiHandler.post { statoView.text = "Non riesco ad aprire la fotocamera. Riprova a riaprire l'app." }
                }
            } catch (t: Throwable) {
                logFile.appendText("Errore apertura fotocamera: ${describeError(t)}\n")
                uiHandler.post { statoView.text = "Errore nell'apertura della fotocamera: ${describeError(t)}" }
            }
        }
    }

    /** Deve essere chiamata dal thread della fotocamera. */
    private fun configuraSessione(lente: Lente) {
        val cam = cameraDevice ?: return
        val anteprima = previewSurface ?: return
        if (lente == lenteAttuale && session != null && sessionePronta) return

        sessionePronta = false
        val chiusuraPrecedente = chiusuraSessione
        try {
            session?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        // Attende che la fotocamera confermi la chiusura della sessione precedente:
        // passare subito al nuovo obiettivo senza aspettare può bloccare la fotocamera (timeout).
        try {
            chiusuraPrecedente?.await(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
        try {
            jpegReader?.close()
        } catch (_: Throwable) {
        }

        try {
            val chars = caratteristiche[lente] ?: cameraManager.getCameraCharacteristics(backId)
            val mappa = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSize = mappa?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }

            val nuovoJpeg = if (jpegSize != null) ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2) else null
            nuovoJpeg?.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireNextImage()
                    if (img != null && !codaJpeg.offer(img)) img.close()
                } catch (_: Throwable) {
                }
            }, callbackHandler)
            jpegReader = nuovoJpeg

            val fisicoId = fisici[lente]
            // Primo tentativo: anteprima e JPEG sullo STESSO obiettivo fisico, così l'inquadratura
            // che vedi coincide con la foto. Se il telefono rifiuta la combinazione, secondo tentativo:
            // anteprima sull'obiettivo principale (logico) e solo il JPEG sull'obiettivo scelto.
            val tentativi = if (fisicoId != null) listOf(true, false) else listOf(false)
            var motivo = ""
            var anteprimaFedele = false
            for (fedele in tentativi) {
                val configAnteprima = OutputConfiguration(anteprima)
                val configJpeg = nuovoJpeg?.let { OutputConfiguration(it.surface) }
                if (fisicoId != null) {
                    configJpeg?.setPhysicalCameraId(fisicoId)
                    if (fedele) configAnteprima.setPhysicalCameraId(fisicoId)
                }
                val surfaces = listOfNotNull(configAnteprima, configJpeg)

                val latch = CountDownLatch(1)
                val chiusuraNuova = CountDownLatch(1)
                chiusuraSessione = chiusuraNuova
                var erroreCreazione = ""
                try {
                    cam.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, surfaces, callbackExecutor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    latch.countDown()
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    erroreCreazione = "il sistema della fotocamera ha rifiutato la combinazione anteprima+JPEG per l'obiettivo $lente"
                                    latch.countDown()
                                }
                                override fun onClosed(s: CameraCaptureSession) {
                                    chiusuraNuova.countDown()
                                }
                            }
                        )
                    )
                } catch (t: Throwable) {
                    erroreCreazione = describeError(t)
                }
                val completata = latch.await(8, TimeUnit.SECONDS)
                if (session != null) {
                    anteprimaFedele = fedele
                    motivo = ""
                    break
                }
                motivo = when {
                    !completata -> "tempo scaduto in attesa della fotocamera"
                    erroreCreazione.isNotEmpty() -> erroreCreazione
                    else -> "motivo sconosciuto"
                }
                logFile.appendText("Sessione non configurata (lente=$lente, anteprimaSuObiettivo=$fedele, jpeg=$jpegSize): $motivo\n")
                if (!completata) break
                try {
                    chiusuraNuova.await(3, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
            }
            val sess = session
            if (sess == null) {
                uiHandler.post {
                    statoView.text = "Non riesco ad avviare l'anteprima (obiettivo: $lente).\n$motivo\n\nPremi 'Copia report' e mandami il testo per sistemarlo."
                }
                return
            }
            lenteAttuale = lente
            if (fisicoId != null && !anteprimaFedele) {
                logFile.appendText("Anteprima sull'obiettivo principale: il telefono non accetta l'anteprima sull'obiettivo $lente\n")
                uiHandler.post {
                    statoView.text = statoView.text.toString() +
                        "\n\nATTENZIONE: su questo telefono l'anteprima non può mostrare l'obiettivo $lente. " +
                        "Vedi l'obiettivo principale, ma la foto esce dall'obiettivo $lente: l'inquadratura sarà diversa."
                }
            }

            val richiesta = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            richiesta.addTarget(anteprima)
            richiesta.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            sess.setRepeatingRequest(richiesta.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { ultimoExpoNs = it }
                    res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { ultimoIso = it }
                    res.get(CaptureResult.CONTROL_AF_STATE)?.let { ultimoAfState = it }
                    res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { ultimoFocusDistance = it }
                }
            }, callbackHandler)
            sessionePronta = true
        } catch (t: Throwable) {
            logFile.appendText("Errore configuraSessione (lente=$lente): ${describeError(t)}\n")
            uiHandler.post { statoView.text = "Errore nella configurazione della fotocamera (obiettivo: $lente):\n${describeError(t)}" }
        }
    }

    // --------------------------------------------------------
    // Diagnostica e qualità
    // --------------------------------------------------------

    private fun avviaDiagnostica() {
        statoView.text = "Raccolgo le informazioni sulle fotocamere..."
        cameraHandler.post {
            val testo = try {
                diagnostica()
            } catch (t: Throwable) {
                "Errore diagnostica: ${describeError(t)}"
            }
            uiHandler.post { statoView.text = testo }
        }
    }

    private fun elenco(valori: IntArray?): String = valori?.joinToString(",") ?: "-"

    private fun nomeEstensione(e: Int): String = when (e) {
        0 -> "AUTOMATICO"
        1 -> "RITOCCO_VOLTO"
        2 -> "BOKEH"
        3 -> "HDR"
        4 -> "NOTTE"
        else -> "ESTENSIONE_$e"
    }

    private fun diagnostica(): String = buildString {
        append("=== DIAGNOSTICA ===\n")
        append("${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Fotocamera posteriore usata dall'app: $backId\n\n")
        for (id in cameraManager.cameraIdList) {
            try {
                val c = cameraManager.getCameraCharacteristics(id)
                val lato = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "POST"
                    CameraCharacteristics.LENS_FACING_FRONT -> "ANT"
                    else -> "EXT"
                }
                val focali = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(",") ?: "-"
                append("Camera $id [$lato] fisiche=${c.physicalCameraIds} focale=${focali}mm eq35=${equivalenteMm(c).roundToInt()}mm\n")
                append("  rumore=${elenco(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES))}")
                append("  bordi=${elenco(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES))}")
                append("  stabilizzatore=${elenco(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))}\n")
                val mappa = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = mappa?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                append("  JPEG massimo=${jpeg ?: "-"}\n")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val estensioni = try {
                        cameraManager.getCameraExtensionCharacteristics(id).supportedExtensions
                            .joinToString(",") { nomeEstensione(it) }
                            .ifEmpty { "nessuna" }
                    } catch (t: Throwable) {
                        "errore ${t.javaClass.simpleName}"
                    }
                    append("  estensioni=$estensioni\n")
                }
            } catch (t: Throwable) {
                append("Camera $id: errore ${describeError(t)}\n")
            }
        }
        append("\nObiettivi riconosciuti dall'app:\n")
        for (l in Lente.values()) {
            append("  $l -> ${fisici[l]?.let { "fisica $it" } ?: "logica $backId"}\n")
        }
    }

    /** Chiede alla fotocamera la lavorazione migliore (rumore, nitidezza, colori), se il telefono la offre. */
    private fun impostaQualita(b: CaptureRequest.Builder) {
        val c = try {
            cameraManager.getCameraCharacteristics(backId)
        } catch (_: Throwable) {
            return
        }
        fun usa(disponibili: CameraCharacteristics.Key<IntArray>, chiave: CaptureRequest.Key<Int>, voluto: Int) {
            if (c.get(disponibili)?.contains(voluto) == true) b.set(chiave, voluto)
        }
        usa(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        usa(
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
            CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY
        )
        usa(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )
        usa(
            CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
            CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
        )
        usa(
            CameraCharacteristics.SHADING_AVAILABLE_MODES,
            CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY
        )
        usa(
            CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES,
            CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
        )
        usa(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        )
    }

    // --------------------------------------------------------
    // Scatto
    // --------------------------------------------------------

    private fun avviaScatto() {
        if (inElaborazione) return
        val sc = scenarioSelezionato ?: return
        inElaborazione = true
        bottoneScatta.isEnabled = false
        uiHandler.post { statoView.text = "Preparo l'obiettivo per: ${sc.nome}..." }
        cameraHandler.post {
            try {
                eseguiScatto(sc)
            } catch (t: Throwable) {
                logFile.appendText("Errore scatto: ${describeError(t)}\n")
                uiHandler.post { statoView.text = "Errore durante lo scatto: ${describeError(t)}" }
            } finally {
                inElaborazione = false
                uiHandler.post { bottoneScatta.isEnabled = true }
            }
        }
    }

    private fun eseguiScatto(sc: Scenario) {
        configuraSessione(sc.lente)
        val cam = cameraDevice ?: return
        val sess = session ?: return
        val chars = caratteristiche[sc.lente] ?: cameraManager.getCameraCharacteristics(backId)
        val jpegR = jpegReader

        uiHandler.post { statoView.text = "Leggo la luce della scena..." }
        Thread.sleep(400) // lascia convergere la misurazione automatica già in corso

        if (sc.fuoco != Fuoco.INFINITO) {
            uiHandler.post { statoView.text = "Metto a fuoco..." }
            val inizio = System.currentTimeMillis()
            while (ultimoAfState != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED &&
                ultimoAfState != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED &&
                System.currentTimeMillis() - inizio < 1500
            ) {
                Thread.sleep(50)
            }
        }
        val distanzaFuoco = if (sc.fuoco == Fuoco.INFINITO) 0f else ultimoFocusDistance

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expoRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val risultato = Solver.risolvi(
            sc, ultimoExpoNs, ultimoIso,
            expoRange?.lower ?: 1_000_000L, expoRange?.upper ?: 2_000_000_000L,
            isoRange?.lower ?: 50, isoRange?.upper ?: 3200
        )

        uiHandler.post { statoView.text = "Scatto in corso...\nTempo: 1/${(1_000_000_000.0 / risultato.tempoNs).roundToInt()} s   ISO: ${risultato.iso}" }

        val richiesta = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        jpegR?.let { richiesta.addTarget(it.surface) }
        richiesta.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        richiesta.set(CaptureRequest.SENSOR_EXPOSURE_TIME, risultato.tempoNs)
        richiesta.set(CaptureRequest.SENSOR_SENSITIVITY, risultato.iso)
        val frameMin = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        if (frameMin != null) richiesta.set(CaptureRequest.SENSOR_FRAME_DURATION, minOf(risultato.tempoNs + 10_000_000L, frameMin))
        richiesta.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        richiesta.set(CaptureRequest.LENS_FOCUS_DISTANCE, distanzaFuoco)
        richiesta.set(CaptureRequest.CONTROL_AWB_MODE, sc.bilanciamento.modo)
        richiesta.set(CaptureRequest.JPEG_ORIENTATION, orientamentoJpeg(chars))
        richiesta.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        impostaQualita(richiesta)

        val latch = CountDownLatch(1)
        var result: TotalCaptureResult? = null
        sess.capture(richiesta.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                result = res
                latch.countDown()
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                latch.countDown()
            }
        }, callbackHandler)
        if (!latch.await(10, TimeUnit.SECONDS) || result == null) {
            uiHandler.post { statoView.text = "Scatto non riuscito, riprova." }
            return
        }
        val res = result!!
        val ts = res.get(CaptureResult.SENSOR_TIMESTAMP)

        val sloggiato = sc.nome.lowercase(Locale.ITALY)
            .replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val base = "${sloggiato}_${System.currentTimeMillis()}"

        var nomeFileJpeg = ""
        var bytesJpeg: ByteArray? = null
        if (jpegR != null) {
            var img: Image? = null
            val fine = System.currentTimeMillis() + 4000
            while (img == null && System.currentTimeMillis() < fine) {
                val candidato = codaJpeg.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (ts == null || candidato.timestamp == ts) img = candidato else candidato.close()
            }
            if (img != null) {
                bytesJpeg = try {
                    estraiBytes(img)
                } catch (t: Throwable) {
                    logFile.appendText("Errore lettura JPEG: ${describeError(t)}\n")
                    null
                } finally {
                    img.close()
                }
                if (bytesJpeg != null) nomeFileJpeg = salvaBytes(bytesJpeg, "$base.jpg")
            }
        }

        var nomeRitratto = ""
        var erroreRitratto = ""
        if (sc.sfocaSfondo && bytesJpeg != null) {
            uiHandler.post { statoView.text = "Foto scattata. Riconosco la persona e sfoco lo sfondo..." }
            try {
                nomeRitratto = salvaBytes(SfocaturaSfondo.applica(bytesJpeg), "${base}_ritratto.jpg")
            } catch (t: Throwable) {
                erroreRitratto = t.message ?: t.javaClass.simpleName
                logFile.appendText("Errore sfocatura sfondo: ${describeError(t)}\n")
            }
        }

        val expoText = "1/${(1_000_000_000.0 / risultato.tempoNs).roundToInt()} s"
        val testo = buildString {
            append("Scenario: ${sc.nome}\n")
            append("Obiettivo: ${sc.lente}\n")
            append("Tempo di scatto: $expoText   ISO: ${risultato.iso}\n\n")
            append("${sc.consiglio}\n\n")
            if (nomeFileJpeg.isNotEmpty()) append("Foto: $nomeFileJpeg\n")
            if (nomeRitratto.isNotEmpty()) append("Foto con sfondo sfocato: $nomeRitratto\n")
            if (erroreRitratto.isNotEmpty()) append("Sfondo non sfocato: $erroreRitratto\n")
            append("\nCartella: Pictures/FotoScenari")
        }
        uiHandler.post { statoView.text = testo }
    }

    private fun orientamentoJpeg(chars: CameraCharacteristics): Int {
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        @Suppress("DEPRECATION")
        val rotazione = windowManager.defaultDisplay.rotation
        val gradi = when (rotazione) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - gradi + 360) % 360
    }

    private fun estraiBytes(img: Image): ByteArray {
        val buffer = img.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun salvaBytes(bytes: ByteArray, nome: String): String {
        return try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FotoScenari")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return ""
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            nome
        } catch (t: Throwable) {
            logFile.appendText("Errore salvataggio $nome: ${describeError(t)}\n")
            ""
        }
    }

    private fun chiudiTutto() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        try {
            cameraDevice?.close()
        } catch (_: Throwable) {
        }
        try {
            jpegReader?.close()
        } catch (_: Throwable) {
        }
    }
}
