package com.zeoz.zgt

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.appcompat.app.AlertDialog
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native BLE transport for the NIIMBOT B1 Pro used by ZGT.
 *
 * Scope is intentionally narrow:
 * - model id 4097 only
 * - protocol/task V4
 * - 300 dpi
 * - 50 x 30 mm, 576 x 354 px
 * - one label per job
 *
 * Row writes use WRITE_TYPE_DEFAULT (write-with-response). This mirrors the
 * "acked" Web Bluetooth mode that was physically validated on Android before
 * adding the native bridge.
 */
class NiimbotB1ProPrinter(
    private val activity: Activity,
    private val emit: (type: String, message: String) -> Unit
) {
    companion object {
        private val SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        private val CHARACTERISTIC_UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MODEL_ID_B1_PRO = 4097
        private const val WIDTH = 576
        private const val HEIGHT = 354
        private const val DENSITY = 3
        private const val LABEL_TYPE = 1
        private const val SPEED = 1
    }

    private data class Packet(val cmd: Int, val data: ByteArray)

    private val executor = Executors.newSingleThreadExecutor()
    private val notifications = LinkedBlockingQueue<Packet>()
    private val writeResults = LinkedBlockingQueue<Int>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var connectedModelId: Int? = null
    @Volatile private var serviceReady = false
    @Volatile private var intentionalDisconnect = false

    @Volatile private var connectLatch: CountDownLatch? = null
    @Volatile private var descriptorLatch: CountDownLatch? = null
    @Volatile private var mtuLatch: CountDownLatch? = null
    @Volatile private var connectError: String? = null

    private val adapter: BluetoothAdapter?
        get() = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun isConnected(): Boolean {
        return gatt != null && characteristic != null && serviceReady && connectedModelId == MODEL_ID_B1_PRO
    }

    fun print(dataUrl: String) {
        executor.execute {
            var jobStarted = false
            var jobEnded = false

            try {
                emit("progress", "Buscando NIIMBOT B1 Pro…")
                ensureConnected()

                val packed = imageToPacked(dataUrl)

                emit("connected", "B1 Pro conectada. Configurando impresión…")
                requireAck(0x21, ints(DENSITY), 0x31, 2000, "densidad")
                requireAck(0x23, ints(LABEL_TYPE), 0x33, 2000, "tipo de etiqueta")

                val start = ints(0, 1, 0, 0, 0, 0, 0, SPEED, 0)
                requireAck(0x01, start, 0x02, 3000, "inicio de impresión")
                jobStarted = true

                send(0xa3, ints(1))
                Thread.sleep(30)

                val pageSize = ints(
                    (HEIGHT shr 8) and 0xff, HEIGHT and 0xff,
                    (WIDTH shr 8) and 0xff, WIDTH and 0xff,
                    0, 1,
                    0, 0, 0, 0, 0, 0, 0
                )
                requireAck(0x13, pageSize, 0x14, 3000, "tamaño de página")

                emit("progress", "Enviando etiqueta a la B1 Pro…")
                sendImage(packed)

                requireAck(0xe3, ints(1), 0xe4, 12000, "fin de página")

                emit("progress", "Imprimiendo…")
                waitUntilPrinted()

                requireAck(0xf3, ints(1), 0xf4, 4000, "fin de trabajo")
                jobEnded = true

                emit("success", "Etiqueta impresa y confirmada por la B1 Pro.")
            } catch (error: Exception) {
                if (jobStarted && !jobEnded) {
                    try {
                        sendWait(0xf3, ints(1), 0xf4, 2500)
                    } catch (_: Exception) {
                        // Best effort: PrintEnd feeds/retracts the paper when possible.
                    }
                }

                emit(
                    "error",
                    error.message ?: "No se pudo completar la impresión en la B1 Pro."
                )
            }
        }
    }

    fun disconnect() {
        executor.execute {
            disconnectInternal(true)
            emit("disconnected", "B1 Pro desconectada.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureConnected() {
        if (isConnected()) {
            return
        }

        val bluetooth = adapter ?: throw Exception("Este dispositivo no dispone de Bluetooth.")
        if (!bluetooth.isEnabled) {
            throw Exception("Activá Bluetooth en Android y volvé a intentar.")
        }

        disconnectInternal(false)

        val device = scanAndChoose(bluetooth)
        emit("progress", "Conectando con ${safeDeviceName(device)}…")
        connectGatt(device)

        writeRaw(ints(0x03, 0x55, 0x55, 0xc1, 0x01, 0x01, 0xc1, 0xaa, 0xaa))
        Thread.sleep(200)

        try {
            sendWait(0xa5, ints(1), 0xb5, 1500)
        } catch (_: Exception) {
        }

        val model = sendWait(0x40, ints(0x08), 0x48, 2000)
            ?: throw Exception("La impresora no respondió su identificación.")

        val modelId = when {
            model.data.size >= 2 -> ((model.data[0].toInt() and 0xff) shl 8) or
                (model.data[1].toInt() and 0xff)
            model.data.size == 1 -> (model.data[0].toInt() and 0xff) shl 8
            else -> 0
        }

        if (modelId != MODEL_ID_B1_PRO) {
            disconnectInternal(false)
            throw Exception(
                "La impresora seleccionada no es una NIIMBOT B1 Pro (ID 4097). " +
                    "Detectada: ID $modelId. No se envió ninguna etiqueta."
            )
        }

        connectedModelId = modelId
        activity.getSharedPreferences("zgt", Context.MODE_PRIVATE)
            .edit()
            .putString("niimbot_last_address", device.address)
            .apply()
    }

    @SuppressLint("MissingPermission")
    private fun scanAndChoose(bluetooth: BluetoothAdapter): BluetoothDevice {
        val scanner = bluetooth.bluetoothLeScanner
            ?: throw Exception("Android no pudo iniciar el escaneo Bluetooth.")

        val lastAddress = activity
            .getSharedPreferences("zgt", Context.MODE_PRIVATE)
            .getString("niimbot_last_address", null)

        // Keep RSSI so the nearest printer is first when more than one B1 is
        // visible. ConcurrentHashMap also avoids races between the BLE callback
        // thread and the print worker thread.
        val found = ConcurrentHashMap<String, Pair<BluetoothDevice, Int>>()
        val scanFailure = AtomicInteger(0)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try {
                    device.name ?: result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    result.scanRecord?.deviceName
                }

                val advertisesNiimbot = result.scanRecord
                    ?.serviceUuids
                    ?.any { parcel -> parcel.uuid == SERVICE_UUID } == true

                val looksLikeB1 =
                    name?.contains("B1", ignoreCase = true) == true ||
                    name?.contains("NIIMBOT", ignoreCase = true) == true ||
                    advertisesNiimbot ||
                    (lastAddress != null && device.address.equals(lastAddress, ignoreCase = true))

                if (looksLikeB1) {
                    found[device.address] = device to result.rssi
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                scanFailure.compareAndSet(0, errorCode)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            Thread.sleep(7000)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }

        val failureCode = scanFailure.get()
        val devices = found.values
            .sortedByDescending { it.second }
            .map { it.first }

        if (devices.isEmpty()) {
            if (failureCode != 0) {
                throw Exception(
                    "Android no pudo completar el escaneo Bluetooth (código $failureCode). " +
                        "Apagá y encendé Bluetooth y volvé a intentar."
                )
            }

            throw Exception(
                "No se encontró ninguna NIIMBOT B1 Pro. " +
                    "Verificá que esté encendida, cerca y que no siga conectada a otro teléfono."
            )
        }

        if (devices.size == 1) {
            return devices.first()
        }

        val choice = ArrayBlockingQueue<Int>(1)
        activity.runOnUiThread {
            val labels = devices.map { device ->
                "${safeDeviceName(device)} · ${device.address.takeLast(5)}"
            }.toTypedArray()

            AlertDialog.Builder(activity)
                .setTitle("Elegí la NIIMBOT B1 Pro")
                .setItems(labels) { _, which -> choice.offer(which) }
                .setNegativeButton("Cancelar") { _, _ -> choice.offer(-1) }
                .setOnCancelListener { choice.offer(-1) }
                .show()
        }

        val selected = choice.poll(60, TimeUnit.SECONDS) ?: -1
        if (selected !in devices.indices) {
            throw Exception("Selección de impresora cancelada.")
        }

        return devices[selected]
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        serviceReady = false
        connectError = null
        connectedModelId = null
        intentionalDisconnect = false

        val latch = CountDownLatch(1)
        connectLatch = latch

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(activity, false, callback)
        }

        if (!latch.await(15, TimeUnit.SECONDS) || !serviceReady || characteristic == null) {
            val detail = connectError
            disconnectInternal(false)
            throw Exception(detail ?: "No se pudo preparar la conexión Bluetooth con la B1 Pro.")
        }

        val currentGatt = gatt
        if (currentGatt != null) {
            val mLatch = CountDownLatch(1)
            mtuLatch = mLatch
            try {
                if (currentGatt.requestMtu(247)) {
                    mLatch.await(3, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                // The following write is the real validation if MTU negotiation is unavailable.
            } finally {
                mtuLatch = null
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                emit("progress", "Bluetooth conectado. Preparando servicio NIIMBOT…")
                if (!g.discoverServices()) {
                    connectError = "Android no pudo consultar los servicios Bluetooth de la impresora."
                    connectLatch?.countDown()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serviceReady = false
                characteristic = null
                connectedModelId = null
                if (status != BluetoothGatt.GATT_SUCCESS && connectError == null) {
                    connectError = "La conexión Bluetooth se cerró (código $status)."
                }
                connectLatch?.countDown()

                if (!intentionalDisconnect) {
                    emit("disconnected", "La B1 Pro se desconectó. Volvé a conectar para imprimir.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectError = "No se pudieron leer los servicios de la B1 Pro (código $status)."
                connectLatch?.countDown()
                return
            }

            val service = g.getService(SERVICE_UUID)
            val ch = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (ch == null) {
                connectError = "La impresora no expone el servicio Bluetooth NIIMBOT esperado."
                connectLatch?.countDown()
                return
            }

            characteristic = ch

            if (!g.setCharacteristicNotification(ch, true)) {
                connectError = "Android no pudo activar las respuestas Bluetooth de la impresora."
                connectLatch?.countDown()
                return
            }

            val descriptor = ch.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                serviceReady = true
                connectLatch?.countDown()
                return
            }

            descriptorLatch = CountDownLatch(1)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(descriptor)) {
                connectError = "Android no pudo habilitar las notificaciones Bluetooth."
                connectLatch?.countDown()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorLatch?.countDown()
            descriptorLatch = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                serviceReady = true
            } else {
                connectError = "La B1 Pro no confirmó la activación de notificaciones (código $status)."
            }
            connectLatch?.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuLatch?.countDown()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (ch.uuid == CHARACTERISTIC_UUID) {
                writeResults.offer(status)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            if (ch.uuid == CHARACTERISTIC_UUID) {
                parseNotifications(ch.value ?: return)
            }
        }
    }

    private fun parseNotifications(bytes: ByteArray) {
        var offset = 0

        while (offset + 7 <= bytes.size) {
            if (
                (bytes[offset].toInt() and 0xff) != 0x55 ||
                (bytes[offset + 1].toInt() and 0xff) != 0x55
            ) {
                offset++
                continue
            }

            val cmd = bytes[offset + 2].toInt() and 0xff
            val len = bytes[offset + 3].toInt() and 0xff
            val frameSize = 7 + len
            if (offset + frameSize > bytes.size) {
                return
            }

            val data = bytes.copyOfRange(offset + 4, offset + 4 + len)
            notifications.offer(Packet(cmd, data))
            offset += frameSize
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(value: ByteArray) {
        val currentGatt = gatt ?: throw Exception("La B1 Pro no está conectada.")
        val ch = characteristic ?: throw Exception("Canal Bluetooth NIIMBOT no disponible.")

        if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            throw Exception("La B1 Pro no admite el modo de escritura Bluetooth confirmado requerido por Android.")
        }

        writeResults.clear()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = value

        if (!currentGatt.writeCharacteristic(ch)) {
            throw Exception("Android rechazó un paquete Bluetooth antes de enviarlo.")
        }

        val result = writeResults.poll(5000, TimeUnit.MILLISECONDS)
            ?: throw Exception("Android no confirmó el envío de un paquete Bluetooth.")

        if (result != BluetoothGatt.GATT_SUCCESS) {
            throw Exception("Error Bluetooth al enviar datos a la B1 Pro (código $result).")
        }
    }

    private fun send(cmd: Int, data: ByteArray) {
        writeRaw(pack(cmd, data))
    }

    private fun sendWait(
        cmd: Int,
        data: ByteArray,
        responseCmd: Int,
        timeoutMs: Long
    ): Packet? {
        notifications.clear()
        send(cmd, data)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val packet = notifications.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: break

            if (packet.cmd == responseCmd) {
                return packet
            }
        }

        return null
    }

    private fun requireAck(
        cmd: Int,
        data: ByteArray,
        responseCmd: Int,
        timeoutMs: Long,
        label: String
    ): Packet {
        return sendWait(cmd, data, responseCmd, timeoutMs)
            ?: throw Exception("La B1 Pro no confirmó $label.")
    }

    private fun pack(cmd: Int, data: ByteArray): ByteArray {
        val out = ByteArray(7 + data.size)
        out[0] = 0x55
        out[1] = 0x55
        out[2] = cmd.toByte()
        out[3] = data.size.toByte()

        var crc = cmd xor data.size
        for (i in data.indices) {
            out[4 + i] = data[i]
            crc = crc xor (data[i].toInt() and 0xff)
        }

        out[4 + data.size] = (crc and 0xff).toByte()
        out[5 + data.size] = 0xaa.toByte()
        out[6 + data.size] = 0xaa.toByte()
        return out
    }

    private fun imageToPacked(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.startsWith("data:image/png;base64,")) {
            throw Exception("La etiqueta recibida por la APK no es una imagen PNG válida.")
        }

        val raw = try {
            Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        } catch (_: Exception) {
            throw Exception("La APK no pudo decodificar la etiqueta.")
        }

        val source = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw Exception("La APK no pudo abrir la imagen de la etiqueta.")

        val bitmap = if (source.width == WIDTH && source.height == HEIGHT) {
            source
        } else {
            Bitmap.createScaledBitmap(source, WIDTH, HEIGHT, false).also {
                if (it !== source) source.recycle()
            }
        }

        try {
            val pixels = IntArray(WIDTH * HEIGHT)
            bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

            val stride = (WIDTH + 7) shr 3
            val out = ByteArray(stride * HEIGHT)

            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val pixel = pixels[y * WIDTH + x]
                    val alpha = (pixel ushr 24) and 0xff
                    if (alpha <= 32) continue

                    val red = (pixel ushr 16) and 0xff
                    val green = (pixel ushr 8) and 0xff
                    val blue = pixel and 0xff
                    val lum = (299 * red + 587 * green + 114 * blue) / 1000

                    if (lum < 128) {
                        val index = y * stride + (x shr 3)
                        out[index] = (
                            (out[index].toInt() and 0xff) or
                                (0x80 shr (x and 7))
                            ).toByte()
                    }
                }
            }

            return out
        } finally {
            bitmap.recycle()
        }
    }

    private fun sendImage(buf: ByteArray) {
        val stride = (WIDTH + 7) shr 3
        var row = 0
        var lastProgress = -10

        while (row < HEIGHT) {
            val offset = row * stride
            val empty = rowIsEmpty(buf, offset, stride)

            var run = 1
            while (row + run < HEIGHT && run < 200) {
                val next = (row + run) * stride
                var same = true

                for (i in 0 until stride) {
                    if (buf[offset + i] != buf[next + i]) {
                        same = false
                        break
                    }
                }

                if (!same) break
                run++
            }

            if (empty) {
                send(
                    0x84,
                    ints((row shr 8) and 0xff, row and 0xff, run)
                )
            } else {
                val total = popcountRow(buf, offset, stride)
                val data = ByteArray(6 + stride)
                data[0] = ((row shr 8) and 0xff).toByte()
                data[1] = (row and 0xff).toByte()
                data[2] = 0
                data[3] = (total and 0xff).toByte()
                data[4] = ((total shr 8) and 0xff).toByte()
                data[5] = run.toByte()

                System.arraycopy(buf, offset, data, 6, stride)
                send(0x85, data)
            }

            row += run
            val progress = (row * 100 / HEIGHT)
            if (progress >= lastProgress + 10 || row >= HEIGHT) {
                lastProgress = progress
                emit("progress", "Enviando etiqueta… $progress%")
            }
        }
    }

    private fun waitUntilPrinted() {
        val deadline = System.currentTimeMillis() + 30000

        while (System.currentTimeMillis() < deadline) {
            val status = sendWait(0xa3, ints(1), 0xb3, 1500)
            if (status != null && status.data.size >= 4) {
                val page = ((status.data[0].toInt() and 0xff) shl 8) or
                    (status.data[1].toInt() and 0xff)
                val print = status.data[2].toInt() and 0xff

                emit("progress", "Imprimiendo… $print%")
                if (page >= 1) {
                    return
                }
            }

            Thread.sleep(150)
        }

        throw Exception("La B1 Pro no confirmó que la etiqueta terminara de imprimirse.")
    }

    private fun rowIsEmpty(buf: ByteArray, offset: Int, stride: Int): Boolean {
        for (i in 0 until stride) {
            if (buf[offset + i].toInt() != 0) return false
        }
        return true
    }

    private fun popcountRow(buf: ByteArray, offset: Int, stride: Int): Int {
        var total = 0
        for (i in 0 until stride) {
            total += Integer.bitCount(buf[offset + i].toInt() and 0xff)
        }
        return total
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: "NIIMBOT B1"
        } catch (_: SecurityException) {
            "NIIMBOT B1"
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(userInitiated: Boolean) {
        intentionalDisconnect = userInitiated
        connectedModelId = null
        serviceReady = false
        characteristic = null
        notifications.clear()
        writeResults.clear()

        val current = gatt
        gatt = null

        try {
            current?.disconnect()
        } catch (_: Exception) {
        }

        try {
            current?.close()
        } catch (_: Exception) {
        }
    }

    private fun ints(vararg values: Int): ByteArray {
        return ByteArray(values.size) { index -> (values[index] and 0xff).toByte() }
    }
}
