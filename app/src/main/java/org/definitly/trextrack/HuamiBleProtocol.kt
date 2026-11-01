package org.definitly.trextrack

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal Huami / Amazfit BLE protocol helpers for TrexTrack.
 *
 * Classic (Mi Band 2-style) auth:
 *  1. Enable notifications on AUTH characteristic
 *  2. AUTH_SEND_KEY (optional on some devices) or AUTH_REQUEST_RANDOM
 *  3. Band sends random 16 bytes
 *  4. Phone encrypts with AES-ECB using the 16-byte auth key and sends back
 *  5. Band replies SUCCESS → authenticated
 *
 * Activity fetch (after auth):
 *  - Write start-date + COMMAND_FETCH_DATA to ACTIVITY_CONTROL
 *  - Receive chunks on ACTIVITY_DATA notifications
 *
 * Zepp OS (T-Rex 2) often uses ECDH + chunked 2021 endpoints instead of classic AES.
 * We detect those characteristics and report them; full ECDH/file-transfer is next step.
 */
object HuamiBleProtocol {

    private const val TAG = "HuamiBle"

    // Services
    val SERVICE_MIBAND: UUID = uuid16("fee0")
    val SERVICE_MIBAND2: UUID = uuid16("fee1")

    // Characteristics (under fee0 / 3512 base)
    val CHAR_AUTH: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")
    val CHAR_ACTIVITY_CONTROL: UUID = UUID.fromString("00000004-0000-3512-2118-0009af100700")
    val CHAR_ACTIVITY_DATA: UUID = UUID.fromString("00000005-0000-3512-2118-0009af100700")
    val CHAR_WORKOUT: UUID = UUID.fromString("0000000f-0000-3512-2118-0009af100700")
    val CHAR_CHUNKED_2021_WRITE: UUID = UUID.fromString("00000016-0000-3512-2118-0009af100700")
    val CHAR_CHUNKED_2021_READ: UUID = UUID.fromString("00000017-0000-3512-2118-0009af100700")
    val CHAR_ZEPP_FT_SEND: UUID = UUID.fromString("00000023-0000-3512-2118-0009af100700")
    val CHAR_ZEPP_FT_RECV: UUID = UUID.fromString("00000024-0000-3512-2118-0009af100700")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Auth opcodes (classic)
    const val AUTH_SEND_KEY: Byte = 0x01
    const val AUTH_REQUEST_RANDOM: Byte = 0x02
    const val AUTH_SEND_ENCRYPTED: Byte = 0x03
    const val AUTH_RESPONSE: Byte = 0x10
    const val AUTH_SUCCESS: Byte = 0x01
    const val AUTH_FAIL: Byte = 0x04
    const val AUTH_FLAGS: Byte = 0x08

    // Activity control
    const val CMD_ACTIVITY_START_DATE: Byte = 0x01
    const val CMD_ACTIVITY_TYPE_ACTIVITY: Byte = 0x01
    const val CMD_FETCH_DATA: Byte = 0x02
    const val CMD_ACK_ACTIVITY: Byte = 0x03

    enum class AuthState {
        IDLE,
        WAITING_RANDOM,
        WAITING_CONFIRM,
        AUTHENTICATED,
        FAILED
    }

