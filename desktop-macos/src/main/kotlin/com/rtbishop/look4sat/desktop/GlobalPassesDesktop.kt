/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import java.awt.BorderLayout
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.prefs.Preferences
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

internal class GlobalPassesDesktop : JPanel(BorderLayout(6, 6)) {
    private val prefs = Preferences.userNodeForPackage(GlobalPassesDesktop::class.java)
    private var satellites = emptyList<OrbitalData>()
    private val chosen = prefs.get("chosen", "").split(',').mapNotNull { it.toIntOrNull() }.toMutableSet()
    private val picks = object : DefaultTableModel(arrayOf("Track", "Satellite", "NORAD"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0
    }
    private val results = readOnlyTable("Satellite", "AOS", "LOS", "Max elev.")
    private val label = JLabel("Select satellites to calculate upcoming passes")
    private val picksTable = JTable(picks)
    private val output = JTable(results)
    private val search = JTextField(12)
    private val modes = JTextField(8)
    private val deepSpace = JCheckBox("Deep space", prefs.getBoolean("deepSpace", true))
    private val startHour = JSpinner(SpinnerNumberModel(prefs.getInt("startHour", 0), 0, 23, 1))
    private val endHour = JSpinner(SpinnerNumberModel(prefs.getInt("endHour", 23), 0, 23, 1))
    private val invert = JCheckBox("Invert AOS window", prefs.getBoolean("invert", false))
    private var passes = emptyList<Pair<OrbitalData, Pass>>()
    private var worker: SwingWorker<List<Pair<OrbitalData, Pass>>, Unit>? = null
    var stationProvider: () -> GeoPos = { GeoPos(0.0, 0.0) }
    var predictionProvider: () -> Pair<Int, Double> = { 1 to 0.0 }
    var modeProvider: (Int) -> Set<String> = { emptySet() }
    var onSelectSatellite: (Int) -> Unit = {}
    var useUtc = false
        set(value) { field = value; display() }
    private val formatter = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")

    init {
        runCatching {
            val file = applicationFile("selected-satellites.txt")
            if (java.nio.file.Files.isRegularFile(file)) chosen.addAll(java.nio.file.Files.readString(file).split(',').mapNotNull { it.toIntOrNull() })
        }
        val calculate = JButton("Calculate selected passes"); val cancel = JButton("Cancel")
        val select = JButton("Select visible"); val clear = JButton("Clear visible"); val export = JButton("Export pass…")
        val heading = JPanel().apply {
            add(calculate); add(cancel); add(JLabel("Search")); add(search); add(select); add(clear)
            add(JLabel("Modes (comma separated)")); add(modes); add(deepSpace)
        }
        add(heading, BorderLayout.NORTH)
        val sorter = TableRowSorter(picks); picksTable.rowSorter = sorter
        search.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = filter()
            fun filter() { sorter.rowFilter = RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(search.text), 1, 2) }
        })
        fun choose(value: Boolean) {
            // Update the persisted set once; preferences values have an 8 KiB limit.
            for (row in 0 until picksTable.rowCount) {
                val index = picksTable.convertRowIndexToModel(row)
                picks.setValueAt(value, index, 0)
            }
        }
        select.addActionListener { choose(true) }; clear.addActionListener { choose(false) }
        val panes = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(picksTable), JScrollPane(output))
        panes.dividerLocation = 350; add(panes, BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout())
        bottom.add(JPanel().apply {
            add(JLabel("AOS hours")); add(startHour); add(JLabel("through")); add(endHour); add(invert); add(export)
        }, BorderLayout.NORTH)
        bottom.add(label, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH)
        calculate.addActionListener { calculate() }; cancel.addActionListener { worker?.cancel(true) }
        export.addActionListener {
            passes.getOrNull(output.selectedRow)?.let { (satellite, pass) -> exportCalendar(this, satellite.name, pass) }
        }
        output.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) passes.getOrNull(output.selectedRow)?.let { (sat, _) -> onSelectSatellite(sat.catnum) }
        }
        picks.addTableModelListener { event ->
            if (event.type != javax.swing.event.TableModelEvent.UPDATE || event.column != 0) return@addTableModelListener
            val id = picks.getValueAt(event.firstRow, 2).toString().toIntOrNull() ?: return@addTableModelListener
            if (picks.getValueAt(event.firstRow, 0) == true) chosen.add(id) else chosen.remove(id)
        }
    }

    fun updateSatellites(items: List<OrbitalData>) {
        worker?.cancel(true); passes = emptyList(); display()
        satellites = items; picks.rowCount = 0
        items.forEach { picks.addRow(arrayOf<Any?>(it.catnum in chosen, it.name, it.catnum.toString())) }
    }

    private fun display() {
        results.rowCount = 0
        val format = formatter.withZone(if (useUtc) ZoneOffset.UTC else ZoneId.systemDefault())
        passes.forEach { (sat, pass) -> results.addRow(arrayOf<Any?>(sat.name, format.format(Instant.ofEpochMilli(pass.aos)), format.format(Instant.ofEpochMilli(pass.los)), "%.1f°".format(pass.maximum))) }
    }

    private fun calculate() {
        worker?.cancel(true)
        if (picksTable.isEditing) picksTable.cellEditor.stopCellEditing()
        val requestedModes = modes.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val targets = satellites.filter { it.catnum in chosen && (deepSpace.isSelected || !it.isDeepSpace) &&
            (requestedModes.isEmpty() || modeProvider(it.catnum).any { mode -> requestedModes.any { it.equals(mode, true) } }) }
        if (targets.isEmpty()) { label.text = "Select satellites matching your filters"; return }
        val observer = runCatching { stationProvider() }.getOrElse { label.text = it.message ?: "Invalid station"; return }
        val (days, threshold) = predictionProvider()
        val firstHour = startHour.value as Int; val lastHour = endHour.value as Int
        val inverted = invert.isSelected; val zone = if (useUtc) ZoneOffset.UTC else ZoneId.systemDefault()
        passes = emptyList(); display(); label.text = "Calculating ${targets.size} satellites…"
        worker = object : SwingWorker<List<Pair<OrbitalData, Pass>>, Unit>() {
            override fun doInBackground(): List<Pair<OrbitalData, Pass>> {
                val start = System.currentTimeMillis()
                val calculated = mutableListOf<Pair<OrbitalData, Pass>>()
                for (satellite in targets) {
                    if (isCancelled) throw CancellationException("Cancelled")
                    calculated += predictPasses(satellite, observer, start, days, threshold).filter { pass ->
                        val hour = Instant.ofEpochMilli(pass.aos).atZone(zone).hour
                        val within = if (firstHour <= lastHour) hour in firstHour..lastHour else hour >= firstHour || hour <= lastHour
                        within != inverted
                    }.map { satellite to it }
                }
                return calculated.sortedBy { it.second.aos }
            }
            override fun done() {
                if (worker !== this) return
                if (isCancelled) { label.text = "Calculation cancelled"; return }
                runCatching { passes = get(); display(); label.text = "${results.rowCount} upcoming passes for ${targets.size} satellites" }
                    .onFailure { label.text = "Prediction error: ${it.cause?.message ?: it.message}" }
            }
        }.also { it.execute() }
    }

    fun close() {
        worker?.cancel(true)
        val file = applicationFile("selected-satellites.txt")
        runCatching { java.nio.file.Files.createDirectories(file.parent); java.nio.file.Files.writeString(file, chosen.joinToString(",")) }
        prefs.remove("chosen")
        prefs.putBoolean("deepSpace", deepSpace.isSelected); prefs.putBoolean("invert", invert.isSelected)
        prefs.putInt("startHour", startHour.value as Int); prefs.putInt("endHour", endHour.value as Int)
    }
}
