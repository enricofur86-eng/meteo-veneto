package com.meteoarpav.veneto

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File

class MainActivity : Activity() {

    private lateinit var spinner: Spinner
    private lateinit var stato: TextView
    private lateinit var progresso: ProgressBar
    private lateinit var contenuto: LinearLayout

    private var previsioni: Previsioni? = null
    private var zonaScelta: String = ZONA_PREDEFINITA
    private val fileCache by lazy { File(filesDir, "bollettino_utenti.xml") }

    private val blu = Color.parseColor("#1E6FB8")
    private val grigio = Color.parseColor("#5F6B76")
    private val sfondoScheda = Color.parseColor("#EEF4FA")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        zonaScelta = getSharedPreferences("impostazioni", Context.MODE_PRIVATE)
            .getString("zona", ZONA_PREDEFINITA) ?: ZONA_PREDEFINITA

        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        spinner = Spinner(this)
        progresso = ProgressBar(this).apply { visibility = View.GONE }
        barra.addView(spinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        barra.addView(progresso, LinearLayout.LayoutParams(dp(32), dp(32)))
        radice.addView(barra)

        stato = testo("", 13f, grigio).apply { setPadding(dp(16), 0, dp(16), dp(6)) }
        radice.addView(stato)

        contenuto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        val scorri = ScrollView(this).apply { addView(contenuto) }
        radice.addView(scorri, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)

        // Mostra subito l'ultimo bollettino salvato (funziona anche senza rete)
        if (fileCache.exists()) {
            try { mostra(fileCache.inputStream().use { ArpavParser.parse(it) }, daCache = true) } catch (e: Exception) { fileCache.delete() }
        }
        aggiorna()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Aggiorna").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Informazioni")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { aggiorna(); true }
        2 -> { informazioni(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun aggiorna() {
        progresso.visibility = View.VISIBLE
        Rete.inBackground {
            try {
                val dati = Rete.scarica(Rete.URL_BOLLETTINO)
                val nuove = ArpavParser.parse(ByteArrayInputStream(dati))
                if (nuove.zone.isEmpty()) throw Exception("Il bollettino non contiene previsioni")
                fileCache.writeBytes(dati)
                Rete.sulPrincipale {
                    progresso.visibility = View.GONE
                    mostra(nuove, daCache = false)
                }
            } catch (e: Exception) {
                Rete.sulPrincipale {
                    progresso.visibility = View.GONE
                    if (previsioni == null) {
                        contenuto.removeAllViews()
                        contenuto.addView(testo(
                            "Impossibile scaricare le previsioni.\nControlla la connessione e premi Aggiorna.\n\n(${e.message})",
                            15f, grigio))
                    } else {
                        stato.text = stato.text.toString() + "  •  offline: mostro l'ultimo bollettino salvato"
                    }
                }
            }
        }
    }

    private fun mostra(p: Previsioni, daCache: Boolean) {
        previsioni = p
        val nomi = p.zone.map { it.nome }
        val adattatore = ArrayAdapter(this, android.R.layout.simple_spinner_item, nomi)
        adattatore.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.onItemSelectedListener = null
        spinner.adapter = adattatore
        val indice = p.zone.indexOfFirst { it.id == zonaScelta }.coerceAtLeast(0)
        spinner.setSelection(indice, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val z = p.zone[pos]
                if (z.id != zonaScelta) {
                    zonaScelta = z.id
                    getSharedPreferences("impostazioni", Context.MODE_PRIVATE)
                        .edit().putString("zona", z.id).apply()
                    disegna()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        stato.text = "Emesso il ${p.emissione}" +
            (if (p.aggiornamento.isNotBlank()) "  •  aggiornato il ${p.aggiornamento}" else "") +
            (if (daCache) "  •  copia salvata" else "")
        disegna()
    }

    private fun disegna() {
        val p = previsioni ?: return
        contenuto.removeAllViews()
        val zona = p.zone.firstOrNull { it.id == zonaScelta } ?: p.zone.firstOrNull() ?: return

        zona.scadenze.forEach { contenuto.addView(schedaScadenza(it)) }

        // Bollettino testuale "Meteo Veneto" (se la zona è di montagna, anche Dolomiti Meteo)
        val montagna = zona.id in setOf("1", "2", "3", "4", "8")
        p.bollettini
            .filter { it.id == "MV" || (montagna && it.id == "DM") }
            .forEach { aggiungiBollettino(it) }

        contenuto.addView(testo(
            "Fonte dei dati: ARPAV – Servizio Meteorologico del Veneto (meteo.arpa.veneto.it). " +
                "App non ufficiale, gratuita e senza pubblicità.", 12f, grigio
        ).apply { setPadding(0, dp(20), 0, 0) })
    }

    private fun schedaScadenza(s: Scadenza): View {
        val scheda = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(sfondoScheda)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val icona = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        scheda.addView(icona, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(12) })
        Rete.caricaImmagine(icona, s.simbolo, cacheDir)

        val colonna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colonna.addView(testo(maiuscola(s.data), 16f, blu, grassetto = true))
        val cielo = s.valore("Cielo")
        if (cielo.isNotEmpty()) colonna.addView(testo(cielo, 15f, Color.BLACK))
        val temp = s.valore("Temperatura")
        if (temp.isNotEmpty()) colonna.addView(testo(gradi(temp), 20f, Color.BLACK, grassetto = true))

        val pioggia = s.valore("Precipitazioni")
        val prob = s.valore("Probabilita' precipitazione")
        if (pioggia.isNotEmpty() || prob.isNotEmpty()) {
            colonna.addView(riga("Precipitazioni", listOf(pioggia, prob).filter { it.isNotEmpty() }.joinToString(" – ")))
        }
        val giaMostrati = setOf("simbolo", "cielo", "temperatura", "precipitazioni", "probabilita' precipitazione")
        s.voci.filter { it.tipo != "image" && it.titolo.lowercase() !in giaMostrati && it.valore.isNotBlank() }
            .forEach { colonna.addView(riga(etichetta(it.titolo), gradi(it.valore.trim()))) }

        scheda.addView(colonna, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return scheda
    }

    private fun aggiungiBollettino(b: Bollettino) {
        contenuto.addView(testo(b.nome.ifBlank { "Bollettino" }, 20f, blu, grassetto = true)
            .apply { setPadding(0, dp(18), 0, dp(2)) })
        if (b.titolo.isNotBlank()) contenuto.addView(testo(b.titolo, 13f, grigio))
        if (b.avviso.isNotBlank()) contenuto.addView(html(b.avviso).apply {
            setBackgroundColor(Color.parseColor("#FFF3CD")); setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        if (b.evoluzione.isNotBlank()) contenuto.addView(html(b.evoluzione))
        if (b.fenomeni.isNotBlank()) contenuto.addView(html(b.fenomeni))
        b.giorni.forEach { g ->
            contenuto.addView(testo(maiuscola(g.data), 16f, blu, grassetto = true)
                .apply { setPadding(0, dp(14), 0, dp(4)) })
            if (g.immagine.isNotBlank()) {
                val mappa = ImageView(this).apply { adjustViewBounds = true }
                contenuto.addView(mappa, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                Rete.caricaImmagine(mappa, g.immagine, cacheDir)
            }
            if (g.testo.isNotBlank()) contenuto.addView(html(g.testo))
        }
    }

    private fun informazioni() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Meteo Veneto")
            .setMessage(
                "Previsioni del bollettino ufficiale ARPAV per le 18 zone del Veneto, " +
                    "fino a 5 giorni.\n\nNessuna pubblicità, nessun tracciamento: l'app si collega " +
                    "solo al sito dell'ARPAV.\n\nApp non ufficiale, non affiliata ad ARPAV."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- piccoli aiuti per l'interfaccia ----

    private fun testo(t: String, sp: Float, colore: Int, grassetto: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(colore)
        if (grassetto) setTypeface(typeface, Typeface.BOLD)
    }

    private fun riga(titolo: String, valore: String) = TextView(this).apply {
        text = Html.fromHtml("<b>$titolo:</b> ${Html.escapeHtml(valore)}", Html.FROM_HTML_MODE_COMPACT)
        textSize = 14f; setTextColor(Color.parseColor("#26323C"))
        setPadding(0, dp(2), 0, 0)
    }

    private fun html(h: String) = TextView(this).apply {
        text = Html.fromHtml(h, Html.FROM_HTML_MODE_COMPACT)
        textSize = 15f; setTextColor(Color.parseColor("#26323C"))
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun etichetta(t: String) = when (t.lowercase()) {
        "attendibilita'" -> "Attendibilità"
        "probabilita' precipitazione" -> "Probabilità di pioggia"
        else -> if (t.endsWith("a'")) t.dropLast(2) + "à" else t
    }

    /** "max 8/10 C" → "max 8/10 °C" */
    private fun gradi(v: String) = v.replace(Regex("(\\d)\\s*C\\b"), "$1 °C")

    private fun maiuscola(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Zona 7 = Veneziano orientale */
        const val ZONA_PREDEFINITA = "7"
    }
}
