package org.minviewer.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(val uri: Uri, val name: String, val openedAt: Long)

class RecentDocuments(private val context: Context) {
    private val preferences = context.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)

    fun all(): List<RecentDocument> {
        val array = JSONArray(preferences.getString("items", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RecentDocument(Uri.parse(item.getString("uri")), item.getString("name"), item.getLong("openedAt"))
        }
    }

    fun add(uri: Uri, name: String) {
        if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return
        save((listOf(RecentDocument(uri, name, System.currentTimeMillis())) + all().filterNot { it.uri == uri }).take(50))
    }

    fun remove(uri: Uri) = save(all().filterNot { it.uri == uri })

    fun clear() = save(emptyList())

    private fun save(items: List<RecentDocument>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("uri", item.uri.toString()).put("name", item.name).put("openedAt", item.openedAt))
        }
        preferences.edit().putString("items", array.toString()).apply()
    }
}
