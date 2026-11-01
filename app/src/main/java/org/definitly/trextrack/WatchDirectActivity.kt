package org.definitly.trextrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Страница прямой работы с часами Amazfit T-Rex 2 (и совместимыми Zepp OS).
 *
 * Реализовано:
 * - сканирование BLE;
 * - подключение GATT;
 * - обнаружение сервисов/характеристик;
 * - ввод Huami auth key (нужен для полной аутентификации, как в Gadgetbridge);
 * - заготовка загрузки списка/треков с часов.
 *
 * Полная выгрузка GPS-треков с часов требует reverse-engineered протокола Huami/Zepp OS
 * (см. Gadgetbridge). Здесь заложены UI и каркас; загрузка треков сейчас возвращает
 * понятный статус и логирует доступные сервисы.
 */
class WatchDirectActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var deviceListView: TextView
    private lateinit var servicesView: TextView
    private lateinit var scanButton: Button
    private lateinit var connectButton: Button
    private lateinit var loadTracksButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var authKeyButton: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanning = false
    private val foundDevices = LinkedHashMap<String, BluetoothDevice>()
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var connected = false
    private var discoveredServices: List<BluetoothGattService> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeoutMs = 12_000L

    /** Huami / Amazfit auth key (hex), optional — required for authenticated sync on T-Rex 2. */
    private var authKeyHex: String? = null
    private lateinit var sessionStore: SessionStore
    private lateinit var repository: ZeppCloudRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var fetchKeyButton: Button
    private lateinit var keyStatusView: TextView
    private var pendingGpxContent: String? = null
    private var pendingGpxName: String? = null

    // Common Huami / Zepp OS related UUIDs (informational)
    private val HUAMI_SERVICE = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    private val HUAMI_AUTH_SERVICE = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")

    /** Classic Huami auth state machine. */
    private var authState = HuamiBleProtocol.AuthState.IDLE
    private var authKeyBytes: ByteArray? = null
    private var activityBytesReceived = 0
    private val activityBuffer = ArrayList<ByteArray>()
    private var pendingDescriptorWrites = 0
    private var loadInProgress = false
    private var finishPosted = false

    // Zepp OS / Huami 2021 chunked auth
    private var chunkedEncoder: Huami2021ChunkedEncoder? = null
    private var chunkedDecoder: Huami2021ChunkedDecoder? = null
    private var ecdhPrivate: ByteArray? = null
    private var ecdhPublic: ByteArray? = null
    private var sessionAesKey: ByteArray? = null
    private var zeppAuthInProgress = false
    private val gattWriteQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var gattWriteInFlight = false
    private var negotiatedMtu: Int = 23
    private val supportedEndpoints = mutableSetOf<Short>()
    private var activityEndpointEncrypted = true
    /** true → write sports cmds via 0x004b; false → classic CHAR_ACTIVITY_CONTROL */
    private var useChunkedActivityFetch = false

    // Sports summary fetch (endpoint 0x004b)
    private var sportsFetchActive = false
    private var expectedSportsBytes = -1
    private var sportsPacketCounter: Byte = -1
    private val sportsDataBuffer = java.io.ByteArrayOutputStream()
    /** 0x05 summaries, 0x06 details */
    private var sportsFetchType: Byte = 0x05
    private var sportsSinceCal: java.util.Calendar? = null
    /** Exact timestamp bytes returned by the watch (year..second, timezone). */
    private var sportsSinceTimeBytes: ByteArray? = null
    private var lastParsedSummary: ParsedWorkoutSummary? = null
    private var lastGpsPoints: List<GpsPoint> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "Прямая работа с часами"
            textSize = 25f
            setTextColor(0xFF152238.toInt())
        }, matchWrap())

        content.addView(TextView(this).apply {
            text = "Amazfit T-Rex 2 · BLE / GATT\nПодключение к часам и загрузка треков напрямую (без облака)."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        }, matchWrap())

        statusView = TextView(this).apply {
            text = "Статус: не подключено"
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFF0D9.toInt())
            setTextColor(0xFF8A3B12.toInt())
        }
        content.addView(statusView, matchWrap())

        scanButton = Button(this).apply {
            text = "Сканировать BLE"
            setOnClickListener { startScan() }
        }
        content.addView(scanButton, matchWrap())

        content.addView(TextView(this).apply {
            text = "Найденные устройства (нажмите, чтобы выбрать)"
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
        }, matchWrap())

        deviceListView = TextView(this).apply {
            text = "Пока пусто — запустите сканирование"
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFF5F5F5.toInt())
            setOnClickListener { showDevicePicker() }
        }
        content.addView(deviceListView, matchWrap())

        connectButton = Button(this).apply {
            text = "Подключить выбранные часы"
            isEnabled = false
            setOnClickListener { connectSelected() }
        }
        content.addView(connectButton, matchWrap())

        fetchKeyButton = Button(this).apply {
            text = "Получить auth key из аккаунта Zepp"
            setOnClickListener { fetchAuthKeyFromCloud() }
        }
        content.addView(fetchKeyButton, matchWrap())

        authKeyButton = Button(this).apply {
            text = "Ввести Huami auth key вручную"
            setOnClickListener { showAuthKeyDialog() }
        }
        content.addView(authKeyButton, matchWrap())

        keyStatusView = TextView(this).apply {
            textSize = 13f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        content.addView(keyStatusView, matchWrap())

        loadTracksButton = Button(this).apply {
            text = "Загрузить треки с часов"
            isEnabled = false
            setOnClickListener { loadTracksFromWatch() }
        }
        content.addView(loadTracksButton, matchWrap())

        disconnectButton = Button(this).apply {
            text = "Отключить"
            isEnabled = false
            setOnClickListener { disconnectGatt() }
        }
        content.addView(disconnectButton, matchWrap())

        content.addView(TextView(this).apply {
            text = "Сервисы GATT"
            textSize = 16f
            setPadding(0, dp(20), 0, dp(6))
        }, matchWrap())

        servicesView = TextView(this).apply {
            text = "—"
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFF0F4F8.toInt())
        }
        content.addView(servicesView, matchWrap())

        content.addView(TextView(this).apply {
            text = "Примечание: для полной синхронизации тренировок T-Rex 2 обычно нужен auth key " +
                "(получается после первичной привязки через Zepp / инструмент huami-token). " +
                "Протокол обмена данными — проприетарный (реализован в Gadgetbridge). " +
                "Эта страница даёт подключение, discovery и каркас загрузки треков."
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(16), 0, 0)
        }, matchWrap())

        content.addView(Button(this).apply {
            text = "← Назад"
            setOnClickListener { finish() }
        }, matchWrap())

        sessionStore = SessionStore(this)
        repository = ZeppCloudRepositoryImpl(sessionStore)
        authKeyHex = sessionStore.getAuthKey()
        refreshKeyStatus()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        if (bluetoothAdapter == null) {
            setStatus("Bluetooth недоступен на этом устройстве", error = true)
        }
    }

    @Deprecated("Deprecated in Android API; retained for compatibility with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_GPX) return

        val content = pendingGpxContent
        val name = pendingGpxName ?: "trex_track.gpx"
        pendingGpxContent = null
        pendingGpxName = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || content == null) {
            setStatus("Сохранение GPX отменено")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(content)
            } ?: throw java.io.IOException("Не удалось открыть файл для записи")
            setStatus("GPX сохранён: $name", ok = true)
            Toast.makeText(this, "GPX сохранён", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "GPX export failed", e)
            setStatus("Ошибка сохранения GPX: ${e.message}", error = true)
            Toast.makeText(this, "Не удалось сохранить GPX: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveGpxToUserStorage(content: String, fileName: String) {
        pendingGpxContent = content
        pendingGpxName = fileName
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        try {
            startActivityForResult(intent, REQ_SAVE_GPX)
        } catch (e: Exception) {
            pendingGpxContent = null
            pendingGpxName = null
            Log.e(TAG, "Unable to open document picker", e)
            setStatus("Не удалось открыть выбор файла: ${e.message}", error = true)
        }
    }

    override fun onDestroy() {
        pendingGpxContent = null
        pendingGpxName = null
        stopScan()
        disconnectGatt()
        super.onDestroy()
    }

    private fun setStatus(msg: String, error: Boolean = false, ok: Boolean = false) {
        statusView.text = "Статус: $msg"
        when {
            ok -> {
                statusView.setBackgroundColor(0xFFE8F5E9.toInt())
                statusView.setTextColor(0xFF1B5E20.toInt())
            }
            error -> {
                statusView.setBackgroundColor(0xFFFFEBEE.toInt())
                statusView.setTextColor(0xFFB71C1C.toInt())
            }
            else -> {
                statusView.setBackgroundColor(0xFFFFF0D9.toInt())
                statusView.setTextColor(0xFF8A3B12.toInt())
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(perms, REQ_BLE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_BLE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                setStatus("Нет разрешений Bluetooth / Location", error = true)
                Toast.makeText(this, "Нужны разрешения для BLE", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Включите Bluetooth", error = true)
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) { }
            return
        }
        if (scanning) return

        foundDevices.clear()
        updateDeviceListUi()
        setStatus("Сканирование…")
        scanning = true
        scanButton.isEnabled = false

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            setStatus("BLE scanner недоступен", error = true)
            scanning = false
            scanButton.isEnabled = true
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Prefer Amazfit / Huami advertising if possible; also accept any named device.
        val filters = emptyList<ScanFilter>()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan", e)
            setStatus("Ошибка сканирования: ${e.message}", error = true)
            scanning = false
            scanButton.isEnabled = true
            return
        }

        handler.postDelayed({ stopScan() }, scanTimeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanButton.isEnabled = true
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        setStatus(
            if (foundDevices.isEmpty()) "Сканирование завершено, устройств не найдено"
            else "Найдено устройств: ${foundDevices.size}. Выберите и подключите."
        )
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName
            val addr = device.address ?: return
            // Prefer Amazfit / T-Rex / Huami names, but keep all with a name.
            val interesting = name != null && (
                name.contains("T-Rex", ignoreCase = true) ||
                    name.contains("Amazfit", ignoreCase = true) ||
                    name.contains("Zepp", ignoreCase = true) ||
                    name.contains("Huami", ignoreCase = true)
                )
            if (name.isNullOrBlank() && !interesting) return
            if (!foundDevices.containsKey(addr)) {
                foundDevices[addr] = device
                runOnUiThread { updateDeviceListUi() }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                setStatus("Сканирование failed: $errorCode", error = true)
                scanning = false
                scanButton.isEnabled = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDeviceListUi() {
        if (foundDevices.isEmpty()) {
            deviceListView.text = "Пока пусто — запустите сканирование"
            connectButton.isEnabled = false
            return
        }
        val sb = StringBuilder()
        foundDevices.values.forEachIndexed { i, d ->
            val n = d.name ?: "?"
            val mark = if (selectedDevice?.address == d.address) " ▶ " else "   "
            sb.append("$mark${i + 1}. $n\n    ${d.address}\n")
        }
        sb.append("\n(нажмите на список, чтобы выбрать устройство)")
        deviceListView.text = sb.toString()
        connectButton.isEnabled = selectedDevice != null
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "Сначала выполните сканирование", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = foundDevices.values.map { d ->
            "${d.name ?: "?"} (${d.address})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите часы")
            .setItems(labels) { _, which ->
                selectedDevice = foundDevices.values.elementAt(which)
                updateDeviceListUi()
                setStatus("Выбрано: ${selectedDevice?.name ?: selectedDevice?.address}")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectSelected() {
        val device = selectedDevice
        if (device == null) {
            Toast.makeText(this, "Выберите устройство", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        stopScan()
        connectButton.isEnabled = false

        // Сразу подтянуть auth key (по MAC выбранных часов), затем GATT-подключение
        setStatus("Auth key + подключение к ${device.name ?: device.address}…")
        scope.launch {
            try {
                ensureAuthKeyForDevice(device.address)
            } catch (t: Throwable) {
                Log.w(TAG, "ensureAuthKeyForDevice", t)
                // Не блокируем BLE-подключение, если облако недоступно
            }
            runOnUiThread {
                doGattConnect(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun doGattConnect(device: BluetoothDevice) {
        setStatus("Подключение к ${device.name ?: device.address}…")
        try {
            gatt?.close()
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt", e)
            setStatus("Ошибка connectGatt: ${e.message}", error = true)
            connectButton.isEnabled = true
        }
    }

    /**
     * Получить и сохранить auth key для [mac].
     * Если уже есть ключ для этого MAC — не ходим в облако.
     * При нескольких устройствах в аккаунте предпочитает совпадение MAC.
     */
    private suspend fun ensureAuthKeyForDevice(mac: String) {
        val norm = mac.replace(":", "").lowercase()
        val existing = sessionStore.getAuthKey()
        val existingMac = sessionStore.getAuthKeyMac()?.replace(":", "")?.lowercase()
        if (!existing.isNullOrBlank() && existingMac != null && existingMac == norm) {
            authKeyHex = existing
            Log.i(TAG, "auth key already present for $mac")
            runOnUiThread { refreshKeyStatus() }
            return
        }
        if (!sessionStore.isLoggedIn()) {
            Log.i(TAG, "skip cloud auth key: not logged in")
            runOnUiThread {
                if (authKeyHex.isNullOrBlank() && sessionStore.getAuthKey().isNullOrBlank()) {
                    Toast.makeText(
                        this@WatchDirectActivity,
                        "Нет auth key: войдите в Zepp на экране «Облако» или введите ключ вручную",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }
        runOnUiThread { setStatus("Загрузка auth key из Zepp…") }
        val devices = withContext(Dispatchers.IO) {
            repository.listBoundDevices()
        }
        val withKey = devices.filter { it.authKeyHex.isNotBlank() }
        if (withKey.isEmpty()) {
            Log.w(TAG, "cloud devices without auth_key: ${devices.size}")
            return
        }
        val match = withKey.firstOrNull {
            it.macAddress.replace(":", "").lowercase() == norm
        } ?: withKey.firstOrNull { it.active } ?: withKey.first()
        runOnUiThread { applyAuthKey(match) }
        Log.i(TAG, "auth key auto-fetched mac=${match.macAddress} for selected=$mac")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connected = true
                        authState = HuamiBleProtocol.AuthState.IDLE
                        setStatus("Подключено. Обнаружение сервисов…", ok = true)
                        disconnectButton.isEnabled = true
                        loadTracksButton.isEnabled = true
                        try {
                            g.discoverServices()
                        } catch (e: Exception) {
                            setStatus("discoverServices: ${e.message}", error = true)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected = false
                        loadInProgress = false
                        authState = HuamiBleProtocol.AuthState.IDLE
                        setStatus("Отключено", error = true)
                        disconnectButton.isEnabled = false
                        loadTracksButton.isEnabled = false
                        servicesView.text = "—"
                        connectButton.isEnabled = selectedDevice != null
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setStatus("Ошибка discovery: $status", error = true)
                    return@runOnUiThread
                }
                discoveredServices = g.services ?: emptyList()
                val sb = StringBuilder()
                for (s in discoveredServices) {
                    val short = s.uuid.toString().take(8)
                    val hint = when {
                        s.uuid == HUAMI_SERVICE -> " (Huami fee0)"
                        s.uuid == HUAMI_AUTH_SERVICE -> " (Huami fee1)"
                        else -> ""
                    }
                    sb.append("• $short…$hint\n")
                    for (c in s.characteristics) {
                        val props = propFlags(c.properties)
                        val name = when (c.uuid) {
                            HuamiBleProtocol.CHAR_AUTH -> " AUTH"
                            HuamiBleProtocol.CHAR_ACTIVITY_CONTROL -> " ACT_CTL"
                            HuamiBleProtocol.CHAR_ACTIVITY_DATA -> " ACT_DATA"
                            HuamiBleProtocol.CHAR_WORKOUT -> " WORKOUT"
                            HuamiBleProtocol.CHAR_CHUNKED_2021_WRITE -> " CH2021_W"
                            HuamiBleProtocol.CHAR_CHUNKED_2021_READ -> " CH2021_R"
                            HuamiBleProtocol.CHAR_ZEPP_FT_SEND -> " ZEPP_FT_S"
                            HuamiBleProtocol.CHAR_ZEPP_FT_RECV -> " ZEPP_FT_R"
                            else -> ""
                        }
                        sb.append("    ${c.uuid.toString().take(8)}… [$props]$name\n")
                    }
                }
                servicesView.text = if (sb.isEmpty()) "Сервисы не найдены" else sb.toString()
                val hasHuami = discoveredServices.any {
                    it.uuid == HUAMI_SERVICE || it.uuid == HUAMI_AUTH_SERVICE
                }
                val zepp = HuamiBleProtocol.hasZeppOsTransfer(g)
                val classic = HuamiBleProtocol.hasClassicAuth(g)
                setStatus(
                    buildString {
                        append(if (hasHuami) "Huami OK" else "сервисов: ${discoveredServices.size}")
                        if (classic) append(" · classic AUTH")
                        if (zepp) append(" · Zepp OS transfer")
                    },
                    ok = true
                )
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                chunkedEncoder?.setMtu(mtu)
                Log.i(TAG, "MTU changed to $mtu")
            } else {
                Log.w(TAG, "onMtuChanged status=$status mtu=$mtu")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite ${descriptor.characteristic?.uuid} status=$status")
            if (pendingDescriptorWrites > 0) {
                pendingDescriptorWrites--
            }
            // Auth is started by enableNextNotification chain when all CCCDs are done
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            val uuid = characteristic.uuid
            Log.d(TAG, "notify ${uuid.toString().take(8)}… ${HuamiBleProtocol.hex(value)}")

            when (uuid) {
                HuamiBleProtocol.CHAR_AUTH -> handleAuthNotification(g, value)
                HuamiBleProtocol.CHAR_ACTIVITY_CONTROL -> handleActivityControlEndpoint(g, value)
                HuamiBleProtocol.CHAR_ACTIVITY_DATA -> handleActivityData(value)
                HuamiBleProtocol.CHAR_CHUNKED_2021_READ -> handleChunked2021Notify(g, value)
                HuamiBleProtocol.CHAR_ZEPP_FT_RECV -> {
                    activityBytesReceived += value.size
                    activityBuffer.add(value.copyOf())
                    runOnUiThread {
                        setStatus(
                            "Zepp FT: ${activityBytesReceived} байт (${activityBuffer.size} пак.)"
                        )
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite ${characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    setStatus("Write failed ${characteristic.uuid.toString().take(8)}…: $status", error = true)
                }
            }
            // Drain GATT write queue (chunked frames must be sequential)
            gattWriteInFlight = false
            pumpGattWriteQueue(g)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead ${characteristic.uuid} status=$status")
        }
    }

    private fun propFlags(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("R")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("W")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("Wn")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("N")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("I")
        return parts.joinToString("")
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) { }
        gatt = null
        connected = false
        loadInProgress = false
        authState = HuamiBleProtocol.AuthState.IDLE
        activityBuffer.clear()
        activityBytesReceived = 0
        loadTracksButton.isEnabled = false
        disconnectButton.isEnabled = false
        connectButton.isEnabled = selectedDevice != null
        servicesView.text = "—"
        setStatus("Отключено")
    }

    private fun showAuthKeyDialog() {
        val input = EditText(this).apply {
            hint = "hex auth key (32 или 16 байт)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(authKeyHex.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Huami / Amazfit auth key")
            .setMessage(
                "Ключ нужен для аутентификации на T-Rex 2 (как в Gadgetbridge). " +
                    "Можно получить через huami-token или после привязки в Zepp. " +
                    "Формат: hex-строка без 0x."
            )
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text?.toString()?.trim()?.replace(" ", "")?.lowercase().orEmpty().removePrefix("0x")
                if (v.isBlank()) {
                    authKeyHex = null
                    sessionStore.clearAuthKey()
                    setStatus("Auth key очищен")
                } else {
                    authKeyHex = v
                    sessionStore.saveAuthKey(v, null)
                    setStatus("Auth key сохранён (${v.length} символов)")
                }
                refreshKeyStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Загрузка треков с часов:
     * 1) проверка GATT + Huami-сервисов + auth key;
     * 2) enable notifications (AUTH + ACTIVITY_DATA, при наличии — chunked/Zepp FT);
     * 3) classic Huami AES-auth;
     * 4) после auth — запрос activity data (список/сырые пакеты);
     * 5) Zepp OS file-transfer / GPS points — структура готова, полный парсер — следующий этап.
     */
    @SuppressLint("MissingPermission")
    private fun loadTracksFromWatch() {
        val g = gatt
        if (!connected || g == null) {
            Toast.makeText(this, "Сначала подключите часы", Toast.LENGTH_SHORT).show()
            return
        }
        if (loadInProgress) {
            Toast.makeText(this, "Загрузка уже идёт…", Toast.LENGTH_SHORT).show()
            return
        }

        val hasHuami = discoveredServices.any {
            it.uuid == HUAMI_SERVICE || it.uuid == HUAMI_AUTH_SERVICE
        }
        val keyHex = authKeyHex ?: sessionStore.getAuthKey()
        val keyBytes = HuamiBleProtocol.parseAuthKeyHex(keyHex)
        val classicAuth = HuamiBleProtocol.hasClassicAuth(g)
        val activityChars = HuamiBleProtocol.hasActivityChars(g)
        val zeppOs = HuamiBleProtocol.hasZeppOsTransfer(g)

        if (!hasHuami) {
            setStatus("Нет Huami-сервисов (fee0/fee1) на соединении", error = true)
            Toast.makeText(this, "Huami UUID не найдены", Toast.LENGTH_LONG).show()
            return
        }
        if (keyBytes == null) {
            setStatus("Нужен auth key (16 байт hex)", error = true)
            AlertDialog.Builder(this)
                .setTitle("Auth key")
                .setMessage(
                    "Для T-Rex 2 / Amazfit нужен Huami auth key.\n\n" +
                        "Получите его кнопкой «Получить auth key из аккаунта Zepp» " +
                        "или введите вручную (32 hex-символа)."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        authKeyBytes = keyBytes
        authState = HuamiBleProtocol.AuthState.IDLE
        activityBytesReceived = 0
        activityBuffer.clear()
        loadInProgress = true
        finishPosted = false
        zeppAuthInProgress = false
        sessionAesKey = null
        loadTracksButton.isEnabled = false

        setStatus(
            buildString {
                append("Старт загрузки… ")
                append(if (classicAuth) "classic AUTH " else "")
                append(if (activityChars) "activity " else "")
                append(if (zeppOs) "ZeppOS-FT " else "")
            }
        )
        Log.i(
            TAG,
            "loadTracksFromWatch device=${selectedDevice?.address} classic=$classicAuth " +
                "activity=$activityChars zepp=$zeppOs keyLen=${keyBytes.size}"
        )

        // Enable notifications first (descriptor writes are serialized by the stack)
        pendingDescriptorWrites = 0
        val toEnable = mutableListOf<BluetoothGattCharacteristic>()
        HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_AUTH)?.let { toEnable.add(it) }
        // Classic activity control meta responses arrive as notifications on 0x0004
        HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_ACTIVITY_CONTROL)?.let { toEnable.add(it) }
        HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_ACTIVITY_DATA)?.let { toEnable.add(it) }
        HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_CHUNKED_2021_READ)?.let { toEnable.add(it) }
        HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_ZEPP_FT_RECV)?.let { toEnable.add(it) }

        if (toEnable.isEmpty()) {
            loadInProgress = false
            loadTracksButton.isEnabled = true
            setStatus("Нет характеристик для notify (AUTH/ACT_DATA)", error = true)
            return
        }

        // Enable one-by-one; onDescriptorWrite chains the rest / starts auth
        enableNextNotification(g, toEnable, 0)
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(
        g: BluetoothGatt,
        list: List<BluetoothGattCharacteristic>,
        index: Int
    ) {
        if (index >= list.size) {
            handler.post { beginAuth(g) }
            return
        }
        val c = list[index]
        pendingDescriptorWrites = 1
        val ok = HuamiBleProtocol.enableNotifications(g, c)
        if (!ok) {
            Log.w(TAG, "enableNotifications failed for ${c.uuid}")
            handler.post { enableNextNotification(g, list, index + 1) }
            return
        }
        // Chain sequentially; onDescriptorWrite is only informational
        handler.postDelayed({
            pendingDescriptorWrites = 0
            enableNextNotification(g, list, index + 1)
        }, 350)
    }

    /** Choose classic AES or Zepp OS ECDH based on discovered characteristics. */
    @SuppressLint("MissingPermission")
    private fun beginAuth(g: BluetoothGatt) {
        val classic = HuamiBleProtocol.hasClassicAuth(g)
        val zepp = HuamiBleProtocol.hasZeppOsTransfer(g)
        Log.i(TAG, "beginAuth classic=$classic zepp=$zepp")
        // Request larger MTU before chunked writes (default 23 is tight)
        try {
            g.requestMtu(512)
        } catch (e: Exception) {
            Log.w(TAG, "requestMtu failed", e)
        }
        // Give the stack a moment for MTU / CCCD to settle
        handler.postDelayed({
            when {
                zepp && !classic -> startZeppOsAuth(g)
                classic -> startClassicAuth(g)
                zepp -> startZeppOsAuth(g)
                else -> {
                    setStatus("Нет AUTH / chunked 2021 — auth невозможен", error = true)
                    finishLoadWithSummary(false, "нет auth characteristics")
                }
            }
        }, 400)
    }

    /**
     * Zepp OS / Huami 2021 ECDH auth (InitOperation2021).
     * Endpoint 0x0082 over chunked 2021 WRITE/READ characteristics.
     */
    @SuppressLint("MissingPermission")
    private fun startZeppOsAuth(g: BluetoothGatt) {
        val writeChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_CHUNKED_2021_WRITE)
        val readChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_CHUNKED_2021_READ)
        val key = authKeyBytes
        if (writeChar == null || readChar == null || key == null) {
            finishLoadWithSummary(false, "нет CH2021 write/read или auth key")
            return
        }

        Log.i(
            TAG,
            "CH2021 write props=0x${"%02x".format(writeChar.properties)} " +
                "read props=0x${"%02x".format(readChar.properties)}"
        )

        val currentMtu = negotiatedMtu
        val enc = Huami2021ChunkedEncoder(currentMtu)
        val dec = Huami2021ChunkedDecoder({ type, payload ->
            handleZeppAuthPayload(g, type, payload)
        }, force2021Protocol = true)
        chunkedEncoder = enc
        chunkedDecoder = dec
        zeppAuthInProgress = true
        finishPosted = false

        // Generate ECDH key pair (curve B-163)
        val priv = ByteArray(24)
        java.security.SecureRandom().nextBytes(priv)
        ecdhPrivate = priv
        val pub = ECDH_B163.ecdh_generate_public(priv)
        ecdhPublic = pub
        if (pub == null || pub.size < 48) {
            finishLoadWithSummary(false, "ECDH public key generation failed")
            return
        }

        setStatus("Zepp OS auth: отправка ECDH public key…")
        // Command: 04 02 00 02 + 48-byte public key
        val sendPub = ByteArray(52)
        sendPub[0] = 0x04
        sendPub[1] = 0x02
        sendPub[2] = 0x00
        sendPub[3] = 0x02
        System.arraycopy(pub, 0, sendPub, 4, 48)

        gattWriteQueue.clear()
        gattWriteInFlight = false
        enc.write(
            chunkWriter = { chunk ->
                Log.d(TAG, "CH2021 queue chunk len=${chunk.size} ${HuamiBleProtocol.hex(chunk.copyOf(minOf(12, chunk.size)))}")
                queueGattWrite(g, writeChar, chunk)
            },
            type = CHUNKED2021_ENDPOINT_AUTH,
            data = sendPub,
            extendedFlags = true,
            encrypt = false
        )

        handler.postDelayed({
            if (zeppAuthInProgress && authState != HuamiBleProtocol.AuthState.AUTHENTICATED) {
                setStatus("Zepp OS auth: таймаут", error = true)
                zeppAuthInProgress = false
                finishLoadWithSummary(false, "Zepp ECDH auth timeout")
            }
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    private fun handleChunked2021Notify(g: BluetoothGatt, value: ByteArray) {
        val dec = chunkedDecoder ?: return
        val needsAck = dec.decode(value)
        if (needsAck) {
            sendChunkedAck(g, dec.lastHandle, dec.lastCount)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendChunkedAck(g: BluetoothGatt, handle: Byte, count: Byte) {
        val readChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_CHUNKED_2021_READ)
            ?: return
        // ACK is written TO the READ characteristic (as in Gadgetbridge)
        queueGattWrite(g, readChar, byteArrayOf(0x04, 0x00, handle, 0x01, count))
        Log.d(TAG, "chunked ACK queued handle=$handle count=$count")
    }

    /** Serialize GATT writes — Android allows only one outstanding write per connection. */
    @SuppressLint("MissingPermission")
    private fun queueGattWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        gattWriteQueue.addLast(characteristic to value)
        pumpGattWriteQueue(g)
    }

    @SuppressLint("MissingPermission")
    private fun pumpGattWriteQueue(g: BluetoothGatt) {
        if (gattWriteInFlight) return
        val next = gattWriteQueue.removeFirstOrNull() ?: return
        val (ch, value) = next
        // Prefer write-without-response when supported (typical for Huami chunked 0x0016)
        val props = ch.properties
        ch.writeType = when {
            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = value
        gattWriteInFlight = true
        val ok = g.writeCharacteristic(ch)
        Log.d(
            TAG,
            "GATT write ${ch.uuid.toString().take(8)}… len=${value.size} type=${ch.writeType} " +
                "props=0x${"%02x".format(props)} ok=$ok queueLeft=${gattWriteQueue.size}"
        )
        if (!ok) {
            gattWriteInFlight = false
            return
        }
        // WRITE_NO_RESPONSE often does not invoke onCharacteristicWrite — advance queue ourselves
        if (ch.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            handler.postDelayed({
                gattWriteInFlight = false
                pumpGattWriteQueue(g)
            }, 40)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleZeppAuthPayload(g: BluetoothGatt, type: Short, payload: ByteArray) {
        when (type) {
            CHUNKED2021_ENDPOINT_AUTH -> handleAuthEndpoint(g, payload)
            CHUNKED2021_ENDPOINT_HTTP -> handleHttpEndpoint(g, payload)
            CHUNKED2021_ENDPOINT_ACTIVITY -> handleActivityControlEndpoint(g, payload)
            CHUNKED2021_ENDPOINT_SERVICES -> handleServicesEndpoint(g, payload)
            CHUNKED2021_ENDPOINT_CONNECTION -> handleConnectionEndpoint(g, payload)
            else -> {
                Log.d(TAG, "endpoint 0x${"%04x".format(type.toInt())} len=${payload.size} " +
                    HuamiBleProtocol.hex(payload.copyOf(minOf(24, payload.size))))
                activityBytesReceived += payload.size
                activityBuffer.add(payload.copyOf())
            }
        }
    }

    /** Endpoint 0x0015 — ping/pong and MTU announce. */
    @SuppressLint("MissingPermission")
    private fun handleConnectionEndpoint(g: BluetoothGatt, payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0]) {
            0x03.toByte() -> {
                Log.i(TAG, "connection PING → PONG")
                writeChunked(g, CHUNKED2021_ENDPOINT_CONNECTION, byteArrayOf(0x04), encrypt = true)
            }
            0x02.toByte() -> {
                if (payload.size >= 3) {
                    val mtu = ((payload[1].toInt() and 0xff) or ((payload[2].toInt() and 0xff) shl 8)) + 3
                    negotiatedMtu = mtu
                    chunkedEncoder?.setMtu(mtu)
                    Log.i(TAG, "connection MTU announce: $mtu")
                }
            }
            else -> Log.d(TAG, "connection cmd=0x${"%02x".format(payload[0])}")
        }
    }

    /** Endpoint 0x0000 — list of supported Zepp OS services. */
    @SuppressLint("MissingPermission")
    private fun handleServicesEndpoint(g: BluetoothGatt, payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload[0] != 0x04.toByte()) {
            Log.w(TAG, "services unexpected cmd=0x${"%02x".format(payload[0])}")
            return
        }
        if (payload.size < 3) return
        val num = (payload[1].toInt() and 0xff) or ((payload[2].toInt() and 0xff) shl 8)
        Log.i(TAG, "Zepp OS services count=$num")
        supportedEndpoints.clear()
        activityEndpointEncrypted = true
        var i = 3
        repeat(num) {
            if (i + 3 > payload.size) return@repeat
            val ep = ((payload[i].toInt() and 0xff) or ((payload[i + 1].toInt() and 0xff) shl 8)).toShort()
            val encFlag = payload[i + 2].toInt() and 0xff
            i += 3
            supportedEndpoints.add(ep)
            Log.d(TAG, "  service 0x${"%04x".format(ep.toInt())} enc=$encFlag")
            if (ep == CHUNKED2021_ENDPOINT_ACTIVITY) {
                activityEndpointEncrypted = encFlag != 0
            }
        }
        val hasActivity = supportedEndpoints.contains(CHUNKED2021_ENDPOINT_ACTIVITY)
        useChunkedActivityFetch = hasActivity
        Log.i(TAG, "has activity 0x004b=$hasActivity encrypted=$activityEndpointEncrypted useChunked=$useChunkedActivityFetch")
        runOnUiThread {
            setStatus(
                if (hasActivity) "Сервисы: $num, sports via 0x004b…"
                else "Сервисы: $num, sports via classic ACT_CTL…"
            )
        }
        requestSportsSummaries(g)
    }

    @SuppressLint("MissingPermission")
    private fun handleAuthEndpoint(g: BluetoothGatt, payload: ByteArray) {
        if (payload.size < 3) return
        val response = payload[0]
        val step = payload[1]
        val status = payload[2]
        Log.i(TAG, "auth payload step=0x${"%02x".format(step)} status=0x${"%02x".format(status)} len=${payload.size}")

        // RESPONSE=0x10, SUCCESS=0x01
        if (response == 0x10.toByte() && step == 0x04.toByte() && status == 0x01.toByte() && payload.size >= 67) {
            val remoteRandom = payload.copyOfRange(3, 19)
            val remotePublic = payload.copyOfRange(19, 67)
            val priv = ecdhPrivate
            val secretKey = authKeyBytes
            if (priv == null || secretKey == null) {
                finishLoadWithSummary(false, "ECDH state lost")
                return
            }
            val sharedEc = ECDH_B163.ecdh_generate_shared(priv, remotePublic)
            if (sharedEc == null || sharedEc.size < 24) {
                finishLoadWithSummary(false, "ECDH shared secret failed")
                return
            }
            val encryptedSequenceNumber =
                (sharedEc[0].toInt() and 0xff) or
                    ((sharedEc[1].toInt() and 0xff) shl 8) or
                    ((sharedEc[2].toInt() and 0xff) shl 16) or
                    ((sharedEc[3].toInt() and 0xff) shl 24)
            val session = ByteArray(16)
            for (i in 0 until 16) {
                session[i] = (sharedEc[i + 8].toInt() xor secretKey[i].toInt()).toByte()
            }
            sessionAesKey = session
            chunkedEncoder?.setEncryptionParameters(encryptedSequenceNumber, session)
            chunkedDecoder?.setEncryptionParameters(session)
            Log.i(TAG, "session key established seq=$encryptedSequenceNumber")

            try {
                val enc1 = Huami2021Crypto.encryptAesEcb(remoteRandom, secretKey)
                val enc2 = Huami2021Crypto.encryptAesEcb(remoteRandom, session)
                val command = ByteArray(33)
                command[0] = 0x05
                System.arraycopy(enc1, 0, command, 1, 16)
                System.arraycopy(enc2, 0, command, 17, 16)
                runOnUiThread { setStatus("Zepp OS auth: double-encrypted random…") }
                writeChunked(g, CHUNKED2021_ENDPOINT_AUTH, command, encrypt = false)
            } catch (e: Exception) {
                Log.e(TAG, "AES encrypt random", e)
                finishLoadWithSummary(false, "AES encrypt failed: ${e.message}")
            }
        } else if (response == 0x10.toByte() && step == 0x05.toByte() && status == 0x01.toByte()) {
            authState = HuamiBleProtocol.AuthState.AUTHENTICATED
            zeppAuthInProgress = false
            runOnUiThread { setStatus("Zepp OS auth: успех! Запрос списка сервисов…", ok = true) }
            Log.i(TAG, "Zepp OS ECDH auth SUCCESS")
            handler.post { requestZeppServices(g) }
        } else if (response == 0x10.toByte() && step == 0x05.toByte() && status == 0x25.toByte()) {
            zeppAuthInProgress = false
            authState = HuamiBleProtocol.AuthState.FAILED
            runOnUiThread { setStatus("Zepp OS auth: отказ (проверьте auth key)", error = true) }
            finishLoadWithSummary(false, "Zepp AUTH_FAIL 0x25 — неверный ключ")
        } else {
            Log.w(TAG, "Unhandled auth payload: ${HuamiBleProtocol.hex(payload)}")
        }
    }

    /**
     * Endpoint 0x0001 — watch asks phone to HTTP GET Huami APIs.
     * Reply "no internet" so the watch does not wait.
     */
    @SuppressLint("MissingPermission")
    private fun handleHttpEndpoint(g: BluetoothGatt, payload: ByteArray) {
        if (payload.isEmpty()) return
        Log.i(TAG, "HTTP endpoint cmd=0x${"%02x".format(payload[0])} len=${payload.size}")
        if (payload[0] == 0x01.toByte() && payload.size >= 2) {
            // CMD_SIMPLE_REQUEST: requestId at [1]
            val requestId = payload[1].toInt() and 0xff
            // CMD_SIMPLE_RESPONSE, requestId, RESPONSE_NO_INTERNET=0x02, 4 zero bytes
            val reply = byteArrayOf(0x02, requestId.toByte(), 0x02, 0, 0, 0, 0)
            Log.i(TAG, "HTTP reply no-internet id=$requestId")
            writeChunked(g, CHUNKED2021_ENDPOINT_HTTP, reply, encrypt = true)
        } else if (payload[0] == 0x03.toByte() && payload.size >= 2) {
            val requestId = payload[1].toInt() and 0xff
            // raw download — also refuse
            val reply = byteArrayOf(0x02, requestId.toByte(), 0x02, 0, 0, 0, 0)
            writeChunked(g, CHUNKED2021_ENDPOINT_HTTP, reply, encrypt = true)
        }
    }

    /**
     * Endpoint 0x004b — activity/sports fetch control metadata (same opcodes as classic).
     */
    @SuppressLint("MissingPermission")
    private fun handleActivityControlEndpoint(g: BluetoothGatt, payload: ByteArray) {
        Log.i(TAG, "activity control meta: ${HuamiBleProtocol.hex(payload)}")
        if (payload.size < 3) return
        if (payload[0] != 0x10.toByte()) {
            Log.w(TAG, "activity meta not RESPONSE")
            return
        }
        when (payload[1]) {
            HuamiBleProtocol.CMD_ACTIVITY_START_DATE -> {
                if (payload[2] != HuamiBleProtocol.AUTH_SUCCESS) {
                    Log.w(TAG, "start-date unsuccessful")
                    finishLoadWithSummary(true, "sports start-date fail status=${payload[2]}")
                    return
                }
                val expected = if (payload.size >= 7) {
                    (payload[3].toInt() and 0xff) or
                        ((payload[4].toInt() and 0xff) shl 8) or
                        ((payload[5].toInt() and 0xff) shl 16) or
                        ((payload[6].toInt() and 0xff) shl 24)
                } else 0
                // date starts at offset 7
                if (payload.size >= 15) {
                    // The watch returns its own timestamp in bytes 7..14:
                    // uint16 year, month, day, hour, minute, second, timezone.
                    // Keep these bytes: rebuilding them from a Unix timestamp in the
                    // phone timezone shifts the wall clock (T-Rex 2 reports UTC here).
                    sportsSinceTimeBytes = payload.copyOfRange(7, 15)
                    sportsSinceCal = calendarFromHuamiBytes(payload, 7)
                    Log.i(
                        TAG,
                        "sports since from watch: ${sportsSinceCal?.time} " +
                            "raw=${HuamiBleProtocol.hex(sportsSinceTimeBytes!!)}"
                    )
                }
                Log.i(TAG, "sports expectedDataLength=$expected type=0x${"%02x".format(sportsFetchType)}")
                expectedSportsBytes = expected
                if (expected == 0) {
                    writeActivityControl(g, byteArrayOf(HuamiBleProtocol.CMD_ACK_ACTIVITY, 0x09))
                    if (sportsFetchType == 0x05.toByte()) {
                        finishLoadWithSummary(true, "Нет sports summary с указанной даты (0 байт)")
                    } else {
                        finishLoadWithSummary(true, "Нет GPS details (0 байт). Summary: ${lastParsedSummary?.shortDescription()}")
                    }
                } else {
                    runOnUiThread {
                        setStatus(
                            if (sportsFetchType == 0x05.toByte()) "Sports summary: ждут $expected байт…"
                            else "GPS details: ждут $expected байт…"
                        )
                    }
                    sportsFetchActive = true
                    writeActivityControl(g, byteArrayOf(HuamiBleProtocol.CMD_FETCH_DATA))
                }
            }
            HuamiBleProtocol.CMD_FETCH_DATA -> {
                Log.i(TAG, "FETCH_DATA meta status=${payload[2]}")
                if (payload[2] != HuamiBleProtocol.AUTH_SUCCESS) {
                    finishLoadWithSummary(true, "FETCH_DATA fail status=${payload[2]}")
                }
                // data packets arrive on ACTIVITY_DATA characteristic
            }
            HuamiBleProtocol.CMD_ACK_ACTIVITY -> {
                Log.i(TAG, "ACK meta")
                finishSportsFetch("ACK")
            }
            else -> Log.w(TAG, "unknown activity meta cmd=0x${"%02x".format(payload[1])}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChunked(g: BluetoothGatt, endpoint: Short, data: ByteArray, encrypt: Boolean) {
        val writeChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_CHUNKED_2021_WRITE)
            ?: return
        chunkedEncoder?.write(
            chunkWriter = { chunk -> queueGattWrite(g, writeChar, chunk) },
            type = endpoint,
            data = data,
            extendedFlags = true,
            encrypt = encrypt
        )
    }

    /** After auth: request supported service list (endpoint 0x0000, unencrypted). */
    @SuppressLint("MissingPermission")
    private fun requestZeppServices(g: BluetoothGatt) {
        Log.i(TAG, "request Zepp OS services list (0x0000 CMD_GET_LIST)")
        runOnUiThread { setStatus("Запрос списка Zepp OS сервисов…") }
        // Services service is not encrypted in Gadgetbridge
        writeChunked(g, CHUNKED2021_ENDPOINT_SERVICES, byteArrayOf(0x03), encrypt = false)
        // Fallback: if no services reply, still try sports after delay
        handler.postDelayed({
            if (loadInProgress && !finishPosted && supportedEndpoints.isEmpty()) {
                Log.w(TAG, "no services list reply, trying sports anyway")
                requestSportsSummaries(g)
            }
        }, 5_000)
    }

    /**
     * Request sports summaries (type 0x05) since 30 days ago via Zepp OS endpoint 0x004b.
     */
    @SuppressLint("MissingPermission")
    private fun requestSportsSummaries(g: BluetoothGatt) {
        activityBuffer.clear()
        activityBytesReceived = 0
        sportsDataBuffer.reset()
        sportsPacketCounter = (-1).toByte()
        sportsFetchActive = false
        expectedSportsBytes = -1
        sportsFetchType = 0x05
        lastParsedSummary = null
        lastGpsPoints = emptyList()
        sportsSinceTimeBytes = null
        sportsSinceCal = null

        val since = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val timeBytes = huamiTimeBytes(since)
        // 0x01 = START_DATE, 0x05 = SPORTS_SUMMARIES
        val cmd = ByteArray(2 + timeBytes.size)
        cmd[0] = HuamiBleProtocol.CMD_ACTIVITY_START_DATE
        cmd[1] = 0x05 // SPORTS_SUMMARIES
        System.arraycopy(timeBytes, 0, cmd, 2, timeBytes.size)
        Log.i(
            TAG,
            "request sports summaries cmd=${HuamiBleProtocol.hex(cmd)} " +
                "via=${if (useChunkedActivityFetch) "0x004b" else "classic"} enc=$activityEndpointEncrypted"
        )
        runOnUiThread { setStatus("Запрос sports summaries (30 дней)…") }
        writeActivityControl(g, cmd)

        handler.postDelayed({
            if (loadInProgress && !finishPosted && sportsFetchType == 0x05.toByte()) {
                finishSportsFetch("timeout-summaries")
            }
        }, 20_000)
    }

    /** Write activity/sports control either via 0x004b or classic characteristic. */
    @SuppressLint("MissingPermission")
    private fun writeActivityControl(g: BluetoothGatt, data: ByteArray) {
        if (useChunkedActivityFetch) {
            writeChunked(g, CHUNKED2021_ENDPOINT_ACTIVITY, data, encrypt = activityEndpointEncrypted)
            return
        }
        val control = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_ACTIVITY_CONTROL)
        if (control == null) {
            Log.e(TAG, "no CHAR_ACTIVITY_CONTROL")
            finishLoadWithSummary(true, "нет CHAR_ACTIVITY_CONTROL и нет 0x004b")
            return
        }
        Log.i(TAG, "classic ACT_CTL write props=0x${"%02x".format(control.properties)} len=${data.size}")
        queueGattWrite(g, control, data)
    }

    /** year_lo, year_hi, month, day, hour, minute, seconds(0), tz */
    private fun huamiTimeBytes(cal: java.util.Calendar): ByteArray {
        val year = cal.get(java.util.Calendar.YEAR)
        val tzQuarter = (cal.timeZone.getOffset(cal.timeInMillis) / (1000 * 60 * 15)).toByte()
        return byteArrayOf(
            (year and 0xff).toByte(),
            ((year shr 8) and 0xff).toByte(),
            (cal.get(java.util.Calendar.MONTH) + 1).toByte(),
            cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            cal.get(java.util.Calendar.MINUTE).toByte(),
            0, // seconds truncated
            tzQuarter
        )
    }

    private fun finishSportsFetch(reason: String) {
        sportsFetchActive = false
        val raw = sportsDataBuffer.toByteArray()
        val head = HuamiBleProtocol.hex(raw.copyOf(minOf(32, raw.size)))
        Log.i(TAG, "sports fetch ($reason) type=0x${"%02x".format(sportsFetchType)} buffer=${raw.size} expected=$expectedSportsBytes head=$head")

        gatt?.let { g ->
            if (raw.isNotEmpty()) {
                // Zepp OS ACK: 0x09 confirms receipt but keeps the workout on the watch.
                // A one-byte/0x01 ACK can discard GPS details before the 0x06 request.
                writeActivityControl(g, byteArrayOf(HuamiBleProtocol.CMD_ACK_ACTIVITY, 0x09))
            }
        }

        if (sportsFetchType == 0x05.toByte()) {
            val parsed = if (raw.isNotEmpty()) HuamiWorkoutSummaryParser.parse(raw) else null
            lastParsedSummary = parsed
            val desc = parsed?.shortDescription() ?: "buffer=${raw.size} bytes"
            Log.i(TAG, "parsed summary: $desc")
            // Chain GPS details if we got a summary with a usable time
            val g = gatt
            if (g != null && raw.isNotEmpty() && (parsed?.hasGps == true || sportsSinceCal != null)) {
                runOnUiThread { setStatus("Summary OK. Запрос GPS details…\n$desc") }
                handler.postDelayed({ requestSportsDetails(g) }, 500)
                return
            }
            finishLoadWithSummary(true, "Summary: $desc")
        } else {
            // details
            val workoutStartMs = lastParsedSummary?.startTimestampSec?.times(1000L) ?: 0L
            val points = if (raw.isNotEmpty()) {
                HuamiGpsDetailsParser.parse(raw, workoutStartMs)
            } else emptyList()
            lastGpsPoints = points
            val summaryLine = lastParsedSummary?.shortDescription() ?: "—"
            var gpxFileName: String? = null
            if (points.isNotEmpty()) {
                val stamp = lastParsedSummary?.startTimestampSec
                    ?.let { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(it * 1000L)) }
                    ?: java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                gpxFileName = "trex_track_${stamp}.gpx"
                val gpx = HuamiGpsDetailsParser.toGpx(points, summaryLine)
                Log.i(TAG, "GPX ready for export: $gpxFileName (${points.size} pts)")
                runOnUiThread { saveGpxToUserStorage(gpx, gpxFileName!!) }
            }
            val note = buildString {
                append("Summary: ").append(summaryLine).append('\n')
                append("GPS points: ").append(points.size)
                if (points.isNotEmpty()) {
                    val first = points.first()
                    val last = points.last()
                    append("\nFirst: %.5f, %.5f".format(first.latitude, first.longitude))
                    append("\nLast: %.5f, %.5f".format(last.latitude, last.longitude))
                }
                if (gpxFileName != null) append("\nGPX: ").append(gpxFileName).append(" (выберите папку для сохранения)")
            }
            finishLoadWithSummary(true, note)
        }
    }

    private fun calendarFromHuamiBytes(payload: ByteArray, offset: Int): java.util.Calendar {
        val year = (payload[offset].toInt() and 0xff) or ((payload[offset + 1].toInt() and 0xff) shl 8)
        val month = payload[offset + 2].toInt() and 0xff
        val day = payload[offset + 3].toInt() and 0xff
        val hour = payload[offset + 4].toInt() and 0xff
        val min = payload[offset + 5].toInt() and 0xff
        val sec = if (payload.size > offset + 6) payload[offset + 6].toInt() and 0xff else 0
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month - 1)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, min)
            set(java.util.Calendar.SECOND, sec)
            set(java.util.Calendar.MILLISECOND, 0)
        }
    }

    /** Fetch GPS/track details (type 0x06) for the last summary start time. */
    @SuppressLint("MissingPermission")
    private fun requestSportsDetails(g: BluetoothGatt) {
        activityBuffer.clear()
        activityBytesReceived = 0
        sportsDataBuffer.reset()
        sportsPacketCounter = (-1).toByte()
        sportsFetchActive = false
        expectedSportsBytes = -1
        sportsFetchType = 0x06
        finishPosted = false

        // Gadgetbridge uses the timestamp returned by the watch's START_DATE
        // response as the start time for the details operation. Preserve the
        // watch-provided timezone instead of converting the protobuf epoch through
        // the phone timezone (which shifted 01:46 to 04:46 on this T-Rex 2).
        val summaryEpoch = lastParsedSummary?.startTimestampSec
        val watchTimeBytes = sportsSinceTimeBytes?.copyOf()
        val timeBytes = if (watchTimeBytes != null && watchTimeBytes.size >= 8) {
            watchTimeBytes.copyOfRange(0, 8).also {
                it[6] = 0 // Gadgetbridge fetches sports details with minute precision.
            }
        } else {
            val fallback = summaryEpoch?.let { sec ->
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = sec * 1000L
                }
            } ?: sportsSinceCal ?: java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -30)
            }
            huamiTimeBytes(fallback)
        }
        Log.i(
            TAG,
            "details date source=${if (watchTimeBytes != null) "watch-response" else "fallback"} " +
                "summaryEpoch=$summaryEpoch watchBytes=${watchTimeBytes?.let { HuamiBleProtocol.hex(it) }} " +
                "requestBytes=${HuamiBleProtocol.hex(timeBytes)}"
        )
        val cmd = ByteArray(2 + timeBytes.size)
        cmd[0] = HuamiBleProtocol.CMD_ACTIVITY_START_DATE
        cmd[1] = 0x06 // SPORTS_DETAILS
        System.arraycopy(timeBytes, 0, cmd, 2, timeBytes.size)
        Log.i(TAG, "request sports DETAILS cmd=${HuamiBleProtocol.hex(cmd)}")
        runOnUiThread { setStatus("Запрос GPS details…") }
        writeActivityControl(g, cmd)

        handler.postDelayed({
            if (loadInProgress && !finishPosted && sportsFetchType == 0x06.toByte()) {
                finishSportsFetch("timeout-details")
            }
        }, 30_000)
    }

    @SuppressLint("MissingPermission")
    private fun startClassicAuth(g: BluetoothGatt) {
        val authChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_AUTH)
        if (authChar == null) {
            if (HuamiBleProtocol.hasZeppOsTransfer(g)) {
                startZeppOsAuth(g)
            } else {
                setStatus("Нет AUTH characteristic", error = true)
                finishLoadWithSummary(false, "нет CHAR_AUTH")
            }
            return
        }

        authState = HuamiBleProtocol.AuthState.WAITING_RANDOM
        setStatus("Auth: запрос random…")
        authChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        authChar.value = HuamiBleProtocol.packetRequestRandom()
        val ok = g.writeCharacteristic(authChar)
        if (!ok) {
            setStatus("Auth: writeCharacteristic failed", error = true)
            finishLoadWithSummary(false, "write AUTH failed")
        }
        handler.postDelayed({
            if (authState == HuamiBleProtocol.AuthState.WAITING_RANDOM ||
                authState == HuamiBleProtocol.AuthState.WAITING_CONFIRM
            ) {
                setStatus("Auth: таймаут ожидания ответа часов", error = true)
                authState = HuamiBleProtocol.AuthState.FAILED
                finishLoadWithSummary(false, "auth timeout")
            }
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun handleAuthNotification(g: BluetoothGatt, value: ByteArray) {
        val key = authKeyBytes ?: return
        val result = HuamiBleProtocol.parseAuthNotification(value, key) ?: return
        when (result) {
            is HuamiBleProtocol.AuthParseResult.GotRandom -> {
                authState = HuamiBleProtocol.AuthState.WAITING_CONFIRM
                runOnUiThread { setStatus("Auth: random получен, отправка AES…") }
                val authChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_AUTH)
                    ?: return
                authChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                authChar.value = HuamiBleProtocol.packetEncryptedRandom(result.encryptedResponse)
                g.writeCharacteristic(authChar)
            }
            HuamiBleProtocol.AuthParseResult.KeyAccepted -> {
                // Some devices need SEND_KEY first; we requested random already
                runOnUiThread { setStatus("Auth: key accepted, request random…") }
                val authChar = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_AUTH)
                    ?: return
                authChar.value = HuamiBleProtocol.packetRequestRandom()
                g.writeCharacteristic(authChar)
            }
            HuamiBleProtocol.AuthParseResult.Success -> {
                authState = HuamiBleProtocol.AuthState.AUTHENTICATED
                runOnUiThread { setStatus("Auth: успех. Запрос activity…", ok = true) }
                handler.post { requestActivityData(g) }
            }
            HuamiBleProtocol.AuthParseResult.Fail -> {
                authState = HuamiBleProtocol.AuthState.FAILED
                runOnUiThread {
                    setStatus("Auth: отказ (проверьте auth key)", error = true)
                    finishLoadWithSummary(false, "AUTH_FAIL — неверный ключ или нужна ECDH")
                }
            }
            is HuamiBleProtocol.AuthParseResult.Unknown -> {
                Log.w(TAG, "auth unknown: ${HuamiBleProtocol.hex(result.raw)}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestActivityData(g: BluetoothGatt) {
        val control = HuamiBleProtocol.findCharacteristic(g, HuamiBleProtocol.CHAR_ACTIVITY_CONTROL)
        if (control == null) {
            finishLoadWithSummary(
                authenticated = true,
                note = "AUTH OK, но нет ACTIVITY_CONTROL — GPS через Zepp file transfer"
            )
            return
        }
        // Request from 7 days ago
        val from = System.currentTimeMillis() / 1000 - 7 * 24 * 3600
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        control.value = HuamiBleProtocol.packetFetchActivityFrom(from)
        val ok1 = g.writeCharacteristic(control)
        Log.i(TAG, "write activity start-date ok=$ok1 from=$from")

        handler.postDelayed({
            control.value = HuamiBleProtocol.packetFetchData()
            val ok2 = g.writeCharacteristic(control)
            Log.i(TAG, "write FETCH_DATA ok=$ok2")
            runOnUiThread { setStatus("Запрос activity data отправлен, ждём пакеты…") }
        }, 500)

        // Collect for a few seconds, then summarize
        handler.postDelayed({
            finishLoadWithSummary(
                authenticated = true,
                note = "получено activity-пакетов: ${activityBuffer.size} " +
                    "(${activityBytesReceived} байт)"
            )
        }, 8_000)
    }

    private fun handleActivityData(value: ByteArray) {
        activityBytesReceived += value.size
        activityBuffer.add(value.copyOf())
        Log.d(TAG, "activity data len=${value.size} " +
            "head=${HuamiBleProtocol.hex(value.copyOf(minOf(12, value.size)))}")

        if (sportsFetchActive && value.isNotEmpty()) {
            val counter = value[0]
            if (sportsPacketCounter.toInt() == -1 || (sportsPacketCounter + 1).toByte() == counter) {
                sportsPacketCounter = counter
                if (value.size > 1) {
                    sportsDataBuffer.write(value, 1, value.size - 1)
                }
                val got = sportsDataBuffer.size()
                runOnUiThread {
                    setStatus("Sports data: $got / $expectedSportsBytes байт")
                }
                if (expectedSportsBytes > 0 && got >= expectedSportsBytes) {
                    finishSportsFetch("complete")
                }
            } else {
                Log.w(TAG, "sports packet counter gap: got=$counter last=$sportsPacketCounter")
                sportsPacketCounter = counter
                if (value.size > 1) sportsDataBuffer.write(value, 1, value.size - 1)
            }
        } else {
            runOnUiThread {
                setStatus("Activity данные: ${activityBuffer.size} пак. / $activityBytesReceived байт")
            }
        }
    }

    private fun finishLoadWithSummary(authenticated: Boolean, note: String) {
        if (finishPosted) {
            Log.d(TAG, "finishLoad already shown, skip: $note")
            return
        }
        finishPosted = true
        loadInProgress = false
        zeppAuthInProgress = false
        loadTracksButton.isEnabled = true
        val g = gatt
        val zepp = g != null && HuamiBleProtocol.hasZeppOsTransfer(g)
        val msg = buildString {
            append("Результат загрузки с часов\n\n")
            append("Устройство: ${selectedDevice?.name ?: selectedDevice?.address}\n")
            append("Auth: ${if (authenticated) "OK (classic AES)" else "не завершён"}\n")
            append("Activity packets: ${activityBuffer.size} ($activityBytesReceived байт)\n")
            append("Zepp OS transfer chars: ${if (zepp) "да" else "нет"}\n")
            append("\n$note\n\n")
            if (authenticated && activityBuffer.isNotEmpty()) {
                append(
                    "Сырые activity-пакеты получены. Разбор summary + GPS track points " +
                        "(и Zepp OS file transfer) — следующий этап портирования Gadgetbridge.\n"
                )
            } else if (zepp && !authenticated) {
                append(
                    "На T-Rex 2 обычно ECDH-auth (chunked 2021) + protobuf workout summary + " +
                        "file transfer GPS. Classic AES — запасной путь для более старых Huami.\n"
                )
            }
            append("\nЛоги: adb logcat -s WatchDirect HuamiBle")
        }
        AlertDialog.Builder(this)
            .setTitle("Треки с часов")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
        setStatus(
            if (authenticated) "Готово: $note"
            else "Остановлено: $note",
            ok = authenticated,
            error = !authenticated
        )
        Log.i(TAG, "finishLoad auth=$authenticated note=$note packets=${activityBuffer.size}")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.bottomMargin = dp(8) }


    private fun refreshKeyStatus() {
        if (!::keyStatusView.isInitialized) return
        val key = authKeyHex ?: sessionStore.getAuthKey()
        val mac = sessionStore.getAuthKeyMac()
        keyStatusView.text = when {
            key.isNullOrBlank() -> "Auth key: не задан (нужен для полной работы с часами)"
            else -> {
                val short = if (key.length > 12) key.take(6) + "…" + key.takeLast(4) else key
                val macPart = if (mac.isNullOrBlank()) "" else " · MAC $mac"
                "Auth key: $short (${key.length} hex)$macPart"
            }
        }
        keyStatusView.setTextColor(if (key.isNullOrBlank()) 0xFF8A3B12.toInt() else 0xFF1B5E20.toInt())
    }

    /**
     * Загрузка BLE auth key с серверов Zepp для устройств, уже привязанных к аккаунту.
     * Часы рядом не нужны — достаточно сессии (вход на экране «Облако»).
     */
    private fun fetchAuthKeyFromCloud() {
        if (!sessionStore.isLoggedIn()) {
            AlertDialog.Builder(this)
                .setTitle("Нужен вход в Zepp")
                .setMessage(
                    "Сначала войдите в аккаунт на экране «Работа с облаком Zepp». " +
                        "Auth key берётся из списка привязанных устройств вашего аккаунта."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        setStatus("Загрузка устройств и auth key…")
        fetchKeyButton.isEnabled = false
        scope.launch {
            try {
                val devices = withContext(Dispatchers.IO) {
                    repository.listBoundDevices()
                }
                if (devices.isEmpty()) {
                    setStatus("Устройств в аккаунте не найдено", error = true)
                    Toast.makeText(this@WatchDirectActivity, "Нет привязанных устройств", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val withKey = devices.filter { it.authKeyHex.isNotBlank() }
                if (withKey.isEmpty()) {
                    setStatus("Устройства есть, но auth_key пустой", error = true)
                    val detail = devices.joinToString("\n") { "${it.macAddress} · ${it.label}" }
                    AlertDialog.Builder(this@WatchDirectActivity)
                        .setTitle("Нет auth_key")
                        .setMessage(
                            "Сервер вернул устройства без auth_key:\n$detail\n\n" +
                                "Убедитесь, что часы привязаны через Zepp и хотя бы раз синхронизировались."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }
                if (withKey.size == 1) {
                    applyAuthKey(withKey[0])
                } else {
                    val labels = withKey.map { d ->
                        val act = if (d.active) "active" else "inactive"
                        "${d.macAddress} ($act) · ${d.label}"
                    }.toTypedArray()
                    AlertDialog.Builder(this@WatchDirectActivity)
                        .setTitle("Выберите устройство")
                        .setItems(labels) { _, which ->
                            applyAuthKey(withKey[which])
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                    setStatus("Выберите устройство из списка")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "fetchAuthKeyFromCloud", t)
                setStatus("Ошибка: ${t.message}", error = true)
                Toast.makeText(this@WatchDirectActivity, t.message ?: "Ошибка", Toast.LENGTH_LONG).show()
            } finally {
                fetchKeyButton.isEnabled = true
            }
        }
    }

    private fun applyAuthKey(device: BoundDevice) {
        authKeyHex = device.authKeyHex
        sessionStore.saveAuthKey(device.authKeyHex, device.macAddress)
        refreshKeyStatus()
        setStatus("Auth key получен для ${device.macAddress}", ok = true)
        Toast.makeText(
            this,
            "Ключ сохранён (${device.authKeyHex.length} hex) для ${device.macAddress}",
            Toast.LENGTH_LONG
        ).show()
        Log.i(TAG, "auth key saved mac=${device.macAddress} len=${device.authKeyHex.length}")
    }

    companion object {
        private const val TAG = "WatchDirect"
        private const val REQ_BLE = 2001
        private const val REQ_SAVE_GPX = 2002
        /** Huami 2021 chunked endpoint for authentication (InitOperation2021). */
        private const val CHUNKED2021_ENDPOINT_AUTH: Short = 0x0082
        /** HTTP proxy (watch → phone). */
        private const val CHUNKED2021_ENDPOINT_HTTP: Short = 0x0001
        /** Activity / sports fetch control (ZeppOsActivityFetchService). */
        private const val CHUNKED2021_ENDPOINT_ACTIVITY: Short = 0x004b
        /** Service list (ZeppOsServicesService). */
        private const val CHUNKED2021_ENDPOINT_SERVICES: Short = 0x0000
        /** Connection ping/pong (ZeppOsConnectionService). */
        private const val CHUNKED2021_ENDPOINT_CONNECTION: Short = 0x0015
    }
}
