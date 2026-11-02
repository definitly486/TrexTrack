package org.definitly.trextrack

import android.annotation.SuppressLint
import android.util.Log
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Huami / Zepp OS 2021 chunked transfer (endpoint frames over CHAR 0x0016 / 0x0017).
 * Ported from Gadgetbridge (AGPL) Huami2021ChunkedEncoder / Huami2021ChunkedDecoder —
 * simplified for TrexTrack auth + future GPS file transfer.
 */
object Huami2021Crypto {
    @SuppressLint("GetInstance")
    fun encryptAesEcb(value: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(value)
    }

    @SuppressLint("GetInstance")
    fun decryptAesEcb(value: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(value)
    }

    fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        val c = CRC32()
        c.update(data, offset, length)
        return c.value.toInt()
    }
}

fun interface Huami2021PayloadHandler {
    fun handle2021Payload(type: Short, payload: ByteArray)
}

class Huami2021ChunkedEncoder(private var mtu: Int = 23) {
    private var writeHandle: Byte = 0
    @Volatile private var encryptedSequenceNr = 0
    @Volatile private var sharedSessionKey: ByteArray? = null

    fun setEncryptionParameters(encryptedSequenceNr: Int, sharedSessionKey: ByteArray) {
        this.encryptedSequenceNr = encryptedSequenceNr
        this.sharedSessionKey = sharedSessionKey
    }

    fun setMtu(mtu: Int) {
        this.mtu = mtu
    }

    fun reset() {
        writeHandle = 0
        encryptedSequenceNr = 0
    }

    /**
     * Encode [data] for endpoint [type] and call [chunkWriter] for each GATT write.
     * Framing matches Gadgetbridge Huami2021ChunkedEncoder (header_size 10/11 first chunk).
     */
    @Synchronized
    fun write(
        chunkWriter: (ByteArray) -> Unit,
        type: Short,
        data: ByteArray,
        extendedFlags: Boolean,
        encrypt: Boolean
    ) {
        if (encrypt && sharedSessionKey == null) {
            Log.e(TAG, "Can't encrypt without shared session key")
            return
        }

        // Gadgetbridge increments handle at the start of each logical message
        writeHandle++

        var remaining = data.size
        val length = data.size
        var count: Byte = 0
        // First-chunk header includes length(4)+type(2): base 10, +1 if extended_flags
        var headerSize = 10
        if (extendedFlags) headerSize++

        var dataToSend = data
        if (extendedFlags && encrypt) {
            val key = sharedSessionKey!!
            val messageKey = ByteArray(16)
            for (i in 0 until 16) {
                messageKey[i] = (key[i].toInt() xor writeHandle.toInt()).toByte()
            }
            var encryptedLength = length + 8
            val overflow = encryptedLength % 16
            if (overflow > 0) encryptedLength += 16 - overflow

            val encryptable = ByteArray(encryptedLength)
            System.arraycopy(data, 0, encryptable, 0, length)
            encryptable[length] = (encryptedSequenceNr and 0xff).toByte()
            encryptable[length + 1] = ((encryptedSequenceNr shr 8) and 0xff).toByte()
            encryptable[length + 2] = ((encryptedSequenceNr shr 16) and 0xff).toByte()
            encryptable[length + 3] = ((encryptedSequenceNr shr 24) and 0xff).toByte()
            encryptedSequenceNr++
            val checksum = Huami2021Crypto.crc32(encryptable, 0, length + 4)
            encryptable[length + 4] = (checksum and 0xff).toByte()
            encryptable[length + 5] = ((checksum shr 8) and 0xff).toByte()
            encryptable[length + 6] = ((checksum shr 16) and 0xff).toByte()
            encryptable[length + 7] = ((checksum shr 24) and 0xff).toByte()
            remaining = encryptedLength
            try {
                dataToSend = Huami2021Crypto.encryptAesEcb(encryptable, messageKey)
            } catch (e: Exception) {
                Log.e(TAG, "encrypt failed", e)
                return
            }
        }

        while (remaining > 0) {
            val maxChunk = calcMaxWriteChunk(mtu) - headerSize
            if (maxChunk <= 0) {
                Log.e(TAG, "MTU too small: mtu=$mtu header=$headerSize")
                return
            }
            val copyBytes = minOf(remaining, maxChunk)
            val chunk = ByteArray(copyBytes + headerSize)

            var flags = 0
            if (encrypt) flags = flags or 0x08
            if (count.toInt() == 0) {
                flags = flags or 0x01 // first
                var i = 4
                if (extendedFlags) i++
                chunk[i++] = (length and 0xff).toByte()
                chunk[i++] = ((length shr 8) and 0xff).toByte()
                chunk[i++] = ((length shr 16) and 0xff).toByte()
                chunk[i++] = ((length shr 24) and 0xff).toByte()
                chunk[i++] = (type.toInt() and 0xff).toByte()
                chunk[i] = ((type.toInt() shr 8) and 0xff).toByte()
            }
            if (remaining <= maxChunk) {
                flags = flags or 0x02 // last
                flags = flags or 0x04 // needs ack
            }
            chunk[0] = 0x03
            chunk[1] = flags.toByte()
            if (extendedFlags) {
                chunk[2] = 0
                chunk[3] = writeHandle
                chunk[4] = count
            } else {
                chunk[2] = writeHandle
                chunk[3] = count
            }

            System.arraycopy(dataToSend, dataToSend.size - remaining, chunk, headerSize, copyBytes)
            chunkWriter(chunk)
            remaining -= copyBytes
            // Subsequent chunks: only flags+handle+count (+ext)
            headerSize = 4
            if (extendedFlags) headerSize++
            count++
        }
    }

