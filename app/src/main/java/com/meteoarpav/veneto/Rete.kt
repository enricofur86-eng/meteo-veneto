package com.meteoarpav.veneto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Rete {
    const val URL_BOLLETTINO =
        "https://meteo.arpa.veneto.it/meteo/bollettini/it/xml/bollettino_utenti.xml"

    private val esecutore = Executors.newFixedThreadPool(4)
    private val principale = Handler(Looper.getMainLooper())
    private val cacheImmagini = LruCache<String, Bitmap>(60)

    fun inBackground(lavoro: () -> Unit) = esecutore.execute(lavoro)
    fun sulPrincipale(lavoro: () -> Unit) = principale.post(lavoro)

    fun scarica(indirizzo: String): ByteArray {
        var url = URL(indirizzo)
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "MeteoVeneto-Android/1.0")
            c.instanceFollowRedirects = true
            val codice = c.responseCode
            val dove = c.getHeaderField("Location")
            if (codice in 301..308 && dove != null) {
                // redirect (anche tra http e https): riprova al nuovo indirizzo
                c.disconnect()
                url = URL(url, dove)
                return@repeat
            }
            if (codice != 200) {
                c.disconnect()
                throw Exception("Il server ARPAV ha risposto con codice $codice")
            }
            val byte = c.inputStream.use { it.readBytes() }
            c.disconnect()
            return byte
        }
        throw Exception("Troppi reindirizzamenti")
    }

    /** Scarica l'immagine (icona meteo o mappa) e la mette nella ImageView. */
    fun caricaImmagine(vista: ImageView, indirizzo: String, cartellaCache: File) {
        if (indirizzo.isBlank()) return
        val completo = if (indirizzo.startsWith("http")) indirizzo
        else "https://meteo.arpa.veneto.it/" + indirizzo.trimStart('/')
        vista.tag = completo
        cacheImmagini.get(completo)?.let { vista.setImageBitmap(it); return }

        val file = File(cartellaCache, "img_" + completo.hashCode().toString().replace('-', 'm'))
        inBackground {
            val bmp = try {
                val byte = try {
                    scarica(completo).also { file.writeBytes(it) }
                } catch (e: Exception) {
                    if (file.exists()) file.readBytes() else null
                }
                byte?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (e: Exception) { null }
            if (bmp != null) {
                cacheImmagini.put(completo, bmp)
                sulPrincipale { if (vista.tag == completo) vista.setImageBitmap(bmp) }
            }
        }
    }
}
