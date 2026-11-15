/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import java.awt.GridLayout
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.*
import kotlinx.serialization.json.*

internal data class CompassReading(val heading: Double, val pitch: Double?, val receivedAt: Long = System.nanoTime())

internal fun CompassReading.calibrated(headingCorrection: Double, tiltCorrection: Double, tiltSign: Double): CompassReading =
    copy(heading = compassHeading(heading + headingCorrection), pitch = pitch?.let { (it * tiltSign + tiltCorrection).coerceIn(-90.0, 90.0) })

internal fun CompassReading.isFresh(seconds: Int, now: Long = System.nanoTime()): Boolean =
    now - receivedAt < seconds * 1_000_000_000L

internal fun compassHeading(degrees: Double): Double = ((degrees % 360) + 360) % 360
internal fun pointingDifference(target: Double, heading: Double): Double = compassHeading(target - heading + 180) - 180

internal fun parseCompassReading(line: String, json: Boolean, headingField: String, pitchField: String): CompassReading? = runCatching {
    fun number(value: String?): Double? = value?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)
    val heading: Double
    val pitch: Double?
    if (json) {
        val fields = Json.parseToJsonElement(line).jsonObject
        heading = number(fields[headingField]?.jsonPrimitive?.content) ?: return null
        pitch = if (pitchField.isBlank()) null else number(fields[pitchField]?.jsonPrimitive?.content) ?: return null
    } else {
        val fields = line.split(',')
        heading = number(fields.getOrNull(headingField.toInt() - 1)) ?: return null
        pitch = if (pitchField.isBlank()) null else number(fields.getOrNull(pitchField.toInt() - 1)) ?: return null
    }
    require(pitch == null || pitch in -90.0..90.0)
    CompassReading(compassHeading(heading), pitch)
}.getOrNull()

/** Optional newline-delimited sensor input; serial includes Bluetooth serial devices exposed by macOS. */
internal class CompassDesktop(private val onReading: (CompassReading?) -> Unit) : JPanel(GridLayout(0, 2, 8, 8)), AutoCloseable {
    private val prefs = Preferences.userNodeForPackage(CompassDesktop::class.java)
    private val enabled = JCheckBox("Enable external compass", false)
    private val transport = JComboBox(arrayOf("Serial", "TCP"))
    private val device = JTextField(prefs.get("device", "/dev/cu.usbserial"))
    private val host = JTextField(prefs.get("host", "localhost"))
    private val port = JTextField(prefs.get("port", "5000"))
    private val baud = JComboBox(arrayOf("4800", "9600", "19200", "38400", "57600", "115200"))
    private val format = JComboBox(arrayOf("CSV", "JSON"))
    private val headingField = JTextField(prefs.get("headingField", "1"))
    private val pitchField = JTextField(prefs.get("pitchField", "2"))
    private val headingOffset = JTextField(prefs.get("headingOffset", "0"))
    private val declination = JTextField(prefs.get("declination", "0"))
    private val pitchOffset = JTextField(prefs.get("pitchOffset", "0"))
    private val invertPitch = JCheckBox("Invert pitch", prefs.getBoolean("invertPitch", false))
    private val staleSeconds = JSpinner(SpinnerNumberModel(prefs.getInt("staleSeconds", 3).coerceIn(1, 60), 1, 60, 1))
    private val status = JLabel("Disabled · north-up radar")
    private val latest = AtomicReference<CompassReading?>()
    private val message = AtomicReference("Waiting for sensor data")
    private val generation = AtomicLong()
    private val resourceLock = Any()
    private var resource: Closeable? = null
    private var headingCorrection = 0.0
    private var tiltCorrection = 0.0
    private var tiltSign = 1.0
    private val timer = Timer(100) { refresh() }

    init {
        transport.selectedItem = prefs.get("transport", "Serial")
        baud.selectedItem = prefs.get("baud", "9600")
        format.selectedItem = prefs.get("format", "CSV")
        val apply = JButton("Apply / reconnect")
        add(enabled); add(apply)
        add(JLabel("Connection")); add(transport)
        add(JLabel("Serial device (/dev/cu.*)")); add(device)
        add(JLabel("Serial baud")); add(baud)
        add(JLabel("TCP sensor host")); add(host)
        add(JLabel("TCP sensor port")); add(port)
        add(JLabel("One record per line")); add(format)
        add(JLabel("Heading: CSV column (1-based) or JSON key")); add(headingField)
        add(JLabel("Pitch column / key (blank = heading only)")); add(pitchField)
        add(JLabel("Mounting heading offset °")); add(headingOffset)
        add(JLabel("Magnetic declination ° (east +; true-heading sensor: 0)")); add(declination)
        add(JLabel("Pitch mounting offset °")); add(pitchOffset)
        add(invertPitch); add(status)
        add(JLabel("Discard readings after (seconds)")); add(staleSeconds)
        add(JLabel("CSV example: 123.4,15.0 · JSON: {\"heading\":123.4,\"pitch\":15.0}"))
        add(JLabel("Degrees; pitch + upward. Set JSON keys to heading / pitch. Apply changes to connect."))
        add(JLabel("Align the sensor with the Mac or antenna pointing axis."))
        add(JLabel("Requires a compatible sensor stream; raw USB/Bluetooth protocols need a bridge."))
        format.addActionListener {
            if (format.selectedItem == "JSON") {
                if (headingField.text == "1") headingField.text = "heading"
                if (pitchField.text == "2") pitchField.text = "pitch"
            } else {
                if (headingField.text == "heading") headingField.text = "1"
                if (pitchField.text == "pitch") pitchField.text = "2"
            }
        }
        enabled.addActionListener { apply() }; apply.addActionListener { apply() }
        timer.start()
    }

