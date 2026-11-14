package org.minviewer.app

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** Office와 PDF 프로세스가 공유하는 읽기 사본의 사용 수명과 중단 파일 회수. */
class WordSnapshot private constructor(val directory: File, private val channel: FileChannel, private val lease: FileLock) : Closeable {
    @Synchronized override fun close() {
        if (!channel.isOpen) return
        try { lease.release() } finally { channel.close() }
    }

    companion object {
        private val id = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        @Synchronized private fun <T> locked(cache: File, action: (File) -> T): T {
            val root = File(cache, "word-view")
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Word 읽기 폴더를 만들 수 없습니다.")
            // 두 프로세스의 새 사본 생성·인수와 중단 폴더 삭제를 직렬화
            return RandomAccessFile(File(root, ".cleanup"), "rw").channel.use { channel ->
                channel.lock().use { action(root) }
            }
        }

        fun create(cache: File): WordSnapshot = locked(cache) { root ->
            cleanupDirectories(root)
            val directory = File(root, UUID.randomUUID().toString())
            if (!directory.mkdir()) throw IOException("Word 읽기 폴더를 만들 수 없습니다.")
            acquire(directory)
        }

        fun open(cache: File, name: String): WordSnapshot {
            require(id.matches(name)) { "Word 읽기 식별자가 올바르지 않습니다." }
            return locked(cache) { root -> acquire(File(root, name)) }
        }

        private fun acquire(directory: File): WordSnapshot {
            if (!directory.isDirectory) throw IOException("Word 읽기 사본이 없습니다. 원본을 다시 열어 주세요.")
            val channel = RandomAccessFile(File(directory, ".lease"), "rw").channel
            try { return WordSnapshot(directory, channel, channel.lock(0L, Long.MAX_VALUE, true)) }
            catch (error: Exception) { channel.close(); throw error }
        }

        fun cleanup(cache: File) {
            if (!File(cache, "word-view").exists()) return
            locked(cache) { cleanupDirectories(it) }
        }

        private fun cleanupDirectories(root: File) {
            val directories = root.listFiles() ?: throw IOException("Word 읽기 폴더를 확인할 수 없습니다.")
            for (directory in directories.filter { it.isDirectory && id.matches(it.name) }) {
                RandomAccessFile(File(directory, ".lease"), "rw").channel.use { channel ->
                    // 같은 프로세스와 Office 프로세스의 활성 사본 모두 보존
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    lock?.use {
                        if (!directory.deleteRecursively()) throw IOException("중단된 Word 읽기 사본을 정리할 수 없습니다.")
                    }
                }
            }
        }
    }
}
