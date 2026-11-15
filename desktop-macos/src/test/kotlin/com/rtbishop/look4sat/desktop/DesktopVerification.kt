/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** Deterministic, offline regression checks; run with verifyDesktop. */
fun main(args: Array<String>) {
    val csv = "\uFEFFOBJECT_NAME,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,NORAD_CAT_ID,BSTAR,MEAN_MOTION_DOT\n" +
        "\"Test, \"\"Satellite\"\"\",2026-11-15T12:00:00.000000,15.5,0.0005,51.6,120,10,20,123456,0.0001,0.00002\n" +
        "Invalid,2026-11-15T12:00:00,-1,0.1,20,0,0,0,42,0,0\n" +
        "Broken,not-a-date,15,0.1,20,0,0,0,43,0,0\n"
    val parsed = parseElements(csv)
    check(parsed.size == 1) { "Invalid CSV rows were accepted: $parsed" }
    val data = parsed.single()
    check(data.name == "Test, \"Satellite\"" && data.catnum == 123456)
    check(abs(data.epoch - 26319.5) < 1e-8)
    check(data.ndot == 0.00002)
    val tle = """
        ISS (ZARYA)
        1 25544U 98067A   26319.50000000  .00002000  00000-0  10000-3 0  9990
        2 25544  51.6000 120.0000 0005000  10.0000  20.0000 15.50000000123456
    """.trimIndent()
    val legacy = parseElements(tle).single()
    check(legacy.catnum == 25544 && abs(legacy.bstar - 0.0001) < 1e-12)
    check(parseElements(tle.replace("25544", "A1234")).single().catnum == 101234)
    check(parseElements(tle.lines().drop(1).joinToString("\n")).single().catnum == 25544)
    check(parseElements("garbage\n1 short\n2 short").isEmpty())
    check(parseElements("1 x\n2 x").isEmpty())
    check(parseElements(tle.replace("2 25544", "2 12345")).isEmpty())
    val start = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    val station = GeoPos(37.943, 23.647)
    val passes = predictPasses(legacy, station, start, 1, 0.0)
    check(passes.isNotEmpty()) { "Expected LEO passes" }
    passes.forEach { pass ->
        check(pass.aos >= start && pass.los > pass.aos && pass.los <= start + 86400_000)
        check(pass.maximum in 0.0..90.0)
        val orbit = legacy.getObject()
        check(orbit.getElevation(station, pass.aos + 1000) >= 0)
        if (pass.los < start + 86400_000) check(orbit.getElevation(station, pass.los + 1000) <= 0)
    }
    check(passes.zipWithNext().all { (a, b) -> a.los < b.aos })
    check(predictPasses(legacy, station, start, 1, 90.0).isEmpty())
    val geo = OrbitalData("GEO", 26319.5, 1.0027, 0.00001, 0.01, 0.0, 0.0, 0.0, 99999, 0.0)
    val sub = geo.getObject().getPosition(GeoPos(0.0, 0.0), start)
    val geoStation = GeoPos(Math.toDegrees(sub.latitude), Math.toDegrees(sub.longitude).let { if (it > 180) it - 360 else it })
    val geoPass = predictPasses(geo, geoStation, start, 1, 0.0).single()
    check(geoPass.aos == start && geoPass.los == start + 86400_000) { "Continuous deep-space visibility was lost" }
    Thread.currentThread().interrupt()
    try {
        predictPasses(legacy, station, start, 1, 0.0)
        error("Interrupted prediction was not cancelled")
    } catch (_: CancellationException) { } finally { Thread.interrupted() }
    val approaching = OrbitalPos(distanceRate = -1.0)
    check(approaching.getDownlinkFreq(145800000) > 145800000)
    check(approaching.getUplinkFreq(435000000) < 435000000)
    val calibrated = CompassReading(359.0, 15.0, 0L).calibrated(5.0, 5.0, -1.0)
    check(calibrated.heading == 4.0 && calibrated.pitch == -10.0)
    check(calibrated.isFresh(3, 2_999_999_999L))
    check(!calibrated.isFresh(3, 3_000_000_000L))
    check(CompassReading(1.0, null).calibrated(0.0, 0.0, 1.0).pitch == null)
    check(abs(pointingDifference(1.0, 359.0) - 2.0) < 1e-9)
    check(abs(pointingDifference(359.0, 1.0) + 2.0) < 1e-9)
    check(parseCompassReading("-1,15", false, "1", "2")?.heading == 359.0)
    check(parseCompassReading("12", false, "1", "")?.pitch == null)
    check(parseCompassReading("12", false, "1", "2") == null)
    check(parseCompassReading("NaN,12", false, "1", "2") == null)
    check(parseCompassReading("12,Infinity", false, "1", "2") == null)
    check(parseCompassReading("12,91", false, "1", "2") == null)
    check(parseCompassReading("1,2", false, "0", "2") == null)
    check(parseCompassReading("{\"az\":361,\"tilt\":-20}", true, "az", "tilt")?.let { it.heading == 1.0 && it.pitch == -20.0 } == true)
    check(parseCompassReading("{}", true, "heading", "pitch") == null)
    check(parseCompassReading("broken", true, "heading", "pitch") == null)
    javax.swing.SwingUtilities.invokeAndWait {
        val panels = listOf(CompassDesktop {}, Radar().apply {
            orientation = CompassReading(350.0, 20.0)
            position = OrbitalPos(azimuth = Math.toRadians(10.0), elevation = Math.toRadians(40.0))
        }, GroundMap(), GlobalPassesDesktop(), RadioDesktop(), RemoteDesktop(), SatnogsDesktop(), AmsatDesktop(), SstvDesktop())
        panels.forEach { panel ->
            panel.setSize(1100, 700); panel.doLayout()
            val image = java.awt.image.BufferedImage(1100, 700, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose()
            when (panel) {
                is CompassDesktop -> panel.close()
                is SstvDesktop -> panel.close()
                is GlobalPassesDesktop -> panel.close()
                is RadioDesktop -> panel.disconnect()
                is RemoteDesktop -> panel.close()
            }
        }
    }
    if ("--skip-network" in args) println("SKIPPED: TCP integration (network restricted)") else {
        verifyCompassConnection()
        println("PASS: TCP compass input, calibration, stale-data fallback, disable")
    }
    println("PASS: CSV/TLE/Alpha-5 parsing, invalid input, LEO crossings, elevation filtering, deep-space visibility, cancellation, Doppler, compass parsing, calibration, freshness, angle wrapping, desktop panel rendering")
}

private fun verifyCompassConnection() {
    val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    val received = java.util.concurrent.CountDownLatch(1)
    val stale = java.util.concurrent.CountDownLatch(1)
    val current = java.util.concurrent.atomic.AtomicReference<CompassReading?>()
    val socket = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
    var panel: CompassDesktop? = null
    val sender = Thread({
        runCatching {
            server.accept().use { connection ->
                socket.set(connection)
                connection.getOutputStream().apply { write("359,15\n".toByteArray()); flush() }
                // Keep the connection open while testing timeout of an otherwise healthy stream.
                connection.getInputStream().read()
            }
        }
    }, "compass-test-sender").apply { isDaemon = true; start() }
    try {
        javax.swing.SwingUtilities.invokeAndWait {
            val compass = CompassDesktop { reading ->
                current.set(reading)
                if (reading != null) received.countDown() else if (received.count == 0L) stale.countDown()
            }
            panel = compass
            fun field(label: String): javax.swing.JTextField {
                val index = compass.components.indexOfFirst { (it as? javax.swing.JLabel)?.text == label }
                check(index >= 0)
                return compass.components[index + 1] as javax.swing.JTextField
            }
            compass.components.filterIsInstance<javax.swing.JComboBox<*>>().first().selectedItem = "TCP"
            field("TCP sensor host").text = "127.0.0.1"
            field("TCP sensor port").text = server.localPort.toString()
            field("Heading: CSV column (1-based) or JSON key").text = "1"
            field("Pitch column / key (blank = heading only)").text = "2"
            field("Mounting heading offset °").text = "2"
            field("Magnetic declination ° (east +; true-heading sensor: 0)").text = "3"
            field("Pitch mounting offset °").text = "5"
            compass.components.filterIsInstance<javax.swing.JCheckBox>().first { it.text == "Invert pitch" }.isSelected = true
            compass.components.filterIsInstance<javax.swing.JSpinner>().single().value = 1
            compass.components.filterIsInstance<javax.swing.JCheckBox>().first { it.text == "Enable external compass" }.doClick()
        }
        check(received.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "No TCP compass reading received" }
        check(current.get()?.let { abs(it.heading - 4.0) < 1e-9 && it.pitch == -10.0 } == true) { "Calibration was not applied" }
        check(stale.await(4, java.util.concurrent.TimeUnit.SECONDS)) { "Stale compass data was not discarded" }
        javax.swing.SwingUtilities.invokeAndWait {
            panel!!.components.filterIsInstance<javax.swing.JCheckBox>().first { it.text == "Enable external compass" }.doClick()
            check(current.get() == null) { "Disabling compass did not restore north-up" }
        }
    } finally {
        javax.swing.SwingUtilities.invokeAndWait { panel?.close() }
        socket.get()?.close(); server.close(); sender.join(1000)
    }
}
