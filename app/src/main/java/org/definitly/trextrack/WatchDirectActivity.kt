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

    // Common Huami / Zepp OS related UUIDs (informational)
    private val HUAMI_SERVICE = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    private val HUAMI_AUTH_SERVICE = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")

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

    override fun onDestroy() {
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
        setStatus("Подключение к ${device.name ?: device.address}…")
        connectButton.isEnabled = false
        try {
            gatt?.close()
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt", e)
            setStatus("Ошибка connectGatt: ${e.message}", error = true)
            connectButton.isEnabled = true
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connected = true
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
                    val uuid = s.uuid.toString()
                    val short = uuid.take(8)
                    val hint = when {
                        s.uuid == HUAMI_SERVICE -> " (Huami)"
                        s.uuid == HUAMI_AUTH_SERVICE -> " (Huami Auth)"
                        else -> ""
                    }
                    sb.append("• $short…$hint\n")
                    for (c in s.characteristics) {
                        val props = propFlags(c.properties)
                        sb.append("    ${c.uuid.toString().take(8)}… [$props]\n")
                    }
                }
                servicesView.text = if (sb.isEmpty()) "Сервисы не найдены" else sb.toString()
                val hasHuami = discoveredServices.any {
                    it.uuid == HUAMI_SERVICE || it.uuid == HUAMI_AUTH_SERVICE
                }
                setStatus(
                    if (hasHuami) "Подключено, Huami-сервисы обнаружены"
                    else "Подключено, сервисов: ${discoveredServices.size}",
                    ok = true
                )
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Reserved for future protocol reads
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
     * Каркас загрузки треков с часов.
     * Полная реализация протокола Huami/Zepp OS (список activity + GPS points)
     * — объёмная задача (см. Gadgetbridge Zepp OS support). Здесь:
     * - проверка соединения;
     * - проверка наличия Huami-сервисов;
     * - учёт auth key;
     * - понятный статус пользователю.
     */
    private fun loadTracksFromWatch() {
        if (!connected || gatt == null) {
            Toast.makeText(this, "Сначала подключите часы", Toast.LENGTH_SHORT).show()
            return
        }
        val hasHuami = discoveredServices.any {
            it.uuid == HUAMI_SERVICE || it.uuid == HUAMI_AUTH_SERVICE
        }
        val keyPresent = !authKeyHex.isNullOrBlank()

        val msg = buildString {
            append("Запрос загрузки треков с часов\n\n")
            append("Устройство: ${selectedDevice?.name ?: selectedDevice?.address}\n")
            append("Huami-сервисы: ${if (hasHuami) "да" else "не обнаружены"}\n")
            append("Auth key: ${if (keyPresent) "задан" else "не задан"}\n\n")
            if (!hasHuami) {
                append(
                    "На этом соединении не видны типичные Huami UUID (fee0/fee1). " +
                        "Возможно, часы используют другой транспорт (BT Classic / btrfcomm) " +
                        "или требуется предварительная аутентификация.\n\n"
                )
            }
            if (!keyPresent) {
                append(
                    "Для T-Rex 2 обычно нужен auth key. Введите его кнопкой «Ввести Huami auth key».\n\n"
                )
            }
            append(
                "Реализация полного протокола выгрузки activity + GPS сейчас в виде каркаса. " +
                    "Следующий шаг — портирование логики синхронизации тренировок " +
                    "(как в Gadgetbridge для Zepp OS) поверх уже установленного GATT-соединения."
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Треки с часов")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()

        setStatus(
            when {
                !hasHuami -> "Треки: нет Huami-сервисов на соединении"
                !keyPresent -> "Треки: нужен auth key (каркас готов)"
                else -> "Треки: каркас готов, протокол — следующий этап"
            }
        )
        Log.i(
            TAG,
            "loadTracksFromWatch device=${selectedDevice?.address} huami=$hasHuami key=$keyPresent services=${discoveredServices.size}"
        )
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
    }
}
