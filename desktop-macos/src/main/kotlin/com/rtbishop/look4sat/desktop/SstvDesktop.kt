/* GPL-3.0-or-later. Desktop audio input for Look4Sat's original SSTV decoder. */
package com.rtbishop.look4sat.desktop

import com.rtbishop.look4sat.core.domain.sstv.SstvDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import javax.swing.*

internal class SstvDesktop : JPanel(BorderLayout(8, 8)) {
    private val running = AtomicBoolean(false)
    private val decoder = SstvDecoder(sampleRate = 44100, enableDiagnosticsHandle = true)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val image = JLabel("Waiting for SSTV audio", SwingConstants.CENTER)
    private val status = JLabel("Microphone capture is stopped")
    @Volatile private var input: TargetDataLine? = null
    @Volatile private var audioThread: Thread? = null
    private val decoderLock = Any()
    private val devices = AudioSystem.getMixerInfo().filter { mixer ->
        AudioSystem.getMixer(mixer).targetLineInfo.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
    }
    private val device = JComboBox((listOf("System default") + devices.map { it.name }).toTypedArray())
    private var latest: BufferedImage? = null

    init {
        val toolbar = JPanel()
        val start = JButton("Start microphone")
        val stop = JButton("Stop")
        val reset = JButton("Reset image")
        val open = JButton("Decode audio file…")
        val save = JButton("Save image…")
        val modes = JComboBox((listOf("Auto") + decoder.supportedModes).toTypedArray())
        toolbar.add(device); toolbar.add(start); toolbar.add(stop); toolbar.add(modes); toolbar.add(reset); toolbar.add(open); toolbar.add(save)
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(image), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        preferredSize = Dimension(600, 450)

        start.addActionListener { startRecording() }
        reset.addActionListener { synchronized(decoderLock) { decoder.clearPixels() }; latest = null; image.icon = null; image.text = "Waiting for SSTV audio" }
        open.addActionListener {
            if (audioThread?.isAlive == true) { status.text = "Stop the current audio input first"; return@addActionListener }
            val picker = JFileChooser()
            if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) startFile(picker.selectedFile)
        }
        stop.addActionListener { stopRecording() }
        modes.addActionListener {
            val chosen = modes.selectedItem.toString()
            synchronized(decoderLock) { decoder.clearPixels(); decoder.lockMode(chosen) }
        }
        save.addActionListener {
            val frame = latest ?: return@addActionListener
            val picker = JFileChooser()
            picker.selectedFile = File("sstv.png")
            if (picker.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                runCatching { ImageIO.write(frame, "png", picker.selectedFile) }
                    .onFailure { status.text = "Save error: ${it.message}" }
            }
        }
        scope.launch {
            decoder.frames.collect { frame ->
                val pixels = frame.imagePixels ?: return@collect
                val w = frame.imageWidth
                val h = frame.imageHeight
                if (w <= 0 || h <= 0 || pixels.size < w * h) return@collect
                val rendered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                rendered.setRGB(0, 0, w, h, pixels, 0, w)
                SwingUtilities.invokeLater {
                    latest = rendered
                    image.icon = ImageIcon(rendered)
                    image.text = null
                    status.text = "${frame.modeName} · RMS %.3f · sync %.1f%%".format(frame.inputRms, frame.syncHitRate * 100)
                }
            }
        }
    }

    private fun startRecording() {
        if (audioThread?.isAlive == true || !running.compareAndSet(false, true)) return
        val format = AudioFormat(44100f, 16, 1, true, false)
        val selectedDevice = device.selectedIndex
        audioThread = Thread({
            try {
                val info = DataLine.Info(TargetDataLine::class.java, format)
                val line = (if (selectedDevice <= 0) AudioSystem.getLine(info)
                    else AudioSystem.getMixer(devices[selectedDevice - 1]).getLine(info)) as TargetDataLine
                input = line
                if (!running.get()) return@Thread
                line.open(format)
                line.start()
                SwingUtilities.invokeLater { status.text = "Capturing 44.1 kHz PCM audio" }
                val buffer = ByteArray(4096)
                while (running.get()) {
                    val bytes = line.read(buffer, 0, buffer.size)
                    if (bytes <= 0) continue
                    val samples = FloatArray(bytes / 2)
                    for (i in samples.indices) {
                        val low = buffer[2 * i].toInt() and 255
                        val high = buffer[2 * i + 1].toInt()
                        samples[i] = ((high shl 8) or low).toShort() / 32768f
                    }
                    synchronized(decoderLock) { runBlocking { decoder.feedSamples(samples) } }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { status.text = "Audio error: ${e.message}" }
            } finally {
                input?.stop(); input?.close(); input = null
                running.set(false)
            }
        }, "Look4Sat-SSTV-audio").apply { isDaemon = true; start() }
    }

    private fun startFile(file: File) {
        if (audioThread?.isAlive == true || !running.compareAndSet(false, true)) return
        audioThread = Thread({
            try {
                AudioSystem.getAudioInputStream(file).use { source ->
                    val format = AudioFormat(44100f, 16, 1, true, false)
                    AudioSystem.getAudioInputStream(format, source).use { stream ->
                        val buffer = ByteArray(4096)
                        while (running.get()) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            val samples = FloatArray(count / 2) { i ->
                                (((buffer[2 * i + 1].toInt() shl 8) or (buffer[2 * i].toInt() and 255)).toShort()) / 32768f
                            }
                            synchronized(decoderLock) { runBlocking { decoder.feedSamples(samples) } }
                        }
                    }
                }
                SwingUtilities.invokeLater { status.text = "Audio file decoded" }
            } catch (error: Exception) {
                SwingUtilities.invokeLater { status.text = "Audio file error: ${error.message}" }
            } finally { running.set(false) }
        }, "Look4Sat-SSTV-file").apply { isDaemon = true; start() }
    }

    fun stopRecording() {
        running.set(false)
        input?.stop()
        input?.close()
        status.text = "Microphone capture is stopped"
    }

    fun close() {
        stopRecording()
        scope.cancel()
    }
}
