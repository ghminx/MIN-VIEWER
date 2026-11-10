package org.minviewer.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(val uri: Uri, val name: String, val openedAt: Long, val page: Int = 0, val mode: String = "", val zoom: Float = 1f, val x: Int = 0, val y: Int = 0)

class RecentDocuments(private val context: Context) {
    companion object { private val lock = Any() }
    private val preferences = context.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)

    fun all(): List<RecentDocument> = synchronized(lock) {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RecentDocument(Uri.parse(item.getString("uri")), item.getString("name"), item.getLong("openedAt"),
                item.optInt("page", 0), item.optString("mode", ""), item.optDouble("zoom", 1.0).toFloat(),
                item.optInt("x", 0), item.optInt("y", 0))
        }
    }

    fun add(uri: Uri, name: String) = synchronized(lock) {
        if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return
        val items = all()
        val previous = items.firstOrNull { it.uri == uri }
        val current = previous?.copy(name = name, openedAt = System.currentTimeMillis())
            ?: RecentDocument(uri, name, System.currentTimeMillis())
        save((listOf(current) + items.filterNot { it.uri == uri }).take(50))
    }

    fun updateView(uri: Uri, page: Int, mode: String, zoom: Float, x: Int = 0, y: Int = 0) = synchronized(lock) {
        val items = all()
        val current = items.firstOrNull { it.uri == uri } ?: return
        if (current.page == page && current.mode == mode && current.zoom == zoom && current.x == x && current.y == y) return
        save(items.map { if (it.uri == uri) it.copy(page = page, mode = mode, zoom = zoom, x = x, y = y) else it })
    }

    fun remove(uri: Uri) = synchronized(lock) { save(all().filterNot { it.uri == uri }) }

    fun clear() = synchronized(lock) { save(emptyList()) }

    private fun save(items: List<RecentDocument>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("uri", item.uri.toString()).put("name", item.name).put("openedAt", item.openedAt)
                .put("page", item.page).put("mode", item.mode).put("zoom", item.zoom.toDouble()).put("x", item.x).put("y", item.y))
        }
        preferences.edit().putString("items", array.toString()).apply()
    }
}
