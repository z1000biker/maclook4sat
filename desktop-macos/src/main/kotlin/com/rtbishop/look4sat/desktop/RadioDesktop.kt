/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.data.framework.Ft817CatProtocol
import com.rtbishop.look4sat.core.data.framework.IcomCivProtocol
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import javax.swing.*

internal class RadioDesktop : JPanel(BorderLayout(8, 8)) {
    private val prefs = Preferences.userNodeForPackage(RadioDesktop::class.java)
    private val port = JComboBox<String>().apply { isEditable = true; selectedItem = prefs.get("port", "/dev/cu.usbserial") }
    private val txPort = JTextField(prefs.get("txPort", ""), 22)
    private val model = JComboBox(arrayOf("Yaesu FT-817/818", "Yaesu FT-857/897", "Icom IC-705"))
    private val baud = JComboBox(arrayOf("4800", "9600", "19200", "38400", "57600", "115200"))
    private val frequency = JTextField("145800000", 12)
    private val transmit = JTextField("", 12)
    private val tone = JTextField("", 8)
    private val split = JCheckBox("IC-705 split VFO")
    private val tracking = JCheckBox("Track Doppler automatically")
    private val mode = JComboBox(Ft817CatProtocol.MODE_TO_BYTE.keys.toTypedArray())
    private val status = JLabel("Disconnected")
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Look4Sat-CAT").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var txOutput: FileOutputStream? = null
    private var connectedIcom = false
    private var connectedSplit = false
    private var generation = 0

    init {
        val controls = JPanel(GridLayout(0, 2, 8, 8))
        val connect = JButton("Connect"); val disconnect = JButton("Disconnect")
        val scan = JButton("Find serial ports"); val tune = JButton("Set frequencies")
        val setMode = JButton("Set mode"); val setTone = JButton("Set CTCSS (blank = off)")
        controls.add(JLabel("Radio")); controls.add(model)
        controls.add(JLabel("RX serial port")); controls.add(port)
        controls.add(JLabel("TX serial port (optional second radio)")); controls.add(txPort)
        controls.add(JLabel("Baud")); controls.add(baud)
        controls.add(scan); controls.add(split); controls.add(connect); controls.add(disconnect)
        controls.add(JLabel("Downlink Hz")); controls.add(frequency)
        controls.add(JLabel("Uplink Hz")); controls.add(transmit)
        controls.add(tune); controls.add(tracking); controls.add(mode); controls.add(setMode)
        controls.add(tone); controls.add(setTone)
        add(controls, BorderLayout.NORTH); add(status, BorderLayout.SOUTH)
        scan.addActionListener {
            val previous = port.editor.item.toString()
            port.removeAllItems()
            File("/dev").listFiles()?.filter { it.name.startsWith("cu.") }?.sortedBy { it.name }?.forEach { port.addItem(it.absolutePath) }
            port.selectedItem = previous
        }
        connect.addActionListener { connect() }
        disconnect.addActionListener { disconnect() }
        model.addActionListener {
            mode.model = DefaultComboBoxModel((if (model.selectedItem == "Icom IC-705") IcomCivProtocol.MODE_TO_BYTE else Ft817CatProtocol.MODE_TO_BYTE).keys.toTypedArray())
        }
        tune.addActionListener { tune(null) }
        setMode.addActionListener {
            val chosen = mode.selectedItem.toString()
            command { out, _ -> out.write(requireNotNull(if (connectedIcom) IcomCivProtocol.buildSetModeCommand(chosen) else Ft817CatProtocol.buildSetModeCommand(chosen))) }
        }
        setTone.addActionListener {
            val hz = tone.text.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            if (tone.text.isNotBlank() && (hz == null || hz !in 60.0..260.0)) { status.text = "Enter a CTCSS tone from 60 to 260 Hz"; return@addActionListener }
            command { rx, tx ->
                val out = tx ?: rx
                out.write(if (connectedIcom) IcomCivProtocol.buildCtcssModeCommand(hz != null) else Ft817CatProtocol.buildCtcssModeCommand(hz != null))
                hz?.let { out.write(if (connectedIcom) IcomCivProtocol.buildSetCtcssToneCommand(it) else Ft817CatProtocol.buildSetCtcssToneCommand(it)) }
            }
        }
    }

