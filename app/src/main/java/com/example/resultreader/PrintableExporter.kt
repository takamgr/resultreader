@file:Suppress("unused", "RedundantInitialiser", "UNUSED_PARAMETER", "RemoveRedundantElse", "RedundantCallOfConversionMethod")

package com.example.resultreader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.widget.Toast
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
        File(
            context.getExternalFilesDir("ResultReader"),
            "result_${pattern.patternCode}_${todayName()}.csv"
        )

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
        if (!src.exists()) {
            Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT)
                .show(); return null
        }
        val lines = src.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return null

        val s1Header = lines.first()
            .replace(Regex("""Sec0?(\d{1,2})""")) { m -> "S${m.groupValues[1].toInt()}" }
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
    @Deprecated(
        "HTML export removed, use exportPrintablePdfStyledFromCsv",
        level = DeprecationLevel.HIDDEN
    )
    private fun exportPrintableHtmlToDownloads(context: Context, pattern: TournamentPattern): Uri? {
        // Deprecated: intentionally kept private to avoid accidental use
        return null
    }

    /** 旧：全クラスまとめて1枚のPDF（必要なら残す） */
    @Deprecated("Use exportPrintablePdfStyledFromCsv instead", level = DeprecationLevel.HIDDEN)
    private fun exportPrintablePdfToDownloads(context: Context, pattern: TournamentPattern) {
        // Delegate to Canvas renderer
        exportPrintablePdfStyledFromCsv(context, pattern, rowsPerPage = 20)
    }

    // ───────── クラス別 PDF ─────────

    /** 改ページ型：クラスごとに rowsPerPage で分割し連番PDFを保存（2枚目以降は大会名・日付を省略） */
    @Deprecated("Use exportPrintablePdfStyledFromCsv instead", level = DeprecationLevel.HIDDEN)
    @Suppress("unused")
    private fun exportPrintablePdfByClass(
        context: Context,
        pattern: TournamentPattern,
        rowsPerPage: Int = 20
    ) {
        // Delegate to unified Canvas renderer
        exportPrintablePdfStyledFromCsv(context, pattern, rowsPerPage)
        return
    }

    /** 1クラス＝A4一枚に圧縮して保存（クラス数ぶんファイル） */
    @Deprecated("Use exportPrintablePdfStyledFromCsv instead", level = DeprecationLevel.HIDDEN)
    @Suppress("unused")
    private fun exportPrintablePdfPerClassSinglePage(
        context: Context,
        pattern: TournamentPattern,
        rowsTarget: Int = 15
    ) {
        // Delegate to unified Canvas renderer
        exportPrintablePdfStyledFromCsv(context, pattern, rowsPerPage = rowsTarget)
        return
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
                    keepIdx.forEach { i ->
                        append("<td>").append(row.getOrNull(i) ?: "").append("</td>")
                    }
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
            append(
                """
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
            """.trimIndent()
            )

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
                keepIdx.forEach { i ->
                    append("<td>").append(row.getOrNull(i) ?: "").append("</td>")
                }
                append("</tr>")
            }
            append("</tbody></table></div></body></html>")
        }
    }

    // ───────── WebView → PDF（UIスレ安全版） ─────────

    /** HTML文字列をA4横1ページのPDFバイト列へ（UIスレブロックなし） */
    @Deprecated(
        "WebView-based PDF generation removed; use Canvas renderer",
        level = DeprecationLevel.HIDDEN
    )
    @Suppress("unused")
    private fun htmlToPdfBytes(context: Context, html: String): ByteArray {
        // Removed: WebView path is deprecated. Return empty bytes.
        return ByteArray(0)
    }


    // ───────── Downloads/ResultReader 出力 ─────────

    private fun writeToDownloads(
        context: Context,
        name: String,
        mime: String,
        bytes: ByteArray
    ): Uri? {
        return try {
            val cr = context.contentResolver
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/ResultReader"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                cr.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                )
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

            Toast.makeText(context, "✅ Downloads/ResultReader に出力しました", Toast.LENGTH_SHORT)
                .show()
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
        val headerBg: Int = android.graphics.Color.rgb(245, 245, 245),
        val zebraBg: Int = android.graphics.Color.rgb(252, 252, 252),
        val grid: Int = android.graphics.Color.rgb(180, 180, 180),
        val text: Int = android.graphics.Color.BLACK
    )

    // ── 右寄せ対象の判定（数値系は右寄せ）
    private fun isNumericCol(label: String): Boolean {
        return label.matches(Regex("^S\\d+$")) ||
                label in listOf("AG", "AC", "A順", "PG", "PC", "P順", "TG", "TC", "順位", "No")
    }

    private fun condensed(): android.graphics.Typeface =
        android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)

    // alignment enum used by cell renderer
    private enum class CellAlign { LEFT, CENTER, RIGHT }

    // ヘッダに表示する大会名（Prefs の tournamentName、未設定時に "大会結果"）
    private fun tournamentName(ctx: Context): String {
        val p = ctx.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        return p.getString("tournamentName", "大会結果") ?: "大会結果"
    }

    // 表示用開催日（Prefs の eventDate。未設定なら本日。フォーマット調整あり）
    private fun eventDateDisplay(ctx: Context): String {
        val p = ctx.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        val s = p.getString("eventDate", null)
        return when {
            s.isNullOrBlank() -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
            s.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> s.replace("-", "/")
            s.matches(Regex("\\d{8}")) -> "${s.substring(0, 4)}/${
                s.substring(
                    4,
                    6
                )
            }/${s.substring(6, 8)}"

            else -> s
        }
    }

    // パターンを可読文に変換
    private fun formatText(pattern: TournamentPattern): String = when (pattern) {
        TournamentPattern.PATTERN_4x2 -> "8セクション2ラップ"
        TournamentPattern.PATTERN_5x2 -> "10セクション2ラップ"
        TournamentPattern.PATTERN_4x3 -> "8セクション3ラップ"
        else -> pattern.patternCode
    }

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

    // 表示用：クラス見出しを日本語フル表記にする（例: オープンクラス / ビギナークラス / IAクラス）
    private fun displayClassTitle(src: String, tournamentType: String): String {
        return when (tournamentType) {
            "championship" -> "${src}クラス"
            else -> when (src) {
                "オープン", "Op" -> "オープンクラス"
                "ビギナー", "B" -> "ビギナークラス"
                else -> "${src}クラス"
            }
        }
    }

    // ── CSV→PDF（Canvas描画・クラス別・複数ページ対応）
    // ── CSV→PDF（Canvas描画・クラス別・複数ページ対応）
    fun exportPrintablePdfStyledFromCsv(
        context: Context,
        pattern: TournamentPattern,
        rowsPerPage: Int = 20,
        drawRowLines: Boolean = false // default: off to preserve previous appearance
    ) {
        // debug: entry & option
        android.util.Log.d(
            "PRINTABLE",
            "exportPrintablePdfStyledFromCsv start: pattern=${pattern.patternCode} drawRowLines=$drawRowLines"
        )

        val src = File(
            context.getExternalFilesDir("ResultReader"),
            "result_${pattern.patternCode}_${todayName()}.csv"
        )
        if (!src.exists()) {
            Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show(); return
        }

        val lines = src.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return

        // 見出し整形（S1表記 & 日本語化 & 非表示列除外）
        // NOTE: "Class" を空にすることでテーブルからクラス列を削除する（Canvas出力専用）
        fun jpHeader(hRaw: String): String {
            val h = hRaw.trim().removePrefix("\uFEFF").trim('"')
            return when (h) {
                "EntryNo" -> "No"
                "Name" -> "名前"
                "Class" -> "" // ← クラス列を非表示にする
                "AmG" -> "AG"
                "AmC" -> "AC"
                "AmRank" -> "A順"
                "PmG" -> "PG"
                "PmC" -> "PC"
                "PmRank" -> "P順"
                "TotalG" -> "TG"
                "TotalC" -> "TC"
                "TotalRank" -> "順位"
                "時刻", "入力", "セッション" -> "" // 非表示
                else -> {
                    val m = Regex("""^Sec0*(\d{1,2})$""").matchEntire(h)
                    if (m != null) "S${m.groupValues[1].toInt()}" else h
                }
            }
        }

        val headerRaw = lines.first().split(",").map { it.trim().removePrefix("\uFEFF") }
        val keepIdx =
            headerRaw.mapIndexedNotNull { i, h -> jpHeader(h).ifBlank { null }?.let { i } }
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
            "championship" -> listOf("IA", "IB", "NA", "NB")
            else -> listOf("オープン", "ビギナー")
        }
        val classIdx = headerRaw.indexOf("Class")
        val grouped = rowsAll.groupBy { it.getOrNull(classIdx) ?: "" }
        val classes = buildList {
            addAll(order)
            addAll(grouped.keys.filter { it.isNotBlank() && it !in order }.sorted())
        }

        val tType = prefs.getString("tournamentType", "beginner") ?: "beginner"

        // A4 横（ポイント単位）
        val pageW = 842
        val pageH = 595
        val pdf = PdfDocument()

        // Paint
        val pText = android.graphics.Paint().apply {
            isAntiAlias = true
            color = style.text
            textSize = style.textSize
            typeface = condensed()
            this.style = android.graphics.Paint.Style.FILL
            this.strokeWidth = 0f
        }
        val pBold = android.graphics.Paint(pText).apply {
            isFakeBoldText = false
            textSize = style.textSize
        }
        val pFill = android.graphics.Paint().apply { isAntiAlias = true }

        @Suppress("UNUSED_VARIABLE")
        val pGrid = android.graphics.Paint().apply {
            // vertical lines or strokes if needed
            isAntiAlias = false
            color = style.grid
            strokeWidth = 1f
            strokeCap = android.graphics.Paint.Cap.BUTT
            strokeJoin = android.graphics.Paint.Join.MITER
            this.style = android.graphics.Paint.Style.STROKE
        }

        // 罫線（塗り矩形で描画するための塗りPaint）
        val pLineFill = android.graphics.Paint().apply {
            isAntiAlias = false
            this.style = android.graphics.Paint.Style.FILL
            // darker grid for better visibility in PDF viewers
            color = android.graphics.Color.rgb(120, 120, 120)
            alpha = 0xFF.toInt()
        }

        // snap helper: floor to integer pixel (Float) — defined here so hLine can call it
        fun snapHalf(v: Float): Float = kotlin.math.floor(v).toFloat()

        // 横線は矩形で描く（丸めたY座標で矩形を描く）
        fun hLine(c: Canvas, x1: Float, x2: Float, y: Float, thickPt: Float) {
            val yy = kotlin.math.round(y).toFloat().coerceAtLeast(0f)
            val maxY = (pageH - style.margin)
            if (yy > maxY) return
            val drawH = if (yy + thickPt > maxY) (maxY - yy).coerceAtLeast(0f) else thickPt
            if (drawH <= 0f) return
            c.drawRect(x1, yy, x2, yy + drawH, pLineFill)
        }

        fun drawPage(clazz: String, rows: List<List<String>>, firstPage: Boolean) {
            val page = pdf.startPage(
                PdfDocument.PageInfo.Builder(pageW, pageH, pdf.pages.size + 1).create()
            )
            val c = page.canvas
            var y = style.margin

            // タイトル（1ページ目だけ） --- 大会名/開催日/形式をPrefsから表示
            if (firstPage) {
                pBold.textSize = style.titleSize
                val title = tournamentName(context)
                val dateStr = eventDateDisplay(context)
                val fmt = formatText(pattern)
                c.drawText(title, style.margin, y + style.titleSize, pBold)
                pText.textSize = style.subSize
                c.drawText(
                    "日付: $dateStr   形式: $fmt",
                    style.margin, y + style.titleSize + style.subSize + 6f, pText
                )
                pText.textSize = style.textSize
                pBold.textSize = style.sectionSize
                y += style.titleSize + style.subSize + 12f
            }

            // クラス見出し（日本語フル表記）
            val clazzTitle = displayClassTitle(clazz, tType)
            c.drawText(clazzTitle, style.margin, y + style.sectionSize, pBold)
            y += style.sectionSize + 6f

            // 列幅
            val cols = headers.size
            val availW = pageW - style.margin * 2
            val weight = FloatArray(cols) { 1f }
            headers.forEachIndexed { i, h ->
                when (h) {
                    "名前" -> weight[i] = 2.2f
                    "No" -> weight[i] = 1.1f
                    "クラス", "順位" -> weight[i] = 1.2f
                }
            }
            val weightSum = weight.sum()
            val colW = FloatArray(cols) { wIndex -> availW * (weight[wIndex] / weightSum) }

            // 行高（小数累積を避けて整数化）
            val rowH = kotlin.math.ceil(style.textSize + style.padY * 2 + 6f).toFloat()

            // ヘッダ行（背景＋見出し）
            pFill.color = style.headerBg
            c.drawRect(style.margin, y, style.margin + availW, y + rowH, pFill)
            var x = style.margin
            headers.forEachIndexed { i, h ->
                val baseline = y + rowH - style.padY - 3f
                drawCellText(c, h, x, colW[i], baseline, CellAlign.CENTER, pBold, style.padX)
                x += colW[i]
            }
            // ヘッダ直下のみ太線（矩形描画でブレを防止）
            val boldPt = 1.6f
            hLine(c, style.margin, style.margin + availW, y + rowH, boldPt)
            // 記録: ヘッダ直下のY（thin線スキップ判定用）
            val headerLineY = y + rowH
            y += rowH

            // データ行
            val thinPt = 1.0f
            val rowsStartY = y // top Y of first data row
            rows.forEachIndexed { r, row ->
                // ゼブラ（上側を薄線分だけ詰めて描画し、次行のゼブラで下線が隠れないようにする）
                if (r % 2 == 1) {
                    pFill.color = style.zebraBg
                    val lineInset = thinPt
                    c.drawRect(
                        style.margin,
                        y + lineInset,
                        style.margin + availW,
                        y + rowH - lineInset,
                        pFill
                    )
                }

                // セル描画
                x = style.margin
                headers.indices.forEach { i ->
                    var cell = row.getOrNull(keepIdx[i])?.trim()?.trim('"').orEmpty()
                    if (headers[i] == "クラス") {
                        val tt = prefs.getString("tournamentType", "beginner") ?: "beginner"
                        cell = displayClassName(cell, tt)
                    }
                    val baseline = y + rowH - style.padY - 3f
                    val align = when (headers[i]) {
                        "名前" -> CellAlign.LEFT
                        else -> CellAlign.CENTER
                    }
                    drawCellText(c, cell, x, colW[i], baseline, align, pText, style.padX)
                    x += colW[i]
                }

                // 横線を各行ごとに確実に描画（各データ行に薄線を必ず引く）
                val thinPt = 1.0f
                val lineY = y + rowH
                android.util.Log.d("PRINTABLE", "draw thin line row=$r lineY=$lineY")
                hLine(c, style.margin, style.margin + availW, lineY, thinPt)
                y += rowH
            }

            // ※ ポスト描画は廃止。各行内で1回だけ描画済み。

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
        Toast.makeText(
            context,
            if (anySaved) "✅ PDFを保存しました（Downloads/ResultReader）" else "❌ PDF出力失敗",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── 追加：クラスごとに別PDFで出力（ロール紙向け）
    // ── クラス別PDF（1クラス=1ファイル、Lap帯つき・全パターン対応）
    fun exportPrintablePdfStyledSplitByClass(
        context: Context,
        pattern: TournamentPattern,
        rowsPerPage: Int = 20
    ) {
        val src = File(
            context.getExternalFilesDir("ResultReader"),
            "result_${pattern.patternCode}_${todayName()}.csv"
        )
        if (!src.exists()) {
            Toast.makeText(context, "本日のCSVが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = src.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return

        // ----- パターン別セクション構成 -----
        data class PatternInfo(val sectionsPerLap: Int, val lapsPerSession: Int)

        val pInfo = when (pattern) {
            TournamentPattern.PATTERN_4x2 -> PatternInfo(4, 2)
            TournamentPattern.PATTERN_4x3 -> PatternInfo(4, 3)
            TournamentPattern.PATTERN_5x2 -> PatternInfo(5, 2)
            else -> PatternInfo(4, 2)
        }
        val sectionsPerLap = pInfo.sectionsPerLap
        val lapsPerSession = pInfo.lapsPerSession
        val perSession = sectionsPerLap * lapsPerSession
        val totalSec = perSession * 2

        // ----- ヘッダ解析 -----
        val headerRaw = lines.first().split(",")
        val headerNorm = headerRaw.map { it.trim().removePrefix("\uFEFF").trim('"') }

        fun jp(keyRaw: String): String {
            val h = keyRaw.trim().removePrefix("\uFEFF").trim('"')
            return when (h) {
                "EntryNo" -> "No"
                "Name" -> "名前"
                "Class" -> ""
                "AmG" -> "AG"
                "AmC" -> "AC"
                "AmRank" -> "A順"
                "PmG" -> "PG"
                "PmC" -> "PC"
                "PmRank" -> "P順"
                "TotalG" -> "TG"
                "TotalC" -> "TC"
                "TotalRank" -> "順位"
                "時刻", "入力", "セッション" -> ""
                else -> h
            }
        }

        // 表示する列インデックス
        val keep = headerNorm.mapIndexedNotNull { i, h ->
            jp(h).ifBlank { null }?.let { i }
        }

        // Sec01 / S1 などを数字に変換
        val secRegex = Regex("^(?:Sec|S)0*(\\d{1,2})$")

        // 全パターン共通のセクション番号ラベル
        fun sectionLabel(secNo: Int): String {
            if (secNo !in 1..totalSec) return secNo.toString()
            val isPm = secNo > perSession
            val base = if (!isPm) 1 else sectionsPerLap + 1
            val offsetInLap = (secNo - 1) % sectionsPerLap
            return (base + offsetInLap).toString()
        }

        // 実際に表に出すヘッダ（表示テキスト）
        val headers: List<String> = keep.map { idx ->
            val raw = headerNorm[idx]
            val m = secRegex.matchEntire(raw)
            if (m != null) {
                val secNo = m.groupValues[1].toInt()
                sectionLabel(secNo)
            } else {
                jp(raw)
            }
        }

        // データ行
        val rowsAll = lines.drop(1).map { it.split(",") }

        // クラスごとにグループ化
        val classIdx = headerNorm.indexOf("Class")
        val grouped = rowsAll.groupBy { row ->
            row.getOrNull(classIdx)?.trim().orEmpty()
        }

        // クラス表示順（大会種別によって優先順を固定）
        val prefs = context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
        val tournamentType = prefs.getString("tournamentType", "beginner") ?: "beginner"

        val baseOrder = if (tournamentType == "championship") {
            listOf("IA", "IB", "NA", "NB")
        } else {
            listOf("オープン", "ビギナー")
        }

        val classes = buildList {
            addAll(baseOrder)
            addAll(grouped.keys.filter { it.isNotBlank() && it !in baseOrder }.sorted())
        }

        // ---------- 描画用 Paint ----------
        val pageW = 842
        val pageH = 595

        val pText = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
            textSize = 10f
            typeface = android.graphics.Typeface.create(
                "sans-serif-condensed",
                android.graphics.Typeface.NORMAL
            )
        }
        val pBold = android.graphics.Paint(pText).apply { isFakeBoldText = true }
        val pHeader = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(240, 240, 240)
            style = android.graphics.Paint.Style.FILL
        }
        val pZebra = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(250, 250, 250)
            style = android.graphics.Paint.Style.FILL
        }
        val pGrid = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(120, 120, 120)
            strokeWidth = 0.8f
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = false
        }
        val pColFill = android.graphics.Paint().apply {
            isAntiAlias = false
            style = android.graphics.Paint.Style.FILL
        }
        val specialCols = setOf("A順", "P順", "順位")
        val specialBgColor = android.graphics.Color.rgb(235, 235, 235)

        fun drawCenteredText(
            canvas: android.graphics.Canvas,
            paint: android.graphics.Paint,
            text: String,
            left: Float,
            right: Float,
            baselineY: Float
        ) {
            val w = paint.measureText(text)
            val x = left + (right - left - w) / 2f
            canvas.drawText(text, x, baselineY, paint)
        }

        // ---------- クラスごとにPDF出力 ----------
        var anySaved = false

        for (clazz in classes) {
            val list = grouped[clazz].orEmpty()
            if (list.isEmpty()) continue

            val pdf = PdfDocument()
            val chunks = list.chunked(rowsPerPage)

            chunks.forEachIndexed { pageIndex, chunk ->
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create()
                )
                val c = page.canvas
                var y = 40f

                // タイトル（大会名）
                pBold.textSize = 16f
                c.drawText(tournamentName(context), 30f, y, pBold)
                y += 22f

                // 日付 + 形式
                pText.textSize = 11f
                val infoText = "日付: ${eventDateDisplay(context)}   形式: ${formatText(pattern)}"
                c.drawText(infoText, 30f, y, pText)
                y += 24f

                // クラス名（日本語フル）
                pBold.textSize = 14f
                val clazzTitle = displayClassTitle(clazz, tournamentType)
                c.drawText(clazzTitle, 30f, y, pBold)
                y += 30f

                // 以降は本文サイズに戻す
                pText.textSize = 10f
                pBold.textSize = 10f

                val cols = headers.size
                val availW = pageW - 60f   // 左右30pt余白

                // 列幅（名前を広め）
                val weights = FloatArray(cols) { 1f }
                headers.forEachIndexed { i, h ->
                    when (h) {
                        "名前" -> weights[i] = 3.2f
                        "No" -> weights[i] = 1.1f
                        "順位" -> weights[i] = 1.2f
                        else -> weights[i] = 1f
                    }
                }
                val weightSum = weights.sum()
                val colWidths = FloatArray(cols) { i ->
                    availW * (weights[i] / weightSum)
                }

                // 各列の開始X
                val colStartX = FloatArray(cols)
                run {
                    var x = 30f
                    for (i in 0 until cols) {
                        colStartX[i] = x
                        x += colWidths[i]
                    }
                }

                // secNo -> 列index
                val secToColIndex = mutableMapOf<Int, Int>()
                for (i in headers.indices) {
                    val raw = headerNorm[keep[i]]
                    val m = secRegex.matchEntire(raw)
                    if (m != null) {
                        val secNo = m.groupValues[1].toInt()
                        secToColIndex[secNo] = i
                    }
                }

                // ヘッダ行より上に置く「ラップ帯」の行（全パターン）
                if ((1..totalSec).all { secToColIndex.containsKey(it) }) {
                    pBold.textSize = 10f
                    val lapBaseY = y

                    fun spanForColumns(match: (Int) -> Boolean): Pair<Float, Float>? {
                        val idxList = headers.indices.filter(match)
                        if (idxList.isEmpty()) return null
                        val first = idxList.minOrNull()!!
                        val last = idxList.maxOrNull()!!
                        val left = colStartX[first]
                        val right = colStartX[last] + colWidths[last]
                        return left to right
                    }

                    fun drawGroupLabel(label: String, match: (Int) -> Boolean) {
                        val span = spanForColumns(match) ?: return
                        drawCenteredText(c, pBold, label, span.first, span.second, lapBaseY)
                    }

                    // AM Lap1..n
                    for (lap in 1..lapsPerSession) {
                        val startSec = (lap - 1) * sectionsPerLap + 1
                        val endSec = lap * sectionsPerLap
                        drawGroupLabel("AM Lap$lap") { i ->
                            val raw = headerNorm[keep[i]]
                            val m = secRegex.matchEntire(raw)
                            m?.groupValues?.get(1)?.toIntOrNull()
                                ?.let { it in startSec..endSec } == true
                        }
                    }

                    // AM結果
                    drawGroupLabel("AM結果") { i ->
                        val raw = headerNorm[keep[i]]
                        raw == "AmG" || raw == "AmC" || raw == "AmRank"
                    }

                    // PM Lap1..n
                    for (lap in 1..lapsPerSession) {
                        val startSec = perSession + (lap - 1) * sectionsPerLap + 1
                        val endSec = perSession + lap * sectionsPerLap
                        drawGroupLabel("PM Lap$lap") { i ->
                            val raw = headerNorm[keep[i]]
                            val m = secRegex.matchEntire(raw)
                            m?.groupValues?.get(1)?.toIntOrNull()
                                ?.let { it in startSec..endSec } == true
                        }
                    }

                    // PM結果
                    drawGroupLabel("PM結果") { i ->
                        val raw = headerNorm[keep[i]]
                        raw == "PmG" || raw == "PmC" || raw == "PmRank"
                    }

                    // 総合結果
                    drawGroupLabel("総合結果") { i ->
                        val raw = headerNorm[keep[i]]
                        raw == "TotalG" || raw == "TotalC" || raw == "TotalRank"
                    }

                    pBold.textSize = 10f
                    y += 16f               // ラップ帯の高さぶん下げる
                }

                // ヘッダ行
                val headerTop = y - 15f
                val headerBottom = y + 10f

                // ヘッダ背景（行全体）
                c.drawRect(30f, headerTop, 30f + availW, headerBottom, pHeader)

                // A順 / P順 / 順位 の列だけ少し濃いグレーで塗り直す
                headers.forEachIndexed { i, h ->
                    if (h in specialCols) {
                        val left = colStartX[i]
                        val right = left + colWidths[i]
                        pColFill.color = specialBgColor
                        c.drawRect(left, headerTop, right, headerBottom, pColFill)
                    }
                }

                // ヘッダ文字（中央揃え）
                val headerBaseY = y
                headers.forEachIndexed { i, h ->
                    val left = colStartX[i]
                    val right = left + colWidths[i]
                    drawCenteredText(c, pText, h, left, right, headerBaseY)
                }

                // ---------- データ行 ----------
                val rowHeight = 24f
                val horizontalLines = mutableListOf<Float>()
                horizontalLines += headerBottom // ヘッダ下

                y += 25f
                var lastRowBottom = headerBottom

                chunk.forEachIndexed { rIndex, row ->
                    val rowTop = y - 12f
                    val rowBottom = y + 12f

                    // ゼブラ
                    if (rIndex % 2 == 1) {
                        c.drawRect(30f, rowTop + 1f, 30f + availW, rowBottom - 1f, pZebra)
                    }

                    // A順 / P順 / 順位 の列だけ背景塗り
                    headers.indices.forEach { i ->
                        if (headers[i] in specialCols) {
                            val left = colStartX[i]
                            val right = left + colWidths[i]
                            pColFill.color = specialBgColor
                            c.drawRect(left, rowTop, right, rowBottom, pColFill)
                        }
                    }

                    // セル文字（全列中央揃え）
                    val baseLineY = y
                    headers.indices.forEach { i ->
                        val v = row.getOrNull(keep[i])?.trim('"')?.trim().orEmpty()
                        val left = colStartX[i]
                        val right = left + colWidths[i]
                        val text = v
                        val w = pText.measureText(text)
                        val x = left + (right - left - w) / 2f
                        c.drawText(text, x, baseLineY, pText)
                    }

                    lastRowBottom = rowBottom
                    horizontalLines += rowBottom
                    y += rowHeight
                }

                // ---------- 横罫線 ----------
                horizontalLines.forEach { lineY ->
                    c.drawLine(30f, lineY, 30f + availW, lineY, pGrid)
                }

                // ------------ 縦罫線（共通） ------------
                fun colIndexByRaw(rawKey: String): Int? {
                    val idx = keep.indexOfFirst { headerNorm[it] == rawKey }
                    return if (idx >= 0) idx else null
                }

                val topY = headerTop
                val bottomY = lastRowBottom

                // No の右
                colIndexByRaw("EntryNo")?.let { idx ->
                    val x = colStartX[idx] + colWidths[idx]
                    c.drawLine(x, topY, x, bottomY, pGrid)
                }
                // 名前 の右
                colIndexByRaw("Name")?.let { idx ->
                    val x = colStartX[idx] + colWidths[idx]
                    c.drawLine(x, topY, x, bottomY, pGrid)
                }

                // A順(AmRank) の左・右
                colIndexByRaw("AmRank")?.let { idx ->
                    val left = colStartX[idx]
                    val right = colStartX[idx] + colWidths[idx]
                    c.drawLine(left, topY, left, bottomY, pGrid)
                    c.drawLine(right, topY, right, bottomY, pGrid)
                }

                // PG(PmG) の左
                colIndexByRaw("PmG")?.let { idx ->
                    val x = colStartX[idx]
                    c.drawLine(x, topY, x, bottomY, pGrid)
                }

                // P順(PmRank) の左・右
                colIndexByRaw("PmRank")?.let { idx ->
                    val left = colStartX[idx]
                    val right = colStartX[idx] + colWidths[idx]
                    c.drawLine(left, topY, left, bottomY, pGrid)
                    c.drawLine(right, topY, right, bottomY, pGrid)
                }

                // 総合順位(TotalRank) の左
                colIndexByRaw("TotalRank")?.let { idx ->
                    val x = colStartX[idx]
                    c.drawLine(x, topY, x, bottomY, pGrid)
                }

                // ------------ 追加の縦罫線（Lap境界） ------------
                if ((1..totalSec).all { secToColIndex.containsKey(it) }) {
                    fun rightEdgeOfSec(secNo: Int): Float? {
                        val idx = secToColIndex[secNo] ?: return null
                        return colStartX[idx] + colWidths[idx]
                    }

                    // AM/PM各ラップ末尾セクションの右端に線を引く
                    for (lap in 1..lapsPerSession) {
                        val amLast = lap * sectionsPerLap
                        rightEdgeOfSec(amLast)?.let { x ->
                            c.drawLine(x, topY, x, bottomY, pGrid)
                        }
                        val pmLast = perSession + lap * sectionsPerLap
                        rightEdgeOfSec(pmLast)?.let { x ->
                            c.drawLine(x, topY, x, bottomY, pGrid)
                        }
                    }
                }

                pdf.finishPage(page)
            }

            val safeName =
                clazz.ifBlank { "ALL" }.replace("[^a-zA-Z0-9一-龠]".toRegex(), "_")
            val fileName = "result_${pattern.patternCode}_${todayName()}_${safeName}.pdf"

            val bos = ByteArrayOutputStream()
            pdf.writeTo(bos)
            pdf.close()

            if (writeToDownloads(context, fileName, "application/pdf", bos.toByteArray()) != null) {
                anySaved = true
            }
        }

        Toast.makeText(
            context,
            if (anySaved) "✅ クラス別PDF（1クラス=1ファイル）を保存しました" else "❌ PDF出力なし",
            Toast.LENGTH_SHORT
        ).show()
    }
}
