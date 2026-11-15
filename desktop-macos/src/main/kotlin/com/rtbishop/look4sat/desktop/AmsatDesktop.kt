/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import java.awt.BorderLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.prefs.Preferences
import javax.swing.*
import kotlinx.serialization.json.*

internal class AmsatDesktop : JPanel(BorderLayout(6, 6)) {
    private val model = readOnlyTable("Satellite", "Report", "Callsign", "Grid", "Reported UTC")
    private val status = JLabel("Download AMSAT status reports; saved reports remain available offline")
    private val table = JTable(model)
    private val prefs = Preferences.userNodeForPackage(AmsatDesktop::class.java)
    private val callsign = JTextField(prefs.get("callsign", ""), 10)
    private val grid = JTextField(prefs.get("grid", ""), 6)
    private val satellite = JTextField(10)
    private val report = JComboBox(arrayOf("Heard", "Telemetry Only", "Not Heard", "Crew Active"))
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private var worker: SwingWorker<String, Unit>? = null

    init {
        val refresh = JButton("Refresh AMSAT status")
        val submit = JButton("Submit report…")
        val controls = JPanel().apply {
            add(refresh); add(JLabel("Satellite")); add(satellite); add(report)
            add(JLabel("Callsign")); add(callsign); add(JLabel("Grid")); add(grid); add(submit)
        }
        add(controls, BorderLayout.NORTH)
        table.autoCreateRowSorter = true
        add(JScrollPane(table), BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && table.selectedRow >= 0) satellite.text = model.getValueAt(table.convertRowIndexToModel(table.selectedRow), 0).toString()
        }
        refresh.addActionListener {
            runRequest(false) {
                val request = HttpRequest.newBuilder(URI.create("https://www.amsat.org/status/api/v1/reports.php?hours=72&limit=500"))
                    .timeout(Duration.ofSeconds(40)).GET().build()
                val result = client.send(request, HttpResponse.BodyHandlers.ofString())
                require(result.statusCode() == 200) { "AMSAT HTTP ${result.statusCode()}" }
                result.body()
            }
        }
        submit.addActionListener {
            val call = callsign.text.trim().uppercase(); val locator = grid.text.trim().uppercase()
            val name = satellite.text.trim(); val selectedReport = report.selectedItem.toString()
            if (call.isEmpty() || name.isEmpty() || (locator.isNotEmpty() && !Regex("[A-R]{2}[0-9]{2}([A-X]{2})?").matches(locator))) {
                status.text = "Enter satellite, callsign, and a valid optional Maidenhead grid"; return@addActionListener
            }
            if (JOptionPane.showConfirmDialog(this, "Submit $selectedReport for $name as $call to AMSAT?", "Submit report", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return@addActionListener
            prefs.put("callsign", call); prefs.put("grid", locator)
            val body = buildJsonObject {
                put("name", name); put("report", selectedReport); put("callsign", call)
                put("reported_at", Instant.now().toString()); if (locator.isNotEmpty()) put("grid_square", locator)
            }.toString()
            runRequest(true) {
                val request = HttpRequest.newBuilder(URI.create("https://www.amsat.org/status/api/v1/reports.php"))
                    .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val result = client.send(request, HttpResponse.BodyHandlers.ofString())
                require(result.statusCode() in 200..299) { "AMSAT HTTP ${result.statusCode()}: ${result.body().take(200)}" }
                val error = (Json.parseToJsonElement(result.body()) as? JsonObject)?.get("error")
                require(error == null || error is JsonNull) { error.toString() }
                result.body()
            }
        }
        val file = applicationFile("amsat-reports.json")
        if (Files.isRegularFile(file)) runCatching { showReports(Files.readString(file)) }
    }

    private fun showReports(text: String) {
        val reports = Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: error("Invalid AMSAT response")
        model.rowCount = 0
        reports.forEach { entry ->
            val obj = entry.jsonObject
            model.addRow(arrayOf<Any?>("name", "report", "callsign", "grid_square", "reported_time").map { obj[it]?.jsonPrimitive?.content.orEmpty() }.toTypedArray())
        }
        status.text = "${model.rowCount} reports from the last 72 hours"
    }

    private fun runRequest(submit: Boolean, request: () -> String) {
        if (worker?.isDone == false) { status.text = "An AMSAT request is already running"; return }
        status.text = if (submit) "Submitting report…" else "Downloading reports…"
        worker = object : SwingWorker<String, Unit>() {
            override fun doInBackground() = request()
            override fun done() {
                runCatching {
                    val text = get()
                    if (submit) status.text = "Report submitted to AMSAT" else {
                        showReports(text)
                        val file = applicationFile("amsat-reports.json")
                        Files.createDirectories(file.parent); Files.writeString(file, text)
                    }
                }.onFailure { status.text = "AMSAT error: ${it.cause?.message ?: it.message}" }
            }
        }.also { it.execute() }
    }
}
