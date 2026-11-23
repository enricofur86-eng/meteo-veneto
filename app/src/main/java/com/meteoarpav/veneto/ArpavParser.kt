package com.meteoarpav.veneto

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Una voce di previsione, es. title="Cielo" value="Sereno". */
data class Voce(val titolo: String, val tipo: String, val valore: String)

/** Una scadenza, es. "lun 23 novembre mattina". */
data class Scadenza(val data: String, val voci: List<Voce>) {
    fun valore(prefissoTitolo: String): String =
        voci.firstOrNull { it.titolo.equals(prefissoTitolo, ignoreCase = true) }?.valore?.trim() ?: ""

    val simbolo: String get() = voci.firstOrNull { it.tipo == "image" }?.valore?.trim() ?: ""
}

/** Una zona del Veneto (18 in tutto). */
data class Zona(val id: String, val nome: String, val scadenze: List<Scadenza>)

/** Un giorno del bollettino testuale Meteo Veneto. */
data class GiornoBollettino(val data: String, val immagine: String, val testo: String)

data class Bollettino(
    val id: String,
    val nome: String,
    val titolo: String,
    val evoluzione: String,
    val avviso: String,
    val fenomeni: String,
    val giorni: List<GiornoBollettino>
)

data class Previsioni(
    val emissione: String,
    val aggiornamento: String,
    val zone: List<Zona>,
    val bollettini: List<Bollettino>
)

/**
 * Legge il file XML pubblico dell'ARPAV:
 * https://meteo.arpa.veneto.it/meteo/bollettini/it/xml/bollettino_utenti.xml
 */
object ArpavParser {

    fun parse(input: InputStream): Previsioni {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null) // la codifica viene letta dall'intestazione XML

        var emissione = ""
        var aggiornamento = ""
        val zone = mutableListOf<Zona>()
        val bollettini = mutableListOf<Bollettino>()

        // stato zona corrente
        var zonaId = ""; var zonaNome = ""
        var scadenze = mutableListOf<Scadenza>()
        var dentroZona = false
        // stato scadenza corrente
        var scadData = ""
        var voci = mutableListOf<Voce>()
        var dentroScadenzaZona = false
        // stato bollettino corrente
        var dentroBollettino = false
        var bId = ""; var bNome = ""; var bTitolo = ""
        var bEvol = ""; var bAvviso = ""; var bFen = ""
        var giorni = mutableListOf<GiornoBollettino>()
        var dentroGiorno = false
        var gData = ""; var gImg = ""; var gTesto = ""

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "data_emissione" -> emissione = p.attr("date")
                    "data_aggiornamento" -> aggiornamento = p.attr("date")
                    "meteogramma" -> {
                        dentroZona = true
                        zonaId = p.attr("zoneid"); zonaNome = p.attr("name")
                        scadenze = mutableListOf()
                    }
                    "scadenza" -> if (dentroZona) {
                        dentroScadenzaZona = true
                        scadData = p.attr("data"); voci = mutableListOf()
                    }
                    "previsione" -> if (dentroScadenzaZona) {
                        voci.add(Voce(p.attr("title"), p.attr("type"), p.attr("value")))
                    }
                    "bollettino" -> {
                        dentroBollettino = true
                        bId = p.attr("bollettinoid"); bNome = p.attr("name"); bTitolo = p.attr("title")
                        bEvol = ""; bAvviso = ""; bFen = ""
                        giorni = mutableListOf()
                    }
                    "evoluzionegenerale" -> if (dentroBollettino) bEvol = leggiTesto(p)
                    "avviso" -> if (dentroBollettino) bAvviso = leggiTesto(p)
                    "fenomeniparticolari" -> if (dentroBollettino) bFen = leggiTesto(p)
                    "giorno" -> if (dentroBollettino) {
                        dentroGiorno = true
                        gData = p.attr("data"); gImg = ""; gTesto = ""
                    }
                    "img" -> if (dentroGiorno && gImg.isEmpty()) gImg = p.attr("src")
                    "text" -> if (dentroGiorno) gTesto = leggiTesto(p)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "scadenza" -> if (dentroScadenzaZona) {
                        scadenze.add(Scadenza(scadData, voci))
                        dentroScadenzaZona = false
                    }
                    "meteogramma" -> {
                        zone.add(Zona(zonaId, zonaNome, scadenze))
                        dentroZona = false
                    }
                    "giorno" -> if (dentroGiorno) {
                        giorni.add(GiornoBollettino(gData, gImg, gTesto))
                        dentroGiorno = false
                    }
                    "bollettino" -> if (dentroBollettino) {
                        bollettini.add(Bollettino(bId, bNome, bTitolo, bEvol, bAvviso, bFen, giorni))
                        dentroBollettino = false
                    }
                }
            }
            ev = p.next()
        }
        return Previsioni(emissione, aggiornamento, zone, bollettini)
    }

    private fun XmlPullParser.attr(nome: String): String = getAttributeValue(null, nome) ?: ""

    /** Legge tutto il testo dentro l'elemento corrente, saltando eventuali tag interni. */
    private fun leggiTesto(p: XmlPullParser): String {
        val sb = StringBuilder()
        var profondita = 1
        while (profondita > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> profondita++
                XmlPullParser.END_TAG -> profondita--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }
}
