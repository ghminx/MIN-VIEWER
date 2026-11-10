package org.minviewer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class ViewStateProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        require(method == "save" && extras != null)
        val uri = Uri.parse(requireNotNull(extras.getString("uri")))
        val recent = RecentDocuments(requireNotNull(context))
        if (extras.getBoolean("opened")) recent.add(uri, requireNotNull(extras.getString("name")))
        recent.updateView(uri, extras.getInt("page"), extras.getString("mode") ?: "", extras.getFloat("zoom", 1f),
            extras.getInt("x"), extras.getInt("y"))
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
