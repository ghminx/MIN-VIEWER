package org.minviewer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class PdfNoteProvider : ContentProvider() {
    override fun onCreate() = true
    private fun store() = PdfInkStore(File(requireNotNull(context).filesDir, "pdf-notes"))
    override fun getType(uri: Uri) = "application/pdf"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "읽기 전용 문서" }
        return ParcelFileDescriptor.open(store().original(requireNotNull(uri.lastPathSegment)), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val id = requireNotNull(uri.lastPathSegment)
        val note = store().load(id)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> "[필기] ${note.name}"
            OpenableColumns.SIZE -> store().original(id).length()
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