    private fun stopInput() {
        generation.incrementAndGet()
        synchronized(resourceLock) { runCatching { resource?.close() }; resource = null }
        latest.set(null); onReading(null)
    }

    private fun apply() {
        stopInput()
        if (!enabled.isSelected) { status.text = "Disabled · north-up radar"; return }
        runCatching {
            fun correction(field: JTextField) = field.text.trim().toDouble().also { require(it.isFinite()) { "Offsets must be finite degrees" } }
            headingCorrection = correction(headingOffset) + correction(declination)
            require(headingCorrection.isFinite()) { "Heading correction is too large" }
            tiltCorrection = correction(pitchOffset); tiltSign = if (invertPitch.isSelected) -1.0 else 1.0
            val json = format.selectedItem == "JSON"
            val heading = headingField.text.trim(); val pitch = pitchField.text.trim()
            require(heading.isNotEmpty()) { "Set the heading field" }
            if (!json) require(heading.toInt() in 1..64 && (pitch.isBlank() || pitch.toInt() in 1..64)) { "CSV columns must be 1–64" }
            val serial = transport.selectedItem == "Serial"
            val path = device.text.trim(); val address = host.text.trim()
            val number = if (serial) 0 else port.text.trim().toInt()
            require(if (serial) path.startsWith("/dev/cu.") else address.isNotEmpty() && number in 1..65535) { "Enter a serial device or TCP host and port" }
            save()
            val session = generation.get(); val rate = baud.selectedItem.toString()
            message.set("Connecting…"); status.text = "Connecting…"
            Thread({
                try {
                    fun own(closeable: Closeable) {
                        synchronized(resourceLock) {
                            if (generation.get() != session) { closeable.close(); throw java.io.IOException("Disconnected") }
                            resource = closeable
                        }
                    }
                    val input: InputStream = if (serial) {
                        val process = ProcessBuilder("/bin/stty", "-f", path, rate, "raw", "-echo").redirectErrorStream(true).start()
                        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Serial configuration timed out") }
                        require(process.exitValue() == 0) { "Could not configure serial device" }
                        FileInputStream(path).also(::own)
                    } else {
                        val socket = Socket(); own(socket)
                        socket.connect(InetSocketAddress(address, number), 3000)
                        socket.getInputStream()
                    }
                    if (generation.get() == session) message.set("Connected · waiting for a valid sensor record")
                    input.reader(Charsets.UTF_8).buffered().use { stream ->
                        // Bound individual records so a noisy or binary device cannot grow memory indefinitely.
                        val line = StringBuilder(); var oversized = false
                        while (generation.get() == session) {
                            val char = stream.read()
                            if (char < 0) error("Sensor disconnected")
                            if (char == 10) {
                                val reading = if (oversized) null else parseCompassReading(line.toString().trimEnd('\r'), json, heading, pitch)
                                synchronized(resourceLock) {
                                    if (generation.get() == session) {
                                        if (reading != null) { latest.set(reading); message.set("Receiving sensor data") }
                                        else message.set("Invalid record · check format and fields")
                                    }
                                }
                                line.clear(); oversized = false
                            } else if (line.length < 4096) line.append(char.toChar()) else oversized = true
                        }
                    }
                } catch (error: Exception) {
                    synchronized(resourceLock) { if (generation.get() == session) { latest.set(null); message.set("Compass: ${error.message}") } }
                } finally {
                    synchronized(resourceLock) {
                        if (generation.get() == session) { runCatching { resource?.close() }; resource = null }
                    }
                }
            }, "Look4Sat-compass").apply { isDaemon = true; start() }
        }.onFailure { enabled.isSelected = false; status.text = "Compass settings: ${it.message}" }
    }

    private fun refresh() {
        if (!enabled.isSelected) return
        val raw = latest.get()
        if (raw == null || !raw.isFresh(staleSeconds.value as Int)) {
            onReading(null)
            status.text = if (raw != null) "Sensor data stale · north-up radar" else message.get()
            return
        }
        val reading = raw.calibrated(headingCorrection, tiltCorrection, tiltSign)
        onReading(reading)
        status.text = "Heading %.1f° · %s".format(reading.heading, reading.pitch?.let { "pitch %.1f°".format(it) } ?: "heading only")
    }

    private fun save() {
        listOf("device" to device, "host" to host, "port" to port, "headingField" to headingField, "pitchField" to pitchField,
            "headingOffset" to headingOffset, "declination" to declination, "pitchOffset" to pitchOffset).forEach { (key, field) -> prefs.put(key, field.text.trim()) }
        prefs.putInt("staleSeconds", staleSeconds.value as Int)
        prefs.put("transport", transport.selectedItem.toString()); prefs.put("baud", baud.selectedItem.toString())
        prefs.put("format", format.selectedItem.toString()); prefs.putBoolean("invertPitch", invertPitch.isSelected)
    }

    override fun close() { timer.stop(); stopInput() }
}