    fun parseAuthKeyHex(hex: String?): ByteArray? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().lowercase().removePrefix("0x").replace(" ", "").replace(":", "")
        if (clean.length != 32 && clean.length != 16) return null
        return try {
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun aesEncryptEcb(key: ByteArray, data16: ByteArray): ByteArray {
        require(data16.size == 16) { "AES block must be 16 bytes" }
        val key16 = when {
            key.size == 16 -> key
            key.size > 16 -> key.copyOf(16)
            else -> key.copyOf(16) // pad with zeros (rare)
        }
        @SuppressLint("GetInstance")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"))
        return cipher.doFinal(data16)
    }

    /** Build classic request-random packet. */
    fun packetRequestRandom(): ByteArray =
        byteArrayOf(AUTH_REQUEST_RANDOM, AUTH_FLAGS)

    /** Build encrypted response packet after receiving random from band. */
    fun packetEncryptedRandom(encrypted16: ByteArray): ByteArray =
        byteArrayOf(AUTH_SEND_ENCRYPTED, AUTH_FLAGS) + encrypted16

    /**
     * Parse auth notification.
     * @return Pair(nextStateHint, optionalRandom16) or null if not an auth response.
     */
    fun parseAuthNotification(value: ByteArray, key: ByteArray): AuthParseResult? {
        if (value.size < 3) return null
        if (value[0] != AUTH_RESPONSE) return null
        val step = (value[1].toInt() and 0x0f).toByte()
        val status = value[2]
        return when {
            step == AUTH_REQUEST_RANDOM && status == AUTH_SUCCESS && value.size >= 19 -> {
                val random = value.copyOfRange(3, 19)
                val encrypted = aesEncryptEcb(key, random)
                AuthParseResult.GotRandom(encrypted)
            }
            step == AUTH_SEND_ENCRYPTED && status == AUTH_SUCCESS ->
                AuthParseResult.Success
            step == AUTH_SEND_ENCRYPTED && status == AUTH_FAIL ->
                AuthParseResult.Fail
            step == AUTH_SEND_KEY && status == AUTH_SUCCESS ->
                AuthParseResult.KeyAccepted
            else -> AuthParseResult.Unknown(value)
        }
    }

    sealed class AuthParseResult {
        data class GotRandom(val encryptedResponse: ByteArray) : AuthParseResult()
        object KeyAccepted : AuthParseResult()
        object Success : AuthParseResult()
        object Fail : AuthParseResult()
        data class Unknown(val raw: ByteArray) : AuthParseResult()
    }

    /**
     * Request activity data starting from [fromEpochSec] (UTC).
     * Classic format: 01 01 + 4-byte little-endian unix time + padding zeros.
     */
    fun packetFetchActivityFrom(fromEpochSec: Long): ByteArray {
        val t = fromEpochSec.toInt()
        return byteArrayOf(
            CMD_ACTIVITY_START_DATE,
            CMD_ACTIVITY_TYPE_ACTIVITY,
            (t and 0xff).toByte(),
            ((t shr 8) and 0xff).toByte(),
            ((t shr 16) and 0xff).toByte(),
            ((t shr 24) and 0xff).toByte(),
            0, 0 // timezone / flags placeholder used by some firmwares
        )
    }

    fun packetFetchData(): ByteArray = byteArrayOf(CMD_FETCH_DATA)

    fun packetAckActivity(): ByteArray = byteArrayOf(CMD_ACK_ACTIVITY)

    fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in gatt.services) {
            val c = s.getCharacteristic(uuid)
            if (c != null) return c
        }
        return null
    }

    fun hasZeppOsTransfer(gatt: BluetoothGatt): Boolean =
        findCharacteristic(gatt, CHAR_ZEPP_FT_SEND) != null ||
            findCharacteristic(gatt, CHAR_CHUNKED_2021_WRITE) != null

    fun hasClassicAuth(gatt: BluetoothGatt): Boolean =
        findCharacteristic(gatt, CHAR_AUTH) != null

    fun hasActivityChars(gatt: BluetoothGatt): Boolean =
        findCharacteristic(gatt, CHAR_ACTIVITY_CONTROL) != null &&
            findCharacteristic(gatt, CHAR_ACTIVITY_DATA) != null

    @SuppressLint("MissingPermission")
    fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
            return false
        }
        val cccd = characteristic.getDescriptor(CCCD) ?: run {
            Log.w(TAG, "no CCCD on ${characteristic.uuid}")
            return false
        }
        val props = characteristic.properties
        val value = when {
            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        cccd.value = value
        return gatt.writeDescriptor(cccd)
    }

    fun hex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
