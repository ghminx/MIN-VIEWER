package org.minviewer.app

import android.content.Context
import android.net.Uri

data class PageViewing(val landscapePages: Int = 1, val scrolling: Boolean = false, val cover: Boolean = false) {
    fun columns(width: Int, height: Int) = if (width > height) landscapePages else 1
    fun row(page: Int, columns: Int) = if (columns == 1) page else if (cover) (page + 1) / 2 else page / 2
    fun first(row: Int, columns: Int) = if (columns == 1) row else if (cover && row > 0) row * 2 - 1 else row * 2
    fun rows(count: Int, columns: Int) = if (columns == 1) count else if (cover) 1 + count / 2 else (count + 1) / 2

    fun save(context: Context, uri: Uri) {
        context.getSharedPreferences("page_viewing", Context.MODE_PRIVATE).edit()
            .putString(uri.toString(), "$landscapePages:${if(scrolling)1 else 0}:${if(cover)1 else 0}").apply()
    }

    companion object {
        fun load(context: Context, uri: Uri): PageViewing {
            val value=context.getSharedPreferences("page_viewing", Context.MODE_PRIVATE).getString(uri.toString(), null)
            if(value==null){
                val legacy=RecentDocuments(context).all().firstOrNull{it.uri==uri}?.mode
                return PageViewing(if(legacy in listOf("2","spread"))2 else 1,legacy in listOf("0","continuous"))
            }
            val fields=value.split(':')
            return PageViewing(fields[0].toInt(), fields[1]=="1", fields[2]=="1")
        }
    }
}
