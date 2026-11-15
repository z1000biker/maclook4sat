/*
 * Look4Sat desktop adaptation. Copyright (C) 2026 contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.CelestialComputer
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.prefs.Preferences
import java.util.concurrent.CancellationException
import javax.swing.*
import javax.swing.table.DefaultTableModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val prefs = Preferences.userNodeForPackage(DesktopWindow::class.java)
private val stamp = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss").withZone(ZoneId.systemDefault())
private const val SOURCE_ROOT = "https://celestrak.org/NORAD/elements/gp.php?GROUP="

internal fun parseElements(text: String): List<OrbitalData> {
    val first = text.trimStart('\uFEFF').lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
    return if (first.contains("NORAD_CAT_ID") && first.contains("EPOCH")) parseOmmCsv(text.trimStart('\uFEFF')) else parseTle(text)
}

private fun csvFields(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val char = line[i]
        if (char == '"') {
            if (quoted && i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ }
            else quoted = !quoted
        } else if (char == ',' && !quoted) { result += current.toString(); current.clear() }
        else current.append(char)
        i++
    }
    result += current.toString()
    return result
}

private fun parseOmmCsv(text: String): List<OrbitalData> {
    val rows = text.lineSequence().filter { it.isNotBlank() }.iterator()
    if (!rows.hasNext()) return emptyList()
    val columns = csvFields(rows.next()).mapIndexed { index, column -> column.trim().uppercase() to index }.toMap()
    val needed = listOf("OBJECT_NAME", "EPOCH", "MEAN_MOTION", "ECCENTRICITY", "INCLINATION",
        "RA_OF_ASC_NODE", "ARG_OF_PERICENTER", "MEAN_ANOMALY", "NORAD_CAT_ID")
    require(needed.all { it in columns }) { "Invalid OMM CSV header" }
    val parsed = mutableListOf<OrbitalData>()
    while (rows.hasNext()) {
        val values = csvFields(rows.next())
        fun get(key: String) = values[columns.getValue(key)].trim()
        fun optional(key: String) = columns[key]?.let { values.getOrNull(it)?.trim()?.toDoubleOrNull() } ?: 0.0
        runCatching {
            val epochText = get("EPOCH").removeSuffix("Z")
            val dt = LocalDateTime.parse(epochText)
            val year = dt.year
            val day = dt.dayOfYear + (dt.toLocalTime().toNanoOfDay() / 86_400_000_000_000.0)
            OrbitalData(get("OBJECT_NAME"), (year % 100) * 1000 + day,
                get("MEAN_MOTION").toDouble(), get("ECCENTRICITY").toDouble(),
                get("INCLINATION").toDouble(), get("RA_OF_ASC_NODE").toDouble(),
                get("ARG_OF_PERICENTER").toDouble(), get("MEAN_ANOMALY").toDouble(),
                parseAlpha5(get("NORAD_CAT_ID")), optional("BSTAR"), optional("MEAN_MOTION_DOT"))
        }.onSuccess(parsed::add)
    }
    return parsed.filter(::validElements).distinctBy { it.catnum }
}

fun main() = SwingUtilities.invokeLater {
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    DesktopWindow().isVisible = true
}

private fun parseAlpha5(value: String): Int {
    val text = value.trim().uppercase(Locale.ROOT)
    if (text.first().isDigit()) return text.toInt()
    val prefix = "ABCDEFGHJKLMNPQRSTUVWXYZ".indexOf(text.first())
    require(prefix >= 0) { "Invalid Alpha-5 catalog ID: $text" }
    return (prefix + 10) * 10000 + text.drop(1).toInt()
}

private fun parseTle(text: String): List<OrbitalData> {
    val lines = text.lineSequence().map { it.trimEnd('\r').trimEnd() }.filter { it.isNotBlank() }.toList()
    val results = mutableListOf<OrbitalData>()
    var index = 0
    while (index < lines.size) {
        val named = !lines[index].startsWith("1 ")
        if (named && index + 2 >= lines.size) break
        val name = if (named) lines[index].removePrefix("0 ").trim() else "NORAD ${lines[index].drop(2).take(5).trim()}"
        val one = lines.getOrNull(index + if (named) 1 else 0)
        val two = lines.getOrNull(index + if (named) 2 else 1)
        if (one == null || two == null || !one.startsWith("1 ") || !two.startsWith("2 ")) {
            index++
            continue
        }
        runCatching {
            require(parseAlpha5(one.substring(2, 7)) == parseAlpha5(two.substring(2, 7))) { "Mismatched TLE catalog IDs" }
            OrbitalData(name, one.substring(18, 32).trim().toDouble(),
                two.substring(52, 63).trim().toDouble(),
                "0.${two.substring(26, 33).trim()}".toDouble(),
                two.substring(8, 16).trim().toDouble(), two.substring(17, 25).trim().toDouble(),
                two.substring(34, 42).trim().toDouble(), two.substring(43, 51).trim().toDouble(),
                parseAlpha5(one.substring(2, 7)),
                run {
                    val mantissa = one.substring(53, 59).trim()
                    val exponent = one.substring(59, 61).trim()
                    if (mantissa.isEmpty()) 0.0 else (mantissa.toDouble() / 1e5) *
                        Math.pow(10.0, exponent.toDouble())
                }, one.substring(33, 43).trim().toDoubleOrNull() ?: 0.0)
        }.onSuccess(results::add)
        index += if (named) 3 else 2
    }
    return results.filter(::validElements).distinctBy { it.catnum }
}

private fun validElements(data: OrbitalData): Boolean =
    data.catnum > 0 && data.meanmo.isFinite() && data.meanmo > 0 && data.eccn in 0.0..<1.0 &&
        data.incl in 0.0..180.0 && listOf(data.epoch, data.raan, data.argper, data.meanan, data.bstar, data.ndot).all { it.isFinite() }

internal data class Pass(val aos: Long, val los: Long, val maximum: Double)

internal fun predictPasses(data: OrbitalData, station: GeoPos, from: Long, days: Int, minElevation: Double): List<Pass> {
    val orbit = data.getObject()
    val until = from + days.coerceIn(1, 10) * 24L * 60 * 60 * 1000
    val step = 15_000L
    fun elevation(at: Long) = orbit.getElevation(station, at) * 180.0 / PI
    fun crossing(low: Long, high: Long, rising: Boolean): Long {
        var a = low
        var b = high
        repeat(18) {
            val mid = a + (b - a) / 2
            if ((elevation(mid) >= 0) == rising) b = mid else a = mid
        }
        return b
    }
    var time = from
    var previous = elevation(time)
    var aos: Long? = if (previous >= 0) from else null
    var max = previous.coerceAtLeast(0.0)
    var peakTime = from
    fun refinedMaximum(end: Long): Double {
        var left = maxOf(aos ?: from, peakTime - step)
        var right = minOf(end, peakTime + step)
        repeat(24) {
            val one = left + (right - left) / 3
            val two = right - (right - left) / 3
            if (elevation(one) < elevation(two)) left = one else right = two
        }
        return maxOf(max, elevation(left + (right - left) / 2))
    }
    val passes = mutableListOf<Pass>()
    while (time < until) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Pass search cancelled")
        val next = minOf(time + step, until)
        val current = elevation(next)
        if (previous < 0 && current >= 0) {
            aos = crossing(time, next, true)
            max = current; peakTime = next
        } else if (previous >= 0 && current < 0) {
            val los = crossing(time, next, false)
            max = refinedMaximum(los)
            aos?.let { if (max >= minElevation) passes += Pass(it, los, max) }
            aos = null
        } else if (aos != null && current > max) { max = current; peakTime = next }
        time = next
        previous = current
    }
    aos?.let { max = refinedMaximum(until); if (max >= minElevation) passes += Pass(it, until, max) }
    return passes
}

internal class Radar : JPanel() {
    var orientation: CompassReading? = null
        set(value) { field = value; repaint() }
    var path: List<OrbitalPos> = emptyList()
        set(value) { field = value; repaint() }
    var position: OrbitalPos? = null
        set(value) { field = value; repaint() }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val canvas = g.create() as Graphics2D
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = (minOf(width, height) / 2 - 50).coerceAtLeast(1)
        val heading = orientation?.heading ?: 0.0
        fun angle(radians: Double) = radians - Math.toRadians(heading)
        val cx = width / 2
        val cy = height / 2
        canvas.color = Color(65, 100, 125)
        for (n in 1..3) canvas.drawOval(cx - r * n / 3, cy - r * n / 3, 2 * r * n / 3, 2 * r * n / 3)
        canvas.drawLine(cx - r, cy, cx + r, cy)
        canvas.drawLine(cx, cy - r, cx, cy + r)
        canvas.color = foreground
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, degrees) ->
            val az = angle(Math.toRadians(degrees))
            canvas.drawString(label, cx + (sin(az) * (r + 15)).toInt() - 5, cy - (cos(az) * (r + 15)).toInt() + 5)
        }
        canvas.color = Color(220, 170, 60)
        var previous: Point? = null
        path.filter { it.elevation >= 0 }.forEach {
            val distance = (1 - it.elevation * 180 / PI / 90) * r
            val point = Point(cx + (sin(angle(it.azimuth)) * distance).toInt(), cy - (cos(angle(it.azimuth)) * distance).toInt())
            previous?.let { old -> canvas.drawLine(old.x, old.y, point.x, point.y) }
            previous = point
        }
        position?.let {
            val distance = ((90.0 - it.elevation * 180 / PI) / 90.0).coerceIn(0.0, 1.0) * r
            val az = angle(it.azimuth)
            val x = cx + (sin(az) * distance).toInt()
            val y = cy - (cos(az) * distance).toInt()
            canvas.color = if (it.elevation >= 0) Color(35, 175, 100) else Color(180, 105, 75)
            canvas.fillOval(x - 7, y - 7, 14, 14)
        }
        orientation?.let { reading ->
            canvas.color = Color(70, 160, 255)
            val pointerY = cy - (((90 - (reading.pitch ?: 0.0)) / 90).coerceIn(0.0, 1.0) * r).toInt()
            canvas.drawLine(cx - 9, pointerY, cx + 9, pointerY)
            canvas.drawLine(cx, pointerY - 9, cx, pointerY + 9)
            canvas.drawString("Heading %.1f°%s".format(reading.heading,
                reading.pitch?.let { " · tilt %.1f°".format(it) } ?: " · heading only"), 10, 18)
            position?.let { target ->
                val turn = pointingDifference(Math.toDegrees(target.azimuth), reading.heading)
                val tilt = reading.pitch?.let { Math.toDegrees(target.elevation) - it }
                val horizontal = if (kotlin.math.abs(turn) < 1) "Heading aligned" else
                    "Turn %s %.1f°".format(if (turn > 0) "right" else "left", kotlin.math.abs(turn))
                val vertical = tilt?.let { if (kotlin.math.abs(it) < 1) " · tilt aligned" else
                    " · %s %.1f°".format(if (it > 0) "raise" else "lower", kotlin.math.abs(it)) }.orEmpty()
                canvas.drawString(horizontal + vertical, 10, height - 12)
            }
        }
        canvas.dispose()
    }
}

/** Offline Web Mercator map using the original bundled map tiles. */
internal class GroundMap : JPanel() {
    private val world = java.awt.image.BufferedImage(1024, 1024, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
        val g = createGraphics()
        GroundMap::class.java.getResource("/world.png")?.let { g.drawImage(javax.imageio.ImageIO.read(it), 0, 0, 1024, 1024, null) }
        for (x in 0..3) for (y in 0..3) {
            GroundMap::class.java.getResource("/tiles/2/$x/$y.png")?.let { g.drawImage(javax.imageio.ImageIO.read(it), x * 256, y * 256, null) }
        }
        g.dispose()
    }
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var lastMouse: Point? = null
    init {
        addMouseWheelListener {
            val next = (zoom * Math.pow(1.2, -it.preciseWheelRotation)).coerceIn(1.0, 8.0)
            panX = it.x - (it.x - panX) * next / zoom
            panY = it.y - (it.y - panY) * next / zoom
            zoom = next
            if (zoom == 1.0) { panX = 0.0; panY = 0.0 }
            repaint()
        }
        val mouse = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) { lastMouse = e.point }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                lastMouse?.let { panX += e.x - it.x; panY += e.y - it.y }
                lastMouse = e.point; repaint()
            }
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) { zoom = 1.0; panX = 0.0; panY = 0.0; repaint() }
            }
        }
        addMouseListener(mouse); addMouseMotionListener(mouse)
        toolTipText = "Scroll to zoom, drag to pan, double-click to reset"
    }
    var station: GeoPos? = null
    private fun project(lat: Double, lon: Double): Pair<Int, Int> {
        val x = (((lon + 180) % 360 + 360) % 360 / 360 * width).toInt()
        val radians = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(PI / 4 + radians / 2)) / PI) / 2 * height).toInt()
        return x to y
    }
    private fun drawPath(g: Graphics2D, points: List<Pair<Double, Double>>) {
        var previous: Pair<Int, Int>? = null
        points.forEach { (lat, lon) ->
            val point = project(lat, lon)
            previous?.let { if (kotlin.math.abs(point.first - it.first) < width / 2)
                g.drawLine(it.first, it.second, point.first, point.second) }
            previous = point
        }
    }
    var position: OrbitalPos? = null
        set(value) { field = value; repaint() }
    var track: List<Pair<Double, Double>> = emptyList()
        set(value) { field = value; repaint() }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(18, 43, 66)
        g.fillRect(0, 0, width, height)
        val baseTransform = g.transform
        g.translate(panX, panY); g.scale(zoom, zoom)
        g.drawImage(world, 0, 0, width, height, null)
        g.color = Color(247, 181, 69)
        drawPath(g, track)
        station?.let {
            val (x, y) = project(it.latitude, it.longitude)
            g.color = Color.RED
            g.fillOval(x - 5, y - 5, 10, 10)
            g.drawString("Station", x + 8, y)
        }
        position?.let {
            val lat = it.latitude * 180 / PI
            var lon = it.longitude * 180 / PI
            while (lon > 180.0) lon -= 360.0
            while (lon < -180.0) lon += 360.0
            g.color = Color(50, 160, 100)
            drawPath(g, it.getRangeCircle().map { point -> point.latitude to point.longitude })
            val (x, y) = project(lat, lon)
            g.color = Color(74, 228, 150)
            g.fillOval(x - 7, y - 7, 14, 14)
            g.drawString("%.2f°, %.2f°".format(lat, lon), (x + 10).coerceAtMost(width - 120), (y - 10).coerceAtLeast(36))
        }
        g.transform = baseTransform
        g.color = Color.WHITE
        g.drawString("© OpenStreetMap contributors · offline tiles", 8, height - 10)
        g.dispose()
    }
}

