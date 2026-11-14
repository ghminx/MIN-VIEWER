package org.minviewer.app

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** 시스템 저장창에서 반환한 출력 파일에 준비된 사본 기록. */
object CreatedDocumentOutput {
    fun write(resolver: ContentResolver, uri: Uri, source: File, canceled: () -> Boolean) {
        if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("저장 위치를 열 수 없습니다.")
        descriptor.use {
            // 기존 문서 또는 크기를 확인할 수 없는 제공자는 덮어쓰기 전에 중단
            if (it.statSize != 0L) throw IOException("비어 있는 새 파일에만 저장할 수 있습니다. 다른 이름이나 로컬 저장 위치를 선택해 주세요.")
            ParcelFileDescriptor.AutoCloseOutputStream(it).use { target ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
                        val size = input.read(buffer)
                        if (size < 0) break
                        target.write(buffer, 0, size)
                    }
                }
            }
        }
    }
}