    companion object {
        private const val TAG = "Huami2021Enc"
        fun calcMaxWriteChunk(mtu: Int): Int = maxOf(20, mtu - 3)
    }
}

class Huami2021ChunkedDecoder(
    private var handler: Huami2021PayloadHandler?,
    private val force2021Protocol: Boolean = true
) {
    private var currentHandle: Byte? = null
    private var currentType = 0
    private var currentLength = 0
    private var reassemblyBuffer: ByteBuffer? = null
    var lastHandle: Byte = 0
        private set
    var lastCount: Byte = 0
        private set
    @Volatile private var sharedSessionKey: ByteArray? = null

    fun setEncryptionParameters(sharedSessionKey: ByteArray) {
        this.sharedSessionKey = sharedSessionKey
    }

    fun setHandler(handler: Huami2021PayloadHandler) {
        this.handler = handler
    }

    /**
     * @return true if the peer expects an ACK write on the READ characteristic.
     */
    fun decode(data: ByteArray): Boolean {
        if (data.isEmpty() || data[0] != 0x03.toByte()) {
            Log.w(TAG, "Ignoring non-chunked payload")
            return false
        }
        var i = 1
        val flags = data[i++]
        val encrypted = (flags.toInt() and 0x08) != 0
        val firstChunk = (flags.toInt() and 0x01) != 0
        val lastChunk = (flags.toInt() and 0x02) != 0
        val needsAck = (flags.toInt() and 0x04) != 0
        val extended = force2021Protocol || (flags.toInt() and 0x10) != 0
        if (extended) {
            i++ // skip extra flag byte
        }
        val handle = data[i++]
        if (currentHandle != null && currentHandle != handle) {
            Log.w(TAG, "ignoring handle $handle, expected $currentHandle")
            return false
        }
        lastHandle = handle
        lastCount = data[i++]
        if (firstChunk) {
            val fullLength =
                (data[i++].toInt() and 0xff) or
                    ((data[i++].toInt() and 0xff) shl 8) or
                    ((data[i++].toInt() and 0xff) shl 16) or
                    ((data[i++].toInt() and 0xff) shl 24)
            currentLength = fullLength
            var alloc = fullLength
            if (encrypted) {
                var encLen = fullLength + 8
                val overflow = encLen % 16
                if (overflow > 0) encLen += 16 - overflow
                alloc = encLen
            }
            reassemblyBuffer = ByteBuffer.allocate(alloc)
            currentType =
                (data[i++].toInt() and 0xff) or ((data[i++].toInt() and 0xff) shl 8)
            currentHandle = handle
        }
        val buf = reassemblyBuffer ?: return false
        buf.put(data, i, data.size - i)
        if (lastChunk) {
            var out = buf.array()
            if (encrypted) {
                val session = sharedSessionKey
                if (session == null) {
                    Log.w(TAG, "encrypted message without session key")
                    reset()
                    return false
                }
                val messageKey = ByteArray(16)
                for (j in 0 until 16) {
                    messageKey[j] = (session[j].toInt() xor handle.toInt()).toByte()
                }
                try {
                    out = Huami2021Crypto.decryptAesEcb(out, messageKey)
                    out = out.copyOf(currentLength)
                } catch (e: Exception) {
                    Log.w(TAG, "decrypt failed: ${e.message}")
                    reset()
                    return false
                }
            }
            Log.d(TAG, "payload type=0x${"%04x".format(currentType)} len=${out.size} " +
                HuamiBleProtocol.hex(out.copyOf(minOf(32, out.size))))
            try {
                handler?.handle2021Payload(currentType.toShort(), out)
            } catch (e: Exception) {
                Log.e(TAG, "handle payload", e)
            }
            reset()
        }
        return needsAck
    }

    fun reset() {
        currentHandle = null
        currentType = 0
        reassemblyBuffer = null
    }

    companion object {
        private const val TAG = "Huami2021Dec"
    }
}
