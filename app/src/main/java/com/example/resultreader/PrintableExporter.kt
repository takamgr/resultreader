package com.example.resultreader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import android.annotation.SuppressLint
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 掲示用出力（HTML / CSV(S1) / PDF(A4横)）だけを担当する独立モジュール。
 * 既存のOCR・ROI・CsvExporter・TournamentPatternなど不変領域には一切影響しない。
 */
object PrintableExporter {

    // ───────── 共通ユーティリティ ─────────

    private fun todayName() = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    private fun todayDisplay() = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())

    private fun resultCsvFile(context: Context, pattern: TournamentPattern): File =
        File(context.getExternalFilesDir("ResultReader"), "result_${pattern.patternCode}_${todayName()}.csv")

    // 大会名の保存／取得（UIから任意で設定できる）
    @Suppress("unused")
    fun setEventTitle(context: Context, title: String) {
        context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
            .edit().putString("eventTitle", title.trim()).apply()
        Toast.makeText(context, "大会名を保存しました：$title", Toast.LENGTH_SHORT).show()
    }
    private fun getEventTitle(context: Context): String {
        val p = context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        return p.getString("eventTitle", "大会結果") ?: "大会結果"
    }

    // ───────── CSV（S1見出し） ─────────

    /** 共有/Excel用：ヘッダだけ Sec##→S# へ変換したCSVを Downloads/ResultReader に保存 */
    fun exportS1CsvToDownloads(context: Context, pattern: TournamentPattern): Uri? {
        val src = resultCsvFile(context, pattern)
        if (!src.exists()) { Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show(); return null }
        val lines = src.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return null

        val s1Header = lines.first().replace(Regex("""Sec0?(\d{1,2})""")) { m -> "S${m.groupValues[1].toInt()}" }
        val outText = buildString {
            append("\uFEFF") // Excel想定でBOM付与
            append(s1Header).append('\n')
            lines.drop(1).forEach { append(it).append('\n') }
        }
        val name = "result_${pattern.patternCode}_${todayName()}_S1.csv"
        return writeToDownloads(context, name, "text/csv", outText.toByteArray(Charsets.UTF_8))
    }

    // ───────── HTML（掲示用） ─────────

    /** 掲示用：クラスごとに区切り、不要列除外、日本語＋S1見出し、ヘッダ（大会名＋日付）付きでHTML保存 */
    fun exportPrintableHtmlToDownloads(context: Context, pattern: TournamentPattern): Uri? {
        val html = buildPrintableHtml(context, pattern) ?: run {
            Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show()
            return null
        }
        val name = "result_${pattern.patternCode}_${todayName()}_print.html"
        return writeToDownloads(context, name, "text/html", html.toByteArray(Charsets.UTF_8))
    }

    /** 旧：全クラスまとめて1枚のPDF（必要なら残す） */
    fun exportPrintablePdfToDownloads(context: Context, pattern: TournamentPattern) {
        val html = buildPrintableHtml(context, pattern) ?: run {
            Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = htmlToPdfBytes(context, html)
        writeToDownloads(context, "result_${pattern.patternCode}_${todayName()}_print.pdf", "application/pdf", bytes)
    }

    // ───────── クラス別 PDF ─────────

    /** 改ページ型：クラスごとに rowsPerPage で分割し連番PDFを保存（2枚目以降は大会名・日付を省略） */
    @Suppress("unused")
    fun exportPrintablePdfByClass(context: Context, pattern: TournamentPattern, rowsPerPage: Int = 15) {
        val src = resultCsvFile(context, pattern)
        if (!src.exists()) { Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show(); return }
        val lines = src.readLines(Charsets.UTF_8); if (lines.isEmpty()) return

        val header = lines.first().split(",").map { it.trim().removePrefix("\uFEFF") }
        val data = lines.drop(1).map { it.split(",") }

        fun jpHeader(h: String): String = when (h) {
            "EntryNo" -> "No"; "Name" -> "名前"; "Class" -> "クラス"
            "AmG" -> "AG"; "AmC" -> "AC"; "AmRank" -> "A順"
            "PmG" -> "PG"; "PmC" -> "PC"; "PmRank" -> "P順"
            "TotalG" -> "TG"; "TotalC" -> "TC"; "TotalRank" -> "順位"
            "時刻", "入力", "セッション" -> ""
            else -> h.replace(Regex("""Sec0?(\d{1,2})""")) { m -> "S${m.groupValues[1].toInt()}" }
        }
        val keepIdx = header.mapIndexedNotNull { i, h -> jpHeader(h).ifBlank { null }?.let { i } }
        val jpHeaders = keepIdx.map { jpHeader(header[it]) }
        val secCount = jpHeaders.count { it.matches(Regex("^S\\d+$")) }
        val is24 = secCount >= 24

        val prefs = context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        val order = when (prefs.getString("tournamentType", "beginner")) {
            "championship" -> listOf("IA", "IB", "NA", "NB")
            else -> listOf("オープン", "ビギナー")
        }
        val classIdx = header.indexOf("Class")
        val groupedAll = data.groupBy { row -> row.getOrNull(classIdx) ?: "" }

        val title = getEventTitle(context)
        val dateStr = todayDisplay()

        val orderedKeys = buildList {
            addAll(order)
            addAll(groupedAll.keys.filter { it.isNotBlank() && it !in order }.sorted())
        }

        var anySaved = false
        orderedKeys.forEach { clazz ->
            val list = groupedAll[clazz].orEmpty()
            if (list.isEmpty()) return@forEach
            val pages = list.chunked(rowsPerPage)
            pages.forEachIndexed { index, pageRows ->
                val html = buildClassPageHtml(
                    title = title, dateStr = dateStr, patternCode = pattern.patternCode,
                    clazz = clazz, headers = jpHeaders, keepIdx = keepIdx, rows = pageRows,
                    showGlobalHeader = (index == 0), is24 = is24
                )
                val name = "result_${pattern.patternCode}_${todayName()}_${clazz}${if (pages.size == 1) "" else "_${index + 1}"}.pdf"
                val uri = writeToDownloads(context, name, "application/pdf", htmlToPdfBytes(context, html))
                anySaved = anySaved or (uri != null)
            }
        }

        Toast.makeText(context, if (anySaved) "✅ クラス別PDFを保存しました" else "❌ PDF出力なし", Toast.LENGTH_SHORT).show()
    }
    // クラス別（複数ページ時は2枚目以降ヘッダ無し）1ページ分のHTMLを作る
    private fun buildClassPageHtml(
        title: String,
        dateStr: String,
        patternCode: String,
        clazz: String,
        headers: List<String>,
        keepIdx: List<Int>,
        rows: List<List<String>>,
        showGlobalHeader: Boolean,
        is24: Boolean
    ): String {
        return buildString {
            append("""
            <html><head><meta charset="UTF-8">
            <style>
              @page { size: A4 landscape; margin: 0; }
              html, body { margin:0; padding:0; }
              body{ font-family:sans-serif; padding:10mm; }
              .header{ margin-bottom:8px; }
              .title{ font-size:18pt; font-weight:700; }
              .sub{ font-size:11pt; color:#444; }
              h2{ margin:12px 0 6px 0; border-left:6px solid #333; padding-left:8px; }
              table{ width:100%; border-collapse:collapse; margin-top:6px; table-layout:fixed; }
              th,td{
                border:1px solid #999;
                padding:${if (is24) "3px 4px" else "4px 6px"};
                font-size:${if (is24) "9.5pt" else "10pt"};
                line-height:1.25;
                white-space:nowrap; text-align:center; overflow:hidden; text-overflow:clip;
              }
              thead th{ background:#f0f0f0; }
            </style></head><body>
        """.trimIndent())

            if (showGlobalHeader) {
                append("<div class='header'><div class='title'>")
                    .append(title)
                    .append("</div><div class='sub'>")
                    .append(dateStr)
                    .append("　/　形式: ").append(patternCode)
                    .append("</div></div>")
            }

            append("<h2>").append(clazz).append("クラス</h2>")
            append("<table><thead><tr>")
            headers.forEach { append("<th>").append(it).append("</th>") }
            append("</tr></thead><tbody>")
            rows.forEach { row ->
                append("<tr>")
                keepIdx.forEach { i -> append("<td>").append(row.getOrNull(i) ?: "").append("</td>") }
                append("</tr>")
            }
            append("</tbody></table></body></html>")
        }
    }


    /** 1クラス＝A4一枚に圧縮して保存（クラス数ぶんファイル） */
    @Suppress("unused")
    fun exportPrintablePdfPerClassSinglePage(context: Context, pattern: TournamentPattern, rowsTarget: Int = 15) {
        val src = resultCsvFile(context, pattern)
        if (!src.exists()) { Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show(); return }

        val lines = src.readLines(Charsets.UTF_8); if (lines.isEmpty()) return
        val header = lines.first().split(",").map { it.trim().removePrefix("\uFEFF") }
        val data = lines.drop(1).map { it.split(",") }

        fun jpHeader(h: String): String = when (h) {
            "EntryNo" -> "No"; "Name" -> "名前"; "Class" -> "クラス"
            "AmG" -> "AG"; "AmC" -> "AC"; "AmRank" -> "A順"
            "PmG" -> "PG"; "PmC" -> "PC"; "PmRank" -> "P順"
            "TotalG" -> "TG"; "TotalC" -> "TC"; "TotalRank" -> "順位"
            "時刻", "入力", "セッション" -> ""
            else -> h.replace(Regex("""Sec0?(\d{1,2})""")) { m -> "S${m.groupValues[1].toInt()}" }
        }
        val keepIdx = header.mapIndexedNotNull { i, h -> jpHeader(h).ifBlank { null }?.let { i } }
        val jpHeaders = keepIdx.map { jpHeader(header[it]) }
        val secCount = jpHeaders.count { it.matches(Regex("^S\\d+$")) }
        val is24 = secCount >= 24

        val prefs = context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        val order = when (prefs.getString("tournamentType", "beginner")) {
            "championship" -> listOf("IA", "IB", "NA", "NB")
            else -> listOf("オープン", "ビギナー")
        }
        val classIdx = header.indexOf("Class")
        val grouped = data.groupBy { it.getOrNull(classIdx) ?: "" }

        val title = getEventTitle(context)
        val dateStr = todayDisplay()

        var anySaved = false
        val keys = buildList {
            addAll(order)
            addAll(grouped.keys.filter { it.isNotBlank() && it !in order }.sorted())
        }
        keys.forEach { clazz ->
            val rows = grouped[clazz].orEmpty()
            if (rows.isEmpty()) return@forEach
            if (rows.size > rowsTarget) {
                Toast.makeText(context, "⚠️ $clazz は ${rows.size}件。1枚に圧縮します（推奨15）。", Toast.LENGTH_SHORT).show()
            }
            val html = buildSingleClassHtml(
                title = title, dateStr = dateStr, patternCode = pattern.patternCode,
                clazz = clazz, headers = jpHeaders, keepIdx = keepIdx, rows = rows,
                is24 = is24, rowsTarget = rowsTarget
            )
            val name = "result_${pattern.patternCode}_${todayName()}_${clazz}.pdf"
            val uri = writeToDownloads(context, name, "application/pdf", htmlToPdfBytes(context, html))
            anySaved = anySaved or (uri != null)
        }

        Toast.makeText(context, if (anySaved) "✅ クラス別PDF（1枚/クラス）を保存しました" else "❌ PDF出力なし", Toast.LENGTH_SHORT).show()
    }

    // ───────── 内部HTML構築 ─────────

    /** まとめ表示用HTML（全クラスを順に並べた掲示版） */
    private fun buildPrintableHtml(context: Context, pattern: TournamentPattern): String? {
        val src = resultCsvFile(context, pattern); if (!src.exists()) return null
        val rows = src.readLines(Charsets.UTF_8); if (rows.isEmpty()) return null

        val header = rows.first().split(",").map { it.trim().removePrefix("\uFEFF") }
        val data = rows.drop(1).map { it.split(",") }

        fun jpHeader(h: String): String = when (h) {
            "EntryNo" -> "No"; "Name" -> "名前"; "Class" -> "クラス"
            "AmG" -> "AG"; "AmC" -> "AC"; "AmRank" -> "A順"
            "PmG" -> "PG"; "PmC" -> "PC"; "PmRank" -> "P順"
            "TotalG" -> "TG"; "TotalC" -> "TC"; "TotalRank" -> "順位"
            "時刻", "入力", "セッション" -> "" // 掲示では非表示
            else -> h.replace(Regex("""Sec0?(\d{1,2})""")) { m -> "S${m.groupValues[1].toInt()}" }
        }
        val keepIdx = header.mapIndexedNotNull { i, h -> jpHeader(h).ifBlank { null }?.let { i } }
        val jpHeaders = keepIdx.map { jpHeader(header[it]) }

        val secCount = jpHeaders.count { it.matches(Regex("^S\\d+$")) }
        val is24 = secCount >= 24

        val prefs = context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        val order = when (prefs.getString("tournamentType", "beginner")) {
            "championship" -> listOf("IA", "IB", "NA", "NB")
            else -> listOf("オープン", "ビギナー")
        }
        val classIdx = header.indexOf("Class")
        val groupedAll = data.groupBy { row -> row.getOrNull(classIdx) ?: "" }

        val title = getEventTitle(context)
        val dateStr = todayDisplay()

        return buildString {
            append(
                """
                <html>
                  <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                      @page { size: A4 landscape; margin: 0; }
                      html, body { margin: 0; padding: 0; }
                      body{ font-family: sans-serif; padding: 10mm; }
                      .header{ margin-bottom:8px; }
                      .title{ font-size:18pt; font-weight:700; }
                      .sub{ font-size:11pt; color:#444; }
                      h2{ margin:12px 0 6px 0; border-left:6px solid #333; padding-left:8px; }
                      table{ width:100%; border-collapse:collapse; margin-bottom:12px; table-layout: fixed; }
                      th,td{
                        border:1px solid #999;
                        padding:${if (is24) "3px 4px" else "4px 6px"};
                        font-size:${if (is24) "9.5pt" else "10pt"};
                        line-height:1.25; white-space:nowrap; text-align:center; overflow:hidden; text-overflow:clip;
                      }
                      thead th{ position:sticky; top:0; background:#f0f0f0; }
                      .class-gap{ height:6px; }
                    </style>
                  </head>
                  <body>
                """.trimIndent()
            )

            append("<div class='header'><div class='title'>")
                .append(title)
                .append("</div><div class='sub'>")
                .append(dateStr)
                .append("　/　形式: ")
                .append(pattern.patternCode)
                .append("</div></div>")

            val orderedKeys = buildList {
                addAll(order)
                addAll(groupedAll.keys.filter { it.isNotBlank() && it !in order }.sorted())
            }

            orderedKeys.forEach { clazz ->
                val list = groupedAll[clazz].orEmpty()
                if (list.isEmpty()) return@forEach

                append("<h2>").append(clazz).append("クラス</h2>")
                append("<table><thead><tr>")
                jpHeaders.forEach { append("<th>").append(it).append("</th>") }
                append("</tr></thead><tbody>")

                list.forEach { row ->
                    append("<tr>")
                    keepIdx.forEach { i -> append("<td>").append(row.getOrNull(i) ?: "").append("</td>") }
                    append("</tr>")
                }

                append("</tbody></table><div class='class-gap'></div>")
            }

            append("</body></html>")
        }
    }

    /** 1クラスだけの掲示用HTML（横だけ自動フィット） */
    private fun buildSingleClassHtml(
        title: String,
        dateStr: String,
        patternCode: String,
        clazz: String,
        headers: List<String>,
        keepIdx: List<Int>,
        rows: List<List<String>>,
        is24: Boolean,
        rowsTarget: Int
    ): String {
        val fontPt = if (is24) "10pt" else "10.5pt"
        val pad = if (is24) "3px 4px" else "4px 6px"

        return buildString {
            append("""
                <html><head><meta charset="UTF-8">
                <style>
                  @page { size: A4 landscape; margin: 0; }
                  html, body { margin:0; padding:0; }
                  body{ font-family: system-ui, "Noto Sans JP", sans-serif; padding:10mm; }
                  .header{ margin-bottom:8px; }
                  .title{ font-size:18pt; font-weight:700; }
                  .sub{ font-size:11pt; color:#444; }
                  h2{ margin:12px 0 6px 0; border-left:6px solid #333; padding-left:8px; }
                  #wrap{ transform-origin: top left; }
                  table{ width:100%; border-collapse:collapse; margin-top:6px; table-layout: fixed; }
                  th,td{
                    border:1px solid #999; padding:${pad}; font-size:${fontPt};
                    line-height:1.25; white-space:nowrap; text-align:center; overflow:hidden; text-overflow:clip;
                  }
                  thead th{ background:#f0f0f0; }
                </style>
                <script>
                  (function(){
                    function fit(){
                      var w = Math.max(document.documentElement.clientWidth, document.body.clientWidth);
                      var wrap = document.getElementById('wrap');
                      var need = wrap.scrollWidth;
                      var s = w / need;
                      if(s < 1){ wrap.style.transform = 'scale(' + s + ')'; wrap.style.width = (100/s) + '%'; }
                    }
                    window.addEventListener('load', fit);
                    setTimeout(fit, 50);
                  })();
                </script>
                </head><body>
            """.trimIndent())

            append("<div class='header'><div class='title'>")
                .append(title)
                .append("</div><div class='sub'>")
                .append(dateStr).append("　/　形式: ").append(patternCode)
                .append("</div></div>")

            append("<div id='wrap'>")
            append("<h2>").append(clazz).append("クラス</h2>")
            append("<table><thead><tr>")
            headers.forEach { append("<th>").append(it).append("</th>") }
            append("</tr></thead><tbody>")
            rows.forEach { row ->
                append("<tr>")
                keepIdx.forEach { i -> append("<td>").append(row.getOrNull(i) ?: "").append("</td>") }
                append("</tr>")
            }
            append("</tbody></table></div></body></html>")
        }
    }

    // ───────── WebView → PDF（UIスレ安全版） ─────────

    /** HTML文字列をA4横1ページのPDFバイト列へ（UIスレブロックなし） */
    @SuppressLint("SetJavaScriptEnabled")
    private fun htmlToPdfBytes(context: Context, html: String): ByteArray {
        val latch = java.util.concurrent.CountDownLatch(1)
        var bytes = ByteArray(0)

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            val act = context as? android.app.Activity
            val root = act?.window?.decorView as? android.view.ViewGroup
            if (act == null || root == null) { latch.countDown(); return@post }

            val web = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.defaultTextEncodingName = "utf-8"
                setBackgroundColor(android.graphics.Color.WHITE)
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                alpha = 0.01f
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(web)
            fun cleanup() { root.removeView(web); web.destroy() }

            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val js = """
                        (function(){
                          var w = Math.max(document.documentElement.scrollWidth,  document.body.scrollWidth  || 0);
                          var h = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight || 0);
                          return w + 'x' + h;
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js) { res ->
                        try {
                            val parts = res?.trim('"')?.split('x') ?: emptyList()
                            val cssW = parts.getOrNull(0)?.toFloatOrNull() ?: 1400f
                            val cssH = parts.getOrNull(1)?.toFloatOrNull() ?: (view.contentHeight.toFloat().coerceAtLeast(1f))

                            val density = context.resources.displayMetrics.density
                            val bw = (cssW * density).toInt().coerceAtLeast(1)
                            val bh = (cssH * density).toInt().coerceAtLeast(1)

                            web.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(bw, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                            )
                            web.layout(0, 0, bw, bh)

                            web.postDelayed({
                                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                web.draw(canvas)

                                val pageW = 842; val pageH = 595
                                val margin = 12f
                                val availW = pageW - margin * 2
                                val availH = pageH - margin * 2
                                val scale = 0.99f * minOf(availW / bw.toFloat(), availH / bh.toFloat())

                                val pdf = PdfDocument()
                                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
                                page.canvas.translate(margin + maxOf((availW - bw*scale)/2f, 0f), margin)
                                page.canvas.scale(scale, scale)
                                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                                pdf.finishPage(page)

                                val bos = ByteArrayOutputStream()
                                pdf.writeTo(bos)
                                pdf.close()
                                bmp.recycle()

                                bytes = bos.toByteArray()
                                cleanup()
                                latch.countDown()
                            }, 80)
                        } catch (_: Exception) {
                            cleanup(); latch.countDown()
                        }
                    }
                }
            }
            web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 呼び出しがUIスレ：短命ワーカーで待機
            val done = java.util.concurrent.CountDownLatch(1)
            var out = ByteArray(0)
            Thread {
                latch.await()
                out = bytes
                done.countDown()
            }.start()
            done.await()
            return out
        } else {
            latch.await()
            return bytes
        }
    }

    // ───────── Downloads/ResultReader 出力 ─────────

    private fun writeToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        return try {
            val cr = context.contentResolver
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ResultReader")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                cr.insert(MediaStore.Files.getContentUri("external"), values)
            }

            if (uri == null) {
                Toast.makeText(context, "❌ 出力に失敗しました", Toast.LENGTH_SHORT).show()
                return null
            }

            cr.openFileDescriptor(uri, "w")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    fos.fd.sync()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, v, null, null)
            }

            Toast.makeText(context, "✅ Downloads/ResultReader に出力しました", Toast.LENGTH_SHORT).show()
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "❌ 書き込みエラー: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    // ── 表スタイル（色や余白を一括管理）
    private data class TableStyle(
        val margin: Float = 18f,                 // 6.35mm
        val titleSize: Float = 18f,
        val subSize: Float = 11f,
        val sectionSize: Float = 14f,
        val textSize: Float = 10f,               // 24セク時は呼び出し側で9.5fに
        val padX: Float = 4f,
        val padY: Float = 4f,
        val headerBg: Int = android.graphics.Color.rgb(245,245,245),
        val zebraBg: Int = android.graphics.Color.rgb(252,252,252),
        val grid: Int = android.graphics.Color.rgb(180,180,180),
        val text: Int = android.graphics.Color.BLACK
    )

    // ── 右寄せ対象の判定（数値系は右寄せ）
    private fun isNumericCol(label: String): Boolean {
        return label.matches(Regex("^S\\d+$")) ||
               label in listOf("AG","AC","A順","PG","PC","P順","TG","TC","順位","No")
    }

    private fun condensed(): android.graphics.Typeface =
        android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)

    // alignment enum used by cell renderer
    private enum class CellAlign { LEFT, CENTER, RIGHT }

    // セル内テキスト描画（LEFT/CENTER/RIGHT） - object-scope helper
    private fun drawCellText(
        c: Canvas,
        text: String,
        leftX: Float,
        width: Float,
        baseline: Float,
        align: CellAlign,
        paint: android.graphics.Paint,
        padX: Float
    ) {
        val w = paint.measureText(text)
        val x = when (align) {
            CellAlign.LEFT -> leftX + padX
            CellAlign.CENTER -> leftX + (width - w) / 2f
            CellAlign.RIGHT -> leftX + width - padX - w
        }
        c.drawText(text, x, baseline, paint)
    }

    // ビギナー系のクラス短縮（Op/B）
    private fun displayClassName(src: String, tournamentType: String): String {
        return if (tournamentType == "championship") src else when (src) {
            "オープン" -> "Op"
            "ビギナー" -> "B"
            else -> src
        }
    }

    // ── CSV→PDF（Canvas描画・クラス別・複数ページ対応）
    fun exportPrintablePdfStyledFromCsv(
        context: Context,
        pattern: TournamentPattern,
        rowsPerPage: Int = 15
    ) {
        val src = File(context.getExternalFilesDir("ResultReader"),
            "result_${pattern.patternCode}_${todayName()}.csv")
        if (!src.exists()) { Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show(); return }

        val lines = src.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return

        // 見出し整形（S1表記 & 日本語化 & 非表示列除去）
        fun jpHeader(hRaw: String): String {
            // 余計な空白/BOM/二重引用符を除去
            val h = hRaw.trim().removePrefix("\uFEFF").trim('"')

            return when (h) {
                "EntryNo" -> "No"
                "Name"    -> "名前"
                "Class"   -> "クラス"
                "AmG"     -> "AG"
                "AmC"     -> "AC"
                "AmRank"  -> "A順"
                "PmG"     -> "PG"
                "PmC"     -> "PC"
                "PmRank"  -> "P順"
                "TotalG"  -> "TG"
                "TotalC"  -> "TC"
                "TotalRank" -> "順位"
                "時刻", "入力", "セッション" -> "" // 非表示
                else -> {
                    // Sec01, "Sec1", Sec09 → S1, S9, S10 に統一
                    val m = Regex("""^Sec0*(\d{1,2})$""").matchEntire(h)
                    if (m != null) "S${m.groupValues[1].toInt()}" else h
                }
            }
        }
        val headerRaw = lines.first().split(",").map { it.trim().removePrefix("\uFEFF") }
        val keepIdx = headerRaw.mapIndexedNotNull { i, h -> jpHeader(h).ifBlank{null}?.let{ i } }
        val headers = keepIdx.map { jpHeader(headerRaw[it]) }
        val rowsAll = lines.drop(1).map { it.split(",") }

        val secCount = headers.count { it.matches(Regex("^S\\d+$")) }
        val style = if (secCount >= 24)
            TableStyle(textSize = 9.5f, padX = 3f, padY = 3f)
          else
            TableStyle()

        // クラス順（設定から）
        val prefs = context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        val order = when (prefs.getString("tournamentType", "beginner")) {
            "championship" -> listOf("IA","IB","NA","NB")
            else -> listOf("オープン","ビギナー")
        }
        val classIdx = headerRaw.indexOf("Class")
        val grouped = rowsAll.groupBy { it.getOrNull(classIdx) ?: "" }
        val classes = buildList {
            addAll(order)
            addAll(grouped.keys.filter { it.isNotBlank() && it !in order }.sorted())
        }

        // tournamentType を事前に取得して drawPage 等で使う
        val tType = prefs.getString("tournamentType", "beginner") ?: "beginner"

        // A4 横（ポイント単位）
        val pageW = 842; val pageH = 595
        val pdf = PdfDocument()
        // ③ Paint設定 微調整（太い縦線対策）
        val pText = android.graphics.Paint().apply {
            isAntiAlias = true
            color = style.text
            textSize = style.textSize
            typeface = condensed()
            // 'style' local variable shadows Paint.style, so reference via this
            this.style = android.graphics.Paint.Style.FILL   // 実線塗りのみ
            this.strokeWidth = 0f
        }
        val pBold = android.graphics.Paint(pText).apply {
            // FakeBold は縦方向に滲みが出やすいので使わない
            isFakeBoldText = false
            textSize = style.textSize
        }
        val pFill = android.graphics.Paint().apply { isAntiAlias = true }
        val pGrid = android.graphics.Paint().apply {
            isAntiAlias = true
            color = style.grid
            strokeWidth = 1f
            // 指定は this.style で明示
            this.style = android.graphics.Paint.Style.STROKE
        }

        fun drawPage(clazz: String, rows: List<List<String>>, firstPage: Boolean) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pdf.pages.size + 1).create())
            val c = page.canvas
            var y = style.margin

            // タイトル（1ページ目だけ）
            if (firstPage) {
                pBold.textSize = style.titleSize
                c.drawText(getEventTitle(context), style.margin, y + style.titleSize, pBold)
                pText.textSize = style.subSize
                c.drawText("日付: ${todayDisplay()}   形式: ${pattern.patternCode}",
                    style.margin, y + style.titleSize + style.subSize + 6f, pText)
                pText.textSize = style.textSize
                pBold.textSize = style.sectionSize
                y += style.titleSize + style.subSize + 12f
            }

            // クラス見出し（短縮名を利用）
            val clazzTitle = displayClassName(clazz, tType)
            c.drawText("${clazzTitle}クラス", style.margin, y + style.sectionSize, pBold)
            y += style.sectionSize + 6f

            // 列幅：名前を広め、No/クラス/順位はやや狭め、S列は均等
            val cols = headers.size
            val availW = pageW - style.margin * 2
            val weight = FloatArray(cols) { 1f }
            headers.forEachIndexed { i, h ->
                when (h) {
                    "名前"  -> weight[i] = 2.2f
                    "No"   -> weight[i] = 1.1f
                    "クラス","順位" -> weight[i] = 1.2f
                }
            }
            val weightSum = weight.sum()
            val colW = FloatArray(cols) { wIndex -> availW * (weight[wIndex] / weightSum) }

            // 行高
            val rowH = (style.textSize + style.padY * 2 + 6f)

            // ヘッダ行
            pFill.color = style.headerBg
            c.drawRect(style.margin, y, style.margin + availW, y + rowH, pFill)
            var x = style.margin
            headers.forEachIndexed { i, h ->
                val baseline = y + rowH - style.padY - 3f
                drawCellText(c, h, x, colW[i], baseline, CellAlign.CENTER, pBold, style.padX)
                x += colW[i]
            }
            // 下線
            c.drawLine(style.margin, y + rowH, style.margin + availW, y + rowH, pGrid)
            y += rowH

            // データ行
            rows.forEachIndexed { r, row ->
                // ゼブラ
                if (r % 2 == 1) {
                    pFill.color = style.zebraBg
                    c.drawRect(style.margin, y, style.margin + availW, y + rowH, pFill)
                }
                // 横罫
                c.drawLine(style.margin, y + rowH, style.margin + availW, y + rowH, pGrid)

                // セル描画
                x = style.margin
                headers.indices.forEach { i ->
                    var cell = row.getOrNull(keepIdx[i])?.trim()?.trim('"').orEmpty()
                    // クラス列だけ短縮表記に
                    if (headers[i] == "クラス") {
                        val tt = prefs.getString("tournamentType", "beginner") ?: "beginner"
                        cell = displayClassName(cell, tt)
                    }
                    val baseline = y + rowH - style.padY - 3f
                    val align = when (headers[i]) {
                        "名前" -> CellAlign.LEFT           // 名前だけ左寄せで重なり回避
                        else   -> CellAlign.CENTER         // それ以外は中央寄せ
                    }
                    drawCellText(c, cell, x, colW[i], baseline, align, pText, style.padX)
                    x += colW[i]
                }
                y += rowH
            }

            pdf.finishPage(page)
        }

        var anySaved = false
        classes.forEach { clazz ->
            val list = grouped[clazz].orEmpty(); if (list.isEmpty()) return@forEach
            val pages = list.chunked(rowsPerPage)
            pages.forEachIndexed { i, chunk -> drawPage(clazz, chunk, firstPage = (i == 0)) }
        }

        // 1ファイルに全ページまとめて保存
        val bos = ByteArrayOutputStream()
        pdf.writeTo(bos); pdf.close()
        val name = "result_${pattern.patternCode}_${todayName()}_print.pdf"
        val uri = writeToDownloads(context, name, "application/pdf", bos.toByteArray())
        anySaved = uri != null
        Toast.makeText(context, if (anySaved) "✅ PDFを保存しました（Downloads/ResultReader）" else "❌ PDF出力失敗", Toast.LENGTH_SHORT).show()
    }
}