    fun setFrequencies(rx: Long?, tx: Long?) {
        rx?.let { frequency.text = it.toString() }; transmit.text = tx?.toString().orEmpty()
        tracking.isSelected = false
    }

    fun updatePosition(position: OrbitalPos) { if (tracking.isSelected && output != null) tune(position) }

    private fun tune(position: OrbitalPos?) {
        val rx = frequency.text.trim().toLongOrNull()
        val tx = transmit.text.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (rx == null || rx !in 100_000L..500_000_000L || (transmit.text.isNotBlank() && (tx == null || tx !in 100_000L..500_000_000L))) {
            tracking.isSelected = false; status.text = "Enter valid RX/TX frequencies in Hz"; return
        }
        val receive = position?.getDownlinkFreq(rx) ?: rx
        val send = tx?.let { position?.getUplinkFreq(it) ?: it }
        command { out, txOut ->
            if (connectedIcom) {
                out.write(IcomCivProtocol.buildSetWorkingFreqCommand(receive))
                if (send != null) {
                    if (connectedSplit) out.write(IcomCivProtocol.buildSetUnselectedVfoFreqCommand(send))
                    else txOut?.write(IcomCivProtocol.buildSetWorkingFreqCommand(send))
                }
            } else {
                out.write(Ft817CatProtocol.buildSetFreqCommand(receive))
                if (send != null) txOut?.write(Ft817CatProtocol.buildSetFreqCommand(send))
            }
        }
    }

    private fun connect() {
        if (!pending.compareAndSet(false, true)) return
        disconnect()
        val serial = generation
        val name = port.editor.item.toString().trim(); val txName = txPort.text.trim()
        val rate = baud.selectedItem.toString(); val icom = model.selectedItem == "Icom IC-705"; val useSplit = split.isSelected
        prefs.put("port", name); prefs.put("txPort", txName)
        executor.execute {
            var rx: FileOutputStream? = null; var tx: FileOutputStream? = null
            try {
                fun open(path: String): FileOutputStream {
                    require(path.startsWith("/dev/cu.") && File(path).exists()) { "Select an existing macOS /dev/cu.* device" }
                    val process = ProcessBuilder("/bin/stty", "-f", path, rate, "raw", "-echo").redirectErrorStream(true).start()
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Serial configuration timed out") }
                    require(process.exitValue() == 0) { "Could not configure $path" }
                    return FileOutputStream(path)
                }
                require(txName.isEmpty() || txName != name) { "RX and TX ports must be different" }
                rx = open(name); if (txName.isNotEmpty()) tx = open(txName)
                if (icom && useSplit) { rx.write(IcomCivProtocol.buildEnterVfoModeCommand()); rx.write(IcomCivProtocol.buildSplitModeCommand(true)) }
                SwingUtilities.invokeLater {
                    if (serial != generation) { rx.close(); tx?.close() } else {
                        output = rx; txOutput = tx; connectedIcom = icom; connectedSplit = useSplit
                        status.text = "Connected · $name · $rate baud"
                    }
                }
            } catch (error: Exception) {
                runCatching { rx?.close() }; runCatching { tx?.close() }
                SwingUtilities.invokeLater { if (serial == generation) status.text = "CAT connection error: ${error.message}" }
            } finally { pending.set(false) }
        }
    }

    private fun command(send: (FileOutputStream, FileOutputStream?) -> Unit) {
        val rx = output ?: run { status.text = "Connect a CAT interface first"; return }
        val tx = txOutput
        if (!pending.compareAndSet(false, true)) return
        executor.execute {
            runCatching { send(rx, tx); rx.flush(); tx?.flush() }
                .onSuccess { SwingUtilities.invokeLater { status.text = "CAT commands sent" } }
                .onFailure { error -> SwingUtilities.invokeLater { tracking.isSelected = false; status.text = "CAT error: ${error.message}" } }
            pending.set(false)
        }
    }

    fun disconnect() {
        ++generation; tracking.isSelected = false
        runCatching { output?.close() }; runCatching { txOutput?.close() }
        output = null; txOutput = null; status.text = "Disconnected"
    }
}