private class DesktopWindow : JFrame("Look4Sat · macOS") {
    private var satellites = emptyList<OrbitalData>()
    private var filtered = emptyList<OrbitalData>()
    private val filter = JTextField(17)
    private val latitude = JTextField(prefs.get("lat", "37.943"), 8)
    private val longitude = JTextField(prefs.get("lon", "23.647"), 8)
    private val altitude = JTextField(prefs.get("alt", "0"), 5)
    private val downlink = JTextField(prefs.get("downlink", "145800000"), 10)
    private val status = JLabel("Import TLE data or click Download")
    private val information = JTextArea()
    private val radar = Radar()
    private val listModel = DefaultListModel<String>()
    private val list = JList(listModel)
    private val passes = readOnlyTable("AOS", "LOS", "Max elevation")
    private val passTable = JTable(passes)
    private val days = JSpinner(SpinnerNumberModel(prefs.getInt("passDays", 1), 1, 10, 1))
    private val minElevation = JSpinner(SpinnerNumberModel(prefs.getInt("minimumElevation", 0), 0, 90, 1))
    private val tabs = JTabbedPane()
    private val statusDetails = JTextArea()
    private val map = GroundMap()
    private val sstv = SstvDesktop()
    private val radio = RadioDesktop()
    private val transmitters = SatnogsDesktop()
    private val allSatellites = readOnlyTable("Satellite", "NORAD", "Period", "Inclination")
    private val globalPasses = GlobalPassesDesktop()
    private val utc = JCheckBox("Display UTC", prefs.getBoolean("utc", false))
    private val qth = JTextField(8)
    private val customSource = JTextField(prefs.get("customSource", ""), 32)
    private val autoUpdate = JCheckBox("Refresh weekly on startup", prefs.getBoolean("autoUpdate", false))
    private val amsat = AmsatDesktop()
    private val remote = RemoteDesktop()
    private val compass = CompassDesktop { radar.orientation = it }
    private var currentPasses = emptyList<Pass>()
    private var passWorker: SwingWorker<List<Pass>, Unit>? = null
    private var trackWorker: SwingWorker<List<Pair<Double, Double>>, Unit>? = null
    private var downloadWorker: SwingWorker<String, Unit>? = null
    private var passSerial = 0L
    private var lastPrediction = 0L
    private var lastTrack = 0L
    private var radarWorker: SwingWorker<List<OrbitalPos>, Unit>? = null
    private val timer = Timer(1000) { updatePosition() }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(900, 580)
        size = Dimension(1120, 720)
        setLocationRelativeTo(null)
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        val source = JComboBox(arrayOf("amateur", "active", "stations", "weather", "visual", "cubesat"))
        val download = JButton("Download orbital data")
        val import = JButton("Import TLE / OMM…")
        toolbar.add(source)
        toolbar.add(download)
        toolbar.add(import)
        toolbar.add(JLabel("Filter:"))
        toolbar.add(filter)
        toolbar.add(JLabel("Lat:"))
        toolbar.add(latitude)
        toolbar.add(JLabel("Lon:"))
        toolbar.add(longitude)
        toolbar.add(JLabel("Alt m:"))
        toolbar.add(altitude)
        val refresh = JButton("Calculate passes")
        toolbar.add(refresh)

