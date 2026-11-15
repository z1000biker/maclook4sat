/* GPL-3.0-or-later. SatNOGS transmitter catalog adapter for macOS. */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class SatnogsDesktop : JPanel(BorderLayout(6, 6)) {
    private val model = readOnlyTable("NORAD", "Description", "Mode", "Downlink Hz", "Uplink Hz")
    private val table = JTable(model)
    private val status = JLabel("SatNOGS transmitters not downloaded")
    private val rows = mutableListOf<Transmitter>()
    private var selectedCatnum: Int? = null
    private val receiveFrequency = JTextField("145800000", 12)
    private val calculation = JLabel("Select a linear transponder")
    var position: OrbitalPos? = null
    var onFrequencySelected: (Long?, Long?) -> Unit = { _, _ -> }
    private var downloaded: String? = null
    fun modesFor(catnum: Int): Set<String> = rows.filter { it.radio.catnum == catnum }.mapNotNull { it.radio.downlinkMode }.toSet()

    private data class Transmitter(
        val radio: SatRadio
    )

    init {
        val load = JButton("Download SatNOGS transmitters")
        val calculate = JButton("Calculate uplink")
        val import = JButton("Import transmitter JSON…")
        import.addActionListener {
            val picker = JFileChooser()
            if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) runCatching {
                val text = java.nio.file.Files.readString(picker.selectedFile.toPath())
                val parsed = parseCatalog(text)
                require(parsed.isNotEmpty()) { "No transmitter records found" }
                rows.clear(); rows.addAll(parsed); save(text); refresh()
            }.onFailure { status.text = "Import error: ${it.message}" }
        }
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && table.selectedRow >= 0) {
                val index = table.convertRowIndexToModel(table.selectedRow)
                val entry = rows.filter { it.radio.catnum == selectedCatnum }.getOrNull(index)?.radio
                onFrequencySelected(entry?.downlinkLow, entry?.uplinkLow)
            }
        }
        val controls = JPanel()
        controls.add(load); controls.add(import); controls.add(JLabel("RX Hz")); controls.add(receiveFrequency); controls.add(calculate)
        add(controls, BorderLayout.NORTH)
        table.autoCreateRowSorter = true
        add(JScrollPane(table), BorderLayout.CENTER)
        val bottom = JPanel(java.awt.GridLayout(2, 1))
        bottom.add(status); bottom.add(calculation)
        add(bottom, BorderLayout.SOUTH)
        load.addActionListener { download() }
        calculate.addActionListener { calculateUplink() }
        val cache = applicationFile("transmitters.json")
        if (java.nio.file.Files.isRegularFile(cache)) runCatching {
            rows.addAll(parseCatalog(java.nio.file.Files.readString(cache))); refresh()
        }.onFailure { status.text = "Transmitter cache error: ${it.message}" }
    }

    fun selectSatellite(catnum: Int?) {
        selectedCatnum = catnum
        refresh()
    }

    private fun refresh() {
        model.rowCount = 0
        rows.asSequence().filter { it.radio.catnum == selectedCatnum }.forEach { entry ->
            val it = entry.radio
            model.addRow(arrayOf<Any?>(it.catnum, it.info, it.downlinkMode, it.downlinkLow, it.uplinkLow))
        }
        status.text = "${model.rowCount} transmitter records for NORAD ${selectedCatnum ?: "—"}"
    }

    private fun calculateUplink() {
        val pos = position ?: run { calculation.text = "Select a satellite first"; return }
        val index = table.selectedRow.takeIf { it >= 0 }?.let { table.convertRowIndexToModel(it) }
            ?: run { calculation.text = "Select a transponder row"; return }
        val selected = rows.filter { it.radio.catnum == selectedCatnum }.getOrNull(index)?.radio
            ?: run { calculation.text = "Transponder not found"; return }
        val receivedHz = receiveFrequency.text.trim().toLongOrNull()
            ?: run { calculation.text = "Enter RX frequency in Hz"; return }
        val uplink = DopplerFrequencyCalculator.computeUplinkFromDownlink(receivedHz, selected, pos)
        calculation.text = if (uplink != null) "Doppler-corrected TX: $uplink Hz" else "Selected transponder has no complete linear passband"
    }

    private fun save(text: String) {
        val file = applicationFile("transmitters.json")
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, text)
    }

    private fun parseCatalog(text: String): List<Transmitter> {
                val root = Json.parseToJsonElement(text) as JsonArray
                fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.content?.takeIf { it != "null" }
                return root.mapNotNull { element ->
                    runCatching {
                        val obj = element as JsonObject
                        Transmitter(SatRadio(
                            uuid = obj.str("uuid") ?: "unknown", info = obj.str("description") ?: "",
                            catnum = obj.str("norad_cat_id")?.toIntOrNull(),
                            downlinkMode = obj.str("mode"), uplinkMode = obj.str("uplink_mode"),
                            downlinkLow = obj.str("downlink_low")?.toLongOrNull(),
                            downlinkHigh = obj.str("downlink_high")?.toLongOrNull(),
                            uplinkLow = obj.str("uplink_low")?.toLongOrNull(),
                            uplinkHigh = obj.str("uplink_high")?.toLongOrNull(),
                            isInverted = obj.str("invert") == "true"))
                    }.getOrNull()
                }
    }

    private fun download() {
        status.text = "Downloading SatNOGS transmitter catalog…"
        object : SwingWorker<List<Transmitter>, Unit>() {
            override fun doInBackground(): List<Transmitter> {
                val request = HttpRequest.newBuilder(
                    URI.create("https://db.satnogs.org/api/transmitters/?format=json&status=active")
                ).timeout(java.time.Duration.ofSeconds(45)).GET().build()
                val response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build().send(request, HttpResponse.BodyHandlers.ofString())
                require(response.statusCode() == 200) { "SatNOGS HTTP ${response.statusCode()}" }
                downloaded = response.body()
                return parseCatalog(response.body())
            }
            override fun done() {
                runCatching {
                    val parsed = get()
                    require(parsed.isNotEmpty()) { "Empty transmitter catalog" }
                    rows.clear(); rows.addAll(parsed)
                    downloaded?.let { save(it) }
                    refresh()
                }.onFailure { status.text = "SatNOGS error: ${it.cause?.message ?: it.message}" }
            }
        }.execute()
    }
}
