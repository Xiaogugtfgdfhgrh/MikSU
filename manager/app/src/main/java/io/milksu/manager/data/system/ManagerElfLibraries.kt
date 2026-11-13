package io.milksu.manager.data.system

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ManagerElfLibraries {
    private data class Segment(val type: Int, val offset: Long, val address: Long, val size: Long)

    fun rename(bytes: ByteArray, names: Map<String, String>): ByteArray {
        require(bytes.size >= 52 && bytes.take(4) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte()))
        require(bytes[5].toInt() == 1) { "Unsupported ELF byte order" }
        val wide = when (bytes[4].toInt()) {
            1 -> false
            2 -> true
            else -> throw IllegalArgumentException("Unsupported ELF class")
        }
        require(!wide || bytes.size >= 64)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun number(offset: Int): Long = if (wide) buffer.getLong(offset).also { require(it >= 0) }
            else buffer.getInt(offset).toLong() and 0xffffffffL
        fun range(offset: Long, size: Long): Int {
            require(offset >= 0 && size >= 0 && offset <= bytes.size.toLong() - size) { "Invalid ELF file range" }
            return offset.toInt()
        }
        val programOffset = number(if (wide) 32 else 28)
        val programSize = buffer.getShort(if (wide) 54 else 42).toInt() and 0xffff
        val count = buffer.getShort(if (wide) 56 else 44).toInt() and 0xffff
        require(programSize >= if (wide) 56 else 32)
        range(programOffset, programSize.toLong() * count)
        val segments = List(count) { index ->
            val at = (programOffset + programSize.toLong() * index).toInt()
            Segment(buffer.getInt(at), number(at + if (wide) 8 else 4),
                number(at + if (wide) 16 else 8), number(at + if (wide) 32 else 16))
        }
        val dynamicSegments = segments.filter { it.type == 2 }
        require(dynamicSegments.size <= 1) { "Duplicate ELF dynamic table" }
        val dynamic = dynamicSegments.singleOrNull() ?: return bytes
        range(dynamic.offset, dynamic.size)
        val entrySize = if (wide) 16 else 8
        require(dynamic.size % entrySize == 0L)
        var stringAddress: Long? = null
        var stringSize: Long? = null
        val references = mutableListOf<Long>()
        var at = dynamic.offset
        var terminated = false
        while (at < dynamic.offset + dynamic.size) {
            val tag = number(at.toInt())
            val value = number(at.toInt() + entrySize / 2)
            if (tag == 0L) {
                terminated = true
                break
            }
            when (tag) {
                1L, 14L -> references.add(value) // DT_NEEDED and DT_SONAME share .dynstr.
                5L -> { require(stringAddress == null); stringAddress = value }
                10L -> { require(stringSize == null); stringSize = value }
            }
            at += entrySize
        }
        require(terminated) { "Unterminated ELF dynamic table" }
        if (references.isEmpty()) return bytes
        val address = requireNotNull(stringAddress) { "Missing ELF string table" }
        val size = requireNotNull(stringSize) { "Missing ELF string table size" }
        val segment = segments.firstOrNull {
            it.type == 1 && address >= it.address && address - it.address < it.size
        } ?: throw IllegalArgumentException("ELF string table is not file-backed")
        val relative = address - segment.address
        require(size <= segment.size - relative)
        val start = range(segment.offset + relative, size)
        var output: ByteArray? = null
        for (reference in references) {
            require(reference in 0 until size) { "Invalid ELF library string offset" }
            val offset = start + reference.toInt()
            var end = offset
            while (end < start + size.toInt() && bytes[end] != 0.toByte()) end++
            require(end < start + size.toInt()) { "Unterminated ELF library name" }
            val original = bytes.copyOfRange(offset, end).toString(Charsets.US_ASCII)
            val replacement = names[original] ?: continue
            require(replacement.length == original.length &&
                Regex("lib[a-z]+\\.so").matches(replacement)) { "ELF library name length changed" }
            if (output == null) output = bytes.copyOf()
            replacement.toByteArray(Charsets.US_ASCII).copyInto(output, offset)
        }
        return output ?: bytes
    }
}
