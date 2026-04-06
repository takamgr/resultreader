package com.example.resultreader

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

class CsvFileManager(private val context: Activity) {

    fun showCsvListDialog() {
        val csvDir = context.getExternalFilesDir("ResultReader")
        val csvFiles = csvDir?.listFiles { file -> file.extension == "csv" } ?: emptyArray()

        if (csvFiles.isEmpty()) {
            Toast.makeText(context, "保存されたCSVが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = csvFiles.map { it.name }

        val builder = AlertDialog.Builder(context)
        builder.setTitle("CSVファイル一覧")

        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, fileNames)

        builder.setAdapter(adapter) { _, which ->
            // タップ → 開く
            openCsvFile(csvFiles[which])
        }

        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                val fileTarget = csvFiles[position]
                val items = arrayOf("開く", "ダウンロードへコピー", "共有", "削除", "キャンセル")

                AlertDialog.Builder(context)
                    .setTitle(fileTarget.name)
                    .setItems(items) { d, which ->
                         when (which) {
                             0 -> { // 開く
                                 openCsvFile(fileTarget)
                             }
                             1 -> { // ダウンロードへコピー
                                 val uri = copyToDownloads(fileTarget)
                                 if (uri != null) {
                                     Toast.makeText(context, "📂 ダウンロードにコピーしました\n${fileTarget.name}", Toast.LENGTH_LONG).show()
                                 } else {
                                     Toast.makeText(context, "コピーに失敗しました", Toast.LENGTH_SHORT).show()
                                 }
                             }
                             2 -> { // 共有
                                 shareCsvFile(fileTarget)
                             }
                             3 -> { // 削除（既存仕様を維持）
                                 AlertDialog.Builder(context)
                                     .setTitle("削除確認")
                                     .setMessage("「${fileTarget.name}」を削除しますか？")
                                     .setPositiveButton("削除") { _, _ ->
                                         if (fileTarget.delete()) {
                                             Toast.makeText(context, "削除しました", Toast.LENGTH_SHORT).show()
                                             // 一覧更新
                                             context.findViewById<ImageButton>(R.id.openCsvImageButton).performClick()
                                         } else {
                                             Toast.makeText(context, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                                         }
                                         dialog.dismiss()
                                     }
                                     .setNegativeButton("キャンセル", null)
                                     .show()
                             }
                             else -> d.dismiss()
                         }
                     }
                    .show()

                true
            }
        }

        dialog.show()
    }

    fun showExportPopupMenu(anchor: View, selectedPattern: TournamentPattern) {
        val popup = PopupMenu(context, anchor)

        popup.menu.add(0, 3, 2, "CSVを保存")
        // Canvasベースの安定版PDF出力を追加
        popup.menu.add(0, 5, 3, "PDFを保存（Canvas）")
        // optional: popup.menu.add(0, 4, 3, "PDFを開く/印刷")

        popup.setOnMenuItemClickListener { item ->
             when (item.itemId) {
                1 -> {
                    // S1版CSV（HTML path removed; keep S1 CSV)
                    PrintableExporter.exportS1CsvToDownloads(context, selectedPattern)
                    true
                }
                2 -> {

                    // Canvas unified PDF (per-class single page behavior folded into unified renderer)
                    PrintableExporter.exportPrintablePdfStyledSplitByClass(context, selectedPattern, rowsPerPage = 20)
                    true
                }



                5 -> {
                    // Canvas PDF with per-row thin lines enabled for visual verification
                    PrintableExporter.exportPrintablePdfStyledSplitByClass(context, selectedPattern, rowsPerPage = 20)
                    true
                }

                3 -> {
                    PrintableExporter.exportS1CsvToDownloads(context, selectedPattern)
                    true
                }
                4 -> {
                    // legacy: map to unified Canvas exporter
                    PrintableExporter.exportPrintablePdfStyledSplitByClass(context, selectedPattern, rowsPerPage = 20)
                    true
                }
                 else -> false
             }
         }

        popup.show()
    }

    private fun openCsvFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "com.example.resultreader.fileprovider", // ← 固定authority
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "CSVを開くアプリが見つかりません", Toast.LENGTH_SHORT).show()
        }




    }

    // ヘルパー: Downloads にコピー（API29+ は MediaStore、旧API は直接コピー）
    private fun copyToDownloads(src: File): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, src.name)
                src.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            Log.e("EXPORT", "Downloadコピー失敗: ${src.name}", e)
            null
        }
    }

    // ヘルパー: CSV を共有
    private fun shareCsvFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "com.example.resultreader.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "CSVを共有"))
    }
}
