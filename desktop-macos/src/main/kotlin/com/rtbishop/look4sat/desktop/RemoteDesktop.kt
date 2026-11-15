/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import java.awt.GridLayout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.*

internal class RemoteDesktop : JPanel(GridLayout(0, 2, 8, 8)), AutoCloseable {
    private val prefs = Preferences.userNodeForPackage(RemoteDesktop::class.java)
    private val rotator = JCheckBox("Enable TCP rotator", false)
    private val receiver = JCheckBox("Enable TCP frequency control", false)
    private val rotatorHost = JTextField(prefs.get("rotatorHost", "localhost"))
    private val rotatorPort = JTextField(prefs.get("rotatorPort", "4533"))
    private val receiverHost = JTextField(prefs.get("receiverHost", "localhost"))
    private val receiverPort = JTextField(prefs.get("receiverPort", "4532"))
    private val rotationFormat = JTextField(prefs.get("rotationFormat", "P \$AZ \$EL"))
    private val frequencyFormat = JTextField(prefs.get("frequencyFormat", "F \$FREQ"))
    private val offset = JTextField(prefs.get("offset", "0"))
    private val status = JLabel("Remote control disabled")
    private val latest = AtomicReference<Commands?>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Look4Sat-remote").apply { isDaemon = true } }
    private var rotationSocket: Socket? = null
    private var frequencySocket: Socket? = null
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Endpoint(val host: String, val port: Int, val command: String)
    private data class Commands(val rotation: Endpoint?, val frequency: Endpoint?)

    init {
        add(rotator); add(receiver)
        add(JLabel("Rotator host")); add(rotatorHost); add(JLabel("Rotator port")); add(rotatorPort)
        add(JLabel("Rotation command (\$AZ, \$EL)")); add(rotationFormat)
        add(JLabel("Receiver host")); add(receiverHost); add(JLabel("Receiver port")); add(receiverPort)
        add(JLabel("Frequency command (\$FREQ)")); add(frequencyFormat)
        add(JLabel("Frequency offset Hz")); add(offset); add(status)
    }

    fun update(position: OrbitalPos, frequency: Long?) {
        fun endpoint(enabled: Boolean, host: JTextField, port: JTextField, command: String): Endpoint? {
            if (!enabled) return null
            val number = port.text.toInt(); require(number in 1..65535 && host.text.isNotBlank()) { "Invalid host or port" }
            return Endpoint(host.text.trim(), number, command.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t") + "\n")
        }
        runCatching {
            val az = Math.toDegrees(position.azimuth).toString(); val el = Math.toDegrees(position.elevation).coerceAtLeast(0.0).toString()
            val rotation = endpoint(rotator.isSelected, rotatorHost, rotatorPort, rotationFormat.text.replace("\$AZ", az).replace("\$EL", el))
            val corrected = frequency?.let { Math.addExact(it, offset.text.trim().toLong()).coerceAtLeast(0) }
            val tuning = endpoint(receiver.isSelected && corrected != null, receiverHost, receiverPort, frequencyFormat.text.replace("\$FREQ", corrected.toString()))
            latest.set(Commands(rotation, tuning))
            if (!busy.compareAndSet(false, true)) return
            executor.execute {
                try {
                    // A single latest snapshot prevents a queue of obsolete pointing commands.
                    val commands = latest.getAndSet(null) ?: return@execute
                    fun write(endpoint: Endpoint?, existing: Socket?): Socket? {
                        if (endpoint == null) { existing?.close(); return null }
                        var socket = existing
                        if (socket == null || socket.isClosed || socket.inetAddress.hostAddress != java.net.InetAddress.getByName(endpoint.host).hostAddress || socket.port != endpoint.port) {
                            socket?.close(); socket = Socket().apply { connect(InetSocketAddress(endpoint.host, endpoint.port), 1500) }
                        }
                        socket.getOutputStream().apply { write(endpoint.command.toByteArray()); flush() }
                        return socket
                    }
                    rotationSocket = write(commands.rotation, rotationSocket)
                    frequencySocket = write(commands.frequency, frequencySocket)
                    SwingUtilities.invokeLater { status.text = if (commands.rotation == null && commands.frequency == null) "Remote control disabled" else "Remote commands sent" }
                } catch (error: Exception) {
                    runCatching { rotationSocket?.close() }; runCatching { frequencySocket?.close() }
                    rotationSocket = null; frequencySocket = null
                    SwingUtilities.invokeLater { status.text = "Remote error: ${error.message}" }
                } finally { busy.set(false) }
            }
        }.onFailure { status.text = "Remote settings: ${it.message}" }
    }

    override fun close() {
        listOf("rotatorHost" to rotatorHost, "rotatorPort" to rotatorPort, "receiverHost" to receiverHost,
            "receiverPort" to receiverPort, "rotationFormat" to rotationFormat, "frequencyFormat" to frequencyFormat, "offset" to offset)
            .forEach { (key, value) -> prefs.put(key, value.text) }
        executor.shutdownNow(); runCatching { rotationSocket?.close() }; runCatching { frequencySocket?.close() }
    }
}