        val left = JPanel(BorderLayout(6, 6))
        left.add(JLabel("Satellites"), BorderLayout.NORTH)
        left.add(JScrollPane(list), BorderLayout.CENTER)
        val right = JPanel(BorderLayout(8, 8))
        radar.preferredSize = Dimension(390, 370)
        information.isEditable = false
        information.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        information.background = UIManager.getColor("Panel.background")
        val top = JPanel(BorderLayout())
        top.add(radar, BorderLayout.CENTER)
        val details = JPanel(BorderLayout())
        details.add(JLabel("Downlink (Hz):"), BorderLayout.WEST)
        details.add(downlink, BorderLayout.EAST)
        val lower = JPanel(BorderLayout(4, 4))
        lower.add(details, BorderLayout.NORTH)
        lower.add(JScrollPane(information), BorderLayout.CENTER)
        lower.preferredSize = Dimension(380, 180)
        top.add(lower, BorderLayout.EAST)
        right.add(top, BorderLayout.CENTER)
        val passPanel = JPanel(BorderLayout())
        passPanel.add(JLabel("Selected satellite passes"), BorderLayout.NORTH)
        passPanel.add(JScrollPane(JTable(passes)), BorderLayout.CENTER)
        passPanel.preferredSize = Dimension(650, 175)
        right.add(passPanel, BorderLayout.SOUTH)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.dividerLocation = 260
        tabs.addTab("Radar", split)
        val all = JTable(allSatellites)
        all.autoCreateRowSorter = true
        all.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && all.selectedRow >= 0) {
                val id = allSatellites.getValueAt(all.convertRowIndexToModel(all.selectedRow), 1) as Int
                filter.text = ""
                list.selectedIndex = filtered.indexOfFirst { it.catnum == id }
            }
        }
        tabs.addTab("Satellites", JScrollPane(all))
        tabs.addTab("Passes", JScrollPane(passTable).also { it.border = BorderFactory.createTitledBorder("Selected satellite · configured prediction period") })
        tabs.addTab("Upcoming", globalPasses)
        tabs.addTab("Map", map)
        statusDetails.isEditable = false
        statusDetails.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        tabs.addTab("Status", JScrollPane(statusDetails))
        tabs.addTab("SSTV", sstv)
        tabs.addTab("Transceivers", radio)
        tabs.addTab("Remote control", remote)
        tabs.addTab("Compass", JScrollPane(compass))
        tabs.addTab("SatNOGS", transmitters)
        tabs.addTab("AMSAT status", amsat)
        val settingPanel = JPanel(GridLayout(0, 2, 12, 12))
        settingPanel.add(JLabel("Ground station: coordinates in toolbar"))
        settingPanel.add(JLabel("Saved automatically on exit"))
        settingPanel.add(JLabel("Days to predict (1–10)"))
        settingPanel.add(days)
        settingPanel.add(JLabel("Minimum max elevation (degrees)"))
        settingPanel.add(minElevation)
        settingPanel.add(utc)
        settingPanel.add(JLabel("Times use chosen time zone"))
        settingPanel.add(qth)
        val applyQth = JButton("Set Maidenhead locator")
        settingPanel.add(applyQth)
        applyQth.addActionListener {
            val locator = qth.text.trim().uppercase(Locale.ROOT)
            if (!Regex("[A-R]{2}[0-9]{2}([A-X]{2})?").matches(locator)) {
                error(IllegalArgumentException("Enter a 4 or 6 character Maidenhead locator"))
            } else {
                val pos = com.rtbishop.look4sat.core.domain.utility.qthToPosition(if (locator.length == 4) locator + "MM" else locator)!!
                latitude.text = pos.latitude.toString(); longitude.text = pos.longitude.toString()
                updateStation()
            }
        }
        settingPanel.add(JLabel("Custom TLE / OMM URL")); settingPanel.add(customSource)
        val customDownload = JButton("Download custom source")
        settingPanel.add(customDownload); settingPanel.add(autoUpdate)
        customDownload.addActionListener {
            val address = customSource.text.trim()
            if (runCatching { URI.create(address).scheme == "https" }.getOrDefault(false)) {
                prefs.put("customSource", address); downloadTle("custom", address)
            } else error(IllegalArgumentException("Enter an HTTPS orbital data URL"))
        }
        autoUpdate.addActionListener { prefs.putBoolean("autoUpdate", autoUpdate.isSelected) }
        val clear = JButton("Clear satellite data…")
        settingPanel.add(clear)
        clear.addActionListener {
            if (JOptionPane.showConfirmDialog(this, "Clear the satellite catalog and orbital cache?", "Clear data", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                runCatching { Files.deleteIfExists(cachePath()) }.onSuccess {
                    downloadWorker?.cancel(true)
                    satellites = emptyList(); allSatellites.rowCount = 0; globalPasses.updateSatellites(emptyList()); applyFilter()
                    status.text = "Satellite data cleared"
                }.onFailure { error(it) }
            }
        }
        val appearance = AppearanceDesktop(this)
        settingPanel.add(appearance)
        tabs.addTab("Settings", JScrollPane(settingPanel))
        contentPane.layout = BorderLayout(5, 5)
        contentPane.add(JScrollPane(toolbar).apply { preferredSize = Dimension(100, 65) }, BorderLayout.NORTH)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(status, BorderLayout.SOUTH)

        transmitters.onFrequencySelected = { rx, tx ->
            rx?.let { downlink.text = it.toString() }
            radio.setFrequencies(rx, tx)
        }
        download.addActionListener { downloadTle(source.selectedItem.toString()) }
        import.addActionListener {
            val picker = JFileChooser()
            if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                runCatching { loadTle(Files.readString(picker.selectedFile.toPath())); saveElements() }
                    .onFailure { error(it) }
            }
        }
        filter.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
        list.addListSelectionListener { if (!it.valueIsAdjusting) {
            updatePosition()
            calculatePasses()
            calculateTrack()
            transmitters.selectSatellite(selected()?.catnum)
        } }
        passTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) calculateRadarPath() }
        refresh.addActionListener { updateStation() }
        listOf(latitude, longitude, altitude).forEach { it.addActionListener { updateStation() } }
        days.addChangeListener { calculatePasses() }
        minElevation.addChangeListener { calculatePasses() }
        utc.addActionListener { prefs.putBoolean("utc", utc.isSelected); calculatePasses(); globalPasses.useUtc = utc.isSelected }
        globalPasses.useUtc = utc.isSelected
        globalPasses.modeProvider = transmitters::modesFor
        globalPasses.onSelectSatellite = { id ->
            filter.text = ""; list.selectedIndex = filtered.indexOfFirst { it.catnum == id }
        }
        val radarPassTable = (passPanel.getComponent(1) as JScrollPane).viewport.view as JTable
        radarPassTable.selectionModel = passTable.selectionModel
        val export = JButton("Export selected pass to Calendar…")
        passPanel.add(export, BorderLayout.SOUTH)
        export.addActionListener {
            val index = passTable.selectedRow
            val pass = currentPasses.getOrNull(index) ?: return@addActionListener
            val sat = selected() ?: return@addActionListener
            exportCalendar(this, sat.name, pass)
        }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                timer.stop()
                downloadWorker?.cancel(true)
                passWorker?.cancel(true)
                trackWorker?.cancel(true)
                radarWorker?.cancel(true)
                sstv.close()
                radio.disconnect()
                remote.close()
                compass.close()
                globalPasses.close()
                prefs.putInt("selected", selected()?.catnum ?: 0)
                prefs.put("lat", latitude.text)
                prefs.put("lon", longitude.text)
                prefs.put("alt", altitude.text)
                prefs.put("downlink", downlink.text)
                prefs.putInt("passDays", days.value as Int)
                prefs.putInt("minimumElevation", minElevation.value as Int)
            }
        })
        appearance.apply()
        timer.start()
        val cached = cachePath()
        if (Files.isRegularFile(cached)) runCatching { loadTle(Files.readString(cached)) }.onFailure { status.text = "Cache error: ${it.message}" }
        filtered.indexOfFirst { it.catnum == prefs.getInt("selected", 0) }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        if (autoUpdate.isSelected && (!Files.isRegularFile(cached) || System.currentTimeMillis() - Files.getLastModifiedTime(cached).toMillis() > 7 * 86400_000L)) downloadTle("active")
    }

    private fun updateStation() {
        runCatching { observer() }.onFailure { error(it) }.onSuccess {
            updatePosition(); calculatePasses(); calculateTrack()
            prefs.put("lat", latitude.text); prefs.put("lon", longitude.text); prefs.put("alt", altitude.text)
        }
    }

    private fun cachePath(): Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Look4Sat-Mac", "amateur.tle")

    private fun observer(): GeoPos {
        val lat = latitude.text.trim().toDouble()
        val lon = longitude.text.trim().toDouble()
        val alt = altitude.text.trim().toDouble()
        require(lat in -90.0..90.0 && lon in -180.0..180.0 && alt.isFinite()) { "Invalid station coordinates" }
        return GeoPos(lat, lon, alt)
    }

    private fun selected(): OrbitalData? = filtered.getOrNull(list.selectedIndex)

    private fun loadTle(text: String) {
        val parsed = parseElements(text)
        require(parsed.isNotEmpty()) { "No valid TLE/OMM elements found" }
        satellites = (satellites + parsed).associateBy { it.catnum }.values.sortedBy { it.name }
        globalPasses.updateSatellites(satellites)
        globalPasses.stationProvider = { observer() }
        globalPasses.predictionProvider = { days.value as Int to (minElevation.value as Int).toDouble() }
        while (allSatellites.rowCount > 0) allSatellites.removeRow(0)
        satellites.forEach { allSatellites.addRow(arrayOf<Any?>(it.name, it.catnum, "%.1f min".format(it.orbitalPeriod), "%.2f°".format(it.incl))) }
        applyFilter()
        status.text = "Loaded ${satellites.size} satellites · ${stamp.format(Instant.now())}"
    }

    private fun applyFilter() {
        val previous = selected()?.catnum
        filtered = satellites.filter { it.name.contains(filter.text.trim(), ignoreCase = true) || it.catnum.toString().contains(filter.text.trim()) }
        listModel.clear()
        filtered.forEach { listModel.addElement("${it.name}  [${it.catnum}]") }
        if (filtered.isEmpty()) {
            passWorker?.cancel(true); trackWorker?.cancel(true); ++passSerial
            passes.rowCount = 0; currentPasses = emptyList()
            radar.path = emptyList(); radarWorker?.cancel(true)
            radar.position = null; map.position = null; map.track = emptyList()
            information.text = ""; statusDetails.text = ""; transmitters.selectSatellite(null)
        }
        if (filtered.isNotEmpty()) list.selectedIndex = filtered.indexOfFirst { it.catnum == previous }.takeIf { it >= 0 } ?: 0
    }

    private fun saveElements() {
        val path = cachePath()
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "elements-", ".csv")
        Files.writeString(temporary, satellites.joinToString("\n", prefix = "OBJECT_NAME,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,NORAD_CAT_ID,BSTAR,MEAN_MOTION_DOT\n") { element ->
            val year = (element.epoch / 1000).toInt().let { if (it < 57) it + 2000 else it + 1900 }
            val day = element.epoch % 1000
            val epoch = LocalDate.ofYearDay(year, day.toInt()).atStartOfDay().plusNanos(((day % 1) * 86_400_000_000_000).toLong())
            "\"${element.name.replace("\"", "\"\"")}\",$epoch,${element.meanmo},${element.eccn},${element.incl},${element.raan},${element.argper},${element.meanan},${element.catnum},${element.bstar},${element.ndot}"
        })
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun downloadTle(group: String, address: String = "${SOURCE_ROOT}${group}&FORMAT=csv") {
        status.text = "Downloading $group OMM data from CelesTrak…"
        downloadWorker?.cancel(true)
        downloadWorker = object : SwingWorker<String, Unit>() {
            override fun doInBackground(): String {
                val request = HttpRequest.newBuilder(URI.create(address)).timeout(java.time.Duration.ofSeconds(45)).GET().build()
                val response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build().send(request, HttpResponse.BodyHandlers.ofString())
                require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
                return response.body()
            }
            override fun done() {
                if (isCancelled || downloadWorker !== this) return
                runCatching {
                    val body = get()
                    loadTle(body)
                    saveElements()
                }.onFailure { error(it) }
            }
        }.also { it.execute() }
    }

    private fun updatePosition() {
        val data = selected() ?: return
        runCatching {
            val now = System.currentTimeMillis()
            if (now - lastPrediction > 60_000 && passWorker?.isDone != false) calculatePasses()
            if (now - lastTrack > 60_000 && trackWorker?.isDone != false) calculateTrack()
            val station = observer()
            val pos = data.getObject().getPosition(station, now)
            val sun = CelestialComputer.getSunPosition(station, now)
            val moon = CelestialComputer.getMoonPosition(station, now)
            map.station = observer()
            radar.position = pos
            map.position = pos
            transmitters.position = pos
            val hz = downlink.text.trim().toLongOrNull()
            remote.update(pos, hz?.let { pos.getDownlinkFreq(it) })
            radio.updatePosition(pos)
            information.text = buildString {
                appendLine("${data.name} · NORAD ${data.catnum}")
                appendLine("UTC: ${Instant.now()}")
                appendLine("Azimuth:    %.2f°".format(pos.azimuth * 180 / PI))
                appendLine("Elevation:  %.2f°".format(pos.elevation * 180 / PI))
                appendLine("Range:      %.1f km".format(pos.distance))
                appendLine("Altitude:   %.1f km".format(pos.altitude))
                appendLine("Range rate: %.3f km/s".format(pos.distanceRate))
                currentPasses.firstOrNull { it.los > now }?.let { pass ->
                    val active = now >= pass.aos
                    val remaining = ((if (active) pass.los else pass.aos) - now) / 1000
                    appendLine("${if (active) "LOS" else "AOS"} in %02d:%02d:%02d".format(remaining / 3600, remaining / 60 % 60, remaining % 60))
                }
                if (hz != null && hz > 0) appendLine("RX Doppler:  ${pos.getDownlinkFreq(hz)} Hz")
            }
            statusDetails.text = information.text +
                "\nEpoch: ${data.epoch}\nPeriod: %.2f min\nInclination: %.2f°".format(data.orbitalPeriod, data.incl) +
                "\nVisibility: ${CelestialComputer.classifyVisibility(pos.eclipsed, sun.elevation, pos.elevation * 180 / PI)}" +
                "\nSun az/el: %.2f° / %.2f°\nMoon az/el: %.2f° / %.2f°".format(sun.azimuth, sun.elevation, moon.azimuth, moon.elevation) +
                "\nMoon radial velocity: %.1f m/s".format(moon.radialVelocity) +
                if (data.hasDecayed(now)) "\nWARNING: orbital elements indicate decay" else ""
        }.onFailure { status.text = "Position error: ${it.message}" }
    }

    private fun calculatePasses() {
        lastPrediction = System.currentTimeMillis()
        passWorker?.cancel(true)
        val serial = ++passSerial
        passes.rowCount = 0
        currentPasses = emptyList(); radar.path = emptyList(); radarWorker?.cancel(true)
        val data = selected() ?: return
        val station = runCatching { observer() }.getOrElse { error(it); return }
        val predictionDays = days.value as Int
        val minimum = (minElevation.value as Int).toDouble()
        while (passes.rowCount > 0) passes.removeRow(0)
        status.text = "Calculating passes for ${data.name}…"
        passWorker = object : SwingWorker<List<Pass>, Unit>() {
            override fun doInBackground() = predictPasses(data, station, System.currentTimeMillis(), predictionDays, minimum)
            override fun done() {
                if (isCancelled || serial != passSerial || selected()?.catnum != data.catnum) return
                runCatching {
                    currentPasses = get()
                    val formatter = stamp.withZone(if (utc.isSelected) ZoneOffset.UTC else ZoneId.systemDefault())
                    currentPasses.forEach {
                        passes.addRow(arrayOf<Any?>(formatter.format(Instant.ofEpochMilli(it.aos)),
                            formatter.format(Instant.ofEpochMilli(it.los)), "%.1f°".format(it.maximum)))
                    }
                    if (passes.rowCount > 0) passTable.setRowSelectionInterval(0, 0)
                    status.text = "${passes.rowCount} passes in the next $predictionDays days · ${data.name}"
                }.onFailure { error(it) }
            }
        }.also { it.execute() }
    }

    private fun calculateRadarPath() {
        radarWorker?.cancel(true)
        val pass = currentPasses.getOrNull(passTable.selectedRow) ?: return
        val data = selected() ?: return
        val station = runCatching { observer() }.getOrNull() ?: return
        val serial = passSerial
        radarWorker = object : SwingWorker<List<OrbitalPos>, Unit>() {
            override fun doInBackground(): List<OrbitalPos> {
                val orbit = data.getObject()
                return (0..180).map {
                    if (isCancelled) throw CancellationException()
                    orbit.getPosition(station, pass.aos + (pass.los - pass.aos) * it / 180)
                }
            }
            override fun done() {
                if (!isCancelled && serial == passSerial) runCatching { radar.path = get() }
            }
        }.also { it.execute() }
    }

    private fun calculateTrack() {
        lastTrack = System.currentTimeMillis()
        trackWorker?.cancel(true)
        val data = selected() ?: return
        val serial = passSerial
        val station = runCatching { observer() }.getOrNull() ?: return
        trackWorker = object : SwingWorker<List<Pair<Double, Double>>, Unit>() {
            override fun doInBackground(): List<Pair<Double, Double>> {
                val orbit = data.getObject()
                val now = System.currentTimeMillis()
                val durationMs = (data.orbitalPeriod.coerceAtMost(1440.0) * 60_000).toLong()
                return (0..120).map { step ->
                    if (isCancelled) throw CancellationException("Track cancelled")
                    val sample = orbit.getFullPosition(station, now + durationMs * step / 120)
                    val lat = sample.latitude * 180 / PI
                    var lon = sample.longitude * 180 / PI
                    while (lon > 180) lon -= 360
                    while (lon < -180) lon += 360
                    lat to lon
                }
            }
            override fun done() {
                if (isCancelled || serial != passSerial || selected()?.catnum != data.catnum) return
                runCatching { map.track = get() }
            }
        }.also { it.execute() }
    }

    private fun error(exception: Throwable) {
        val cause = exception.cause ?: exception
        status.text = "Error: ${cause.message}"
        JOptionPane.showMessageDialog(this, cause.message, "Look4Sat", JOptionPane.ERROR_MESSAGE)
    }
}
