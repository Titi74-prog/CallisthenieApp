package com.thierry.programme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v6.3 — Lecture d'une capture du rapport Xiaomi S400 (Xiaomi Home) par OCR ML Kit, hors ligne.
 * Exposé dans la WebView sous le nom « CaptureBridge ».
 *  - pickImage()      : ouvre le sélecteur d'images (bouton « 📷 Importer une capture »)
 *  - retryHighRes()   : relit la dernière capture en agrandissant l'image (capture trop compressée)
 *  - takePending()    : récupère un résultat arrivé avant le chargement de la page
 * Le résultat est envoyé à la page via window.onCaptureData(json) :
 *  {status:'loading'} puis {status:'ok', id, width, height, hi, lines:[{t,x,y,w,h}]} ou {status:'error', message}
 * Une capture longue est découpée en bandes (BitmapRegionDecoder) : pas de chargement complet en mémoire.
 */
class CaptureBridge(private val activity: ComponentActivity, private val webView: WebView) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var pending: String? = null
    private val cacheFile: File get() = File(activity.cacheDir, "s400_capture.img")

    private val picker: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) importUri(uri)
        }

    // ───────── Méthodes appelées depuis le JavaScript ─────────

    @JavascriptInterface
    fun pickImage() {
        activity.runOnUiThread { try { picker.launch("image/*") } catch (_: Exception) { } }
    }

    @JavascriptInterface
    fun retryHighRes() {
        activity.runOnUiThread { process(true) }
    }

    @JavascriptInterface
    fun takePending(): String? {
        val p = pending
        pending = null
        return p
    }

    // ───────── Entrées natives ─────────

    /** Capture partagée depuis Xiaomi Home (copiée par ImportCaptureActivity). */
    fun importFile(path: String) {
        val f = File(path)
        if (f.absolutePath != cacheFile.absolutePath) {
            try { f.copyTo(cacheFile, overwrite = true); f.delete() } catch (_: Exception) { }
        }
        process(false)
    }

    /** Image choisie dans le sélecteur : copie immédiate (l'autorisation d'accès est temporaire). */
    private fun importUri(uri: Uri) {
        send(JSONObject().put("status", "loading"))
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { input.copyTo(it) }
                    } != null
                } catch (_: Exception) { false }
            }
            if (ok) process(false)
            else send(JSONObject().put("status", "error").put("message", "Impossible d'ouvrir l'image"))
        }
    }

    fun destroy() {
        scope.cancel()
        try { recognizer.close() } catch (_: Exception) { }
    }

    // ───────── OCR ─────────

    private fun process(hiRes: Boolean) {
        if (!cacheFile.exists()) {
            send(JSONObject().put("status", "error").put("message", "Aucune capture à relire"))
            return
        }
        send(JSONObject().put("status", "loading").put("hi", hiRes))
        scope.launch {
            val json = try {
                recognize(hiRes)
            } catch (e: OutOfMemoryError) {
                JSONObject().put("status", "error").put("message", "Image trop grande pour la mémoire disponible")
            } catch (e: Exception) {
                JSONObject().put("status", "error").put("message", e.message ?: e.toString())
            }
            send(json)
        }
    }

    private suspend fun recognize(hiRes: Boolean): JSONObject {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) throw IllegalStateException("Format d'image non reconnu")

        // Échelle : petite capture → agrandie à ~1 080 px de large ; mode « meilleure résolution » → ×2
        var scale = if (w < 900) 1080f / w else 1f
        if (hiRes) scale *= 2f
        scale = min(scale, 2600f / w).coerceAtLeast(1f)

        // Bandes de ~1 600 px (après mise à l'échelle) avec recouvrement pour ne couper aucune ligne
        val tile = max(400, (1600f / scale).roundToInt())
        val overlap = min(160, tile / 4)
        val step = tile - overlap

        val lines = JSONArray()
        val region: BitmapRegionDecoder? = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(cacheFile.absolutePath, false)
            } catch (_: Exception) { null }
        }
        // Secours (format non pris en charge par le décodeur de régions) : image complète, éventuellement réduite
        val full: Bitmap? = if (region == null) withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)
        } else null
        if (region == null && full == null) throw IllegalStateException("Impossible de décoder l'image")

        try {
            var top = 0
            while (top < h) {
                val bottom = min(h, top + tile)
                val first = top == 0
                val last = bottom >= h
                val src = withContext(Dispatchers.IO) {
                    val r = Rect(0, top, w, bottom)
                    if (region != null) region.decodeRegion(r, BitmapFactory.Options())
                    else Bitmap.createBitmap(full!!, 0, top, w, bottom - top)
                }
                val bmp = if (scale != 1f) withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(src, (w * scale).roundToInt(), ((bottom - top) * scale).roundToInt(), true)
                        .also { if (it !== src) src.recycle() }
                } else src

                val text = runOcr(bmp)
                bmp.recycle()

                // On ne garde que les lignes dont le centre tombe dans la partie « propre » de la bande
                val keepFrom = top + if (first) 0 else overlap / 2
                val keepTo = if (last) h else bottom - overlap / 2
                for (block in text.textBlocks) for (line in block.lines) {
                    val b = line.boundingBox ?: continue
                    val x = b.left / scale
                    val y = top + b.top / scale
                    val lw = b.width() / scale
                    val lh = b.height() / scale
                    val cy = y + lh / 2
                    if (cy < keepFrom || cy >= keepTo) continue
                    lines.put(JSONObject()
                        .put("t", line.text)
                        .put("x", x.roundToInt()).put("y", y.roundToInt())
                        .put("w", lw.roundToInt()).put("h", lh.roundToInt()))
                }
                if (last) break
                top += step
            }
        } finally {
            region?.recycle()
            full?.recycle()
        }

        return JSONObject()
            .put("status", "ok")
            .put("id", System.currentTimeMillis())
            .put("width", w).put("height", h)
            .put("hi", hiRes)
            .put("lines", lines)
    }

    private suspend fun runOcr(bmp: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun send(json: JSONObject) {
        val s = json.toString()
        if (json.optString("status") != "loading") pending = s
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "(function(){if(window.onCaptureData){window.onCaptureData($s);return 1;}return 0;})();"
            ) { r -> if (r == "1" && pending == s) pending = null }
        }
    }
}
