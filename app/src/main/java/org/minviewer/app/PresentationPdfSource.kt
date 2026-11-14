package org.minviewer.app

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 정지 화면·PDF용 임시 PPTX 생성. 원본·첫 프레임 압축 픽셀·애니메이션 캔버스 보존. */
internal object PresentationPdfSource {
    fun create(source: File, target: File, cancelled: () -> Boolean): Boolean {
        ZipFile(source).use { zip ->
            val animated = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("ppt/media/") && it.name.endsWith(".gif", true) }
                .filter { entry -> zip.getInputStream(entry).use { firstFrame(it, null) } }.map { it.name }.toSet()
            if (animated.isEmpty()) return false
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                zip.entries().asSequence().forEach { entry ->
                    if (cancelled()) throw InterruptedIOException("작업을 취소했습니다.")
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) zip.getInputStream(entry).use { input ->
                        if (entry.name in animated) check(firstFrame(input, output)) else input.copyTo(output)
                    }
                    output.closeEntry()
                }
            }
        }
        return true
    }

    // 전체 캔버스를 채우는 첫 프레임만 대상. 부분 프레임 합성이 필요한 GIF는 원본 경로 유지.
    private fun firstFrame(stream: InputStream, output: OutputStream?): Boolean {
        val input = DataInputStream(stream.buffered())
        val header = ByteArray(13); input.readFully(header)
        if (String(header, 0, 6, Charsets.US_ASCII) !in listOf("GIF87a", "GIF89a")) return false
        fun word(bytes: ByteArray, index: Int) = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
        output?.write(header)
        val buffer = ByteArray(768)
        var target = output
        fun copy(count: Int) { input.readFully(buffer, 0, count); target?.write(buffer, 0, count) }
        fun blocks() {
            while (true) {
                val count = input.readUnsignedByte(); target?.write(count)
                if (count == 0) return
                copy(count)
            }
        }
        val flags = header[10].toInt() and 255
        if (flags and 128 != 0) copy(3 * (2 shl (flags and 7)))
        var images = 0
        var width = word(header, 6); var height = word(header, 8)
        while (true) {
            val block = input.readUnsignedByte()
            when (block) {
                0x21 -> {
                    val label = input.readUnsignedByte()
                    // 제거한 프레임의 제어만 제외하고 논리 크기·메타데이터 확장은 그대로 보존
                    target = if (images == 0 || label != 0xf9) output else null
                    target?.write(block); target?.write(label); blocks()
                }
                0x2c -> {
                    target = if (images == 0) output else null
                    target?.write(block)
                    val descriptor = ByteArray(9); input.readFully(descriptor)
                    if (images == 0 && (word(descriptor, 0) != 0 || word(descriptor, 2) != 0 || word(descriptor, 4) != word(header, 6) || word(descriptor, 6) != word(header, 8))) return false
                    width = maxOf(width, word(descriptor, 0) + word(descriptor, 4))
                    height = maxOf(height, word(descriptor, 2) + word(descriptor, 6))
                    target?.write(descriptor)
                    val local = descriptor[8].toInt() and 255
                    if (local and 128 != 0) copy(3 * (2 shl (local and 7)))
                    val codeSize = input.readUnsignedByte(); target?.write(codeSize); blocks(); images++
                }
                0x3b -> {
                    if (images <= 1 || width !in 1..65536 || height !in 1..65536) return false
                    if (output != null) {
                        // 뒤쪽 프레임까지 합친 영역을 투명 1px 프레임의 위치로 보존. 엔진의 자르기 경로 유지.
                        val x = width - 1; val y = height - 1
                        output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 1, 0, 0, 0, 0,
                            0x2c, x.toByte(), (x shr 8).toByte(), y.toByte(), (y shr 8).toByte(), 1, 0, 1, 0,
                            0x80.toByte(), 0, 0, 0, 0, 0, 0, 2, 2, 0x44, 1, 0, 0x3b))
                    }
                    return true
                }
                else -> return false
            }
        }
    }
}
