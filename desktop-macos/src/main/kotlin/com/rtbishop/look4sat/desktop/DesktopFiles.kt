/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.table.DefaultTableModel

internal fun readOnlyTable(vararg columns: String) = object : DefaultTableModel(columns, 0) {
    override fun isCellEditable(row: Int, column: Int) = false
}

internal fun applicationFile(name: String): Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Look4Sat-Mac", name)

internal fun exportCalendar(parent: Component, name: String, pass: Pass) {
    val picker = JFileChooser().apply { selectedFile = java.io.File("Look4Sat-pass.ics") }
    if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return
    if (picker.selectedFile.exists() && JOptionPane.showConfirmDialog(parent, "Replace ${picker.selectedFile.name}?", "Export calendar", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
    val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    fun stamp(time: Long) = format.format(Instant.ofEpochMilli(time))
    val escaped = name.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace(",", "\\,").replace(";", "\\;")
    runCatching {
        val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Look4Sat//Mac//EN", "BEGIN:VEVENT",
            "UID:${UUID.randomUUID()}@look4sat", "DTSTAMP:${stamp(System.currentTimeMillis())}",
            "DTSTART:${stamp(pass.aos)}", "DTEND:${stamp(pass.los)}", "SUMMARY:$escaped pass",
            "DESCRIPTION:Maximum elevation %.1f degrees".format(java.util.Locale.ROOT, pass.maximum), "END:VEVENT", "END:VCALENDAR")
        // RFC 5545 folds lines at 75 UTF-8 octets, preserving character boundaries.
        val folded = lines.map { line ->
            val out = StringBuilder(); var bytes = 0
            line.codePoints().forEach { point ->
                val text = String(Character.toChars(point)); val size = text.toByteArray(Charsets.UTF_8).size
                if (bytes + size > 75) { out.append("\r\n "); bytes = 1 }
                out.append(text); bytes += size
            }
            out.toString()
        }
        Files.writeString(picker.selectedFile.toPath(), folded.joinToString("\r\n", postfix = "\r\n"))
    }.onFailure { JOptionPane.showMessageDialog(parent, it.message, "Calendar export failed", JOptionPane.ERROR_MESSAGE) }
}
