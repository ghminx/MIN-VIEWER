package org.minviewer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class ImportedDocumentProvider : ContentProvider() {
    override fun onCreate() = true
    private fun store() = ImportedDocumentStore(File(requireNotNull(context).filesDir, "imported-documents"))
    override fun getType(uri: Uri): String = when (store().get(requireNotNull(uri.lastPathSegment)).name.substringAfterLast('.').lowercase()) {
        "pdf" -> "application/pdf"
        "hwp" -> "application/x-hwp"
        "hwpx" -> "application/vnd.hancom.hwpx"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "읽기 전용 문서" }
        return ParcelFileDescriptor.open(store().file(requireNotNull(uri.lastPathSegment)), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val item = store().get(requireNotNull(uri.lastPathSegment))
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> item.name
            OpenableColumns.SIZE -> item.bytes
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
