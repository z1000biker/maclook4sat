/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.desktop

import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.util.Properties
import java.util.prefs.Preferences
import javax.swing.*

internal class AppearanceDesktop(private val root: JFrame) : JPanel() {
    private val prefs = Preferences.userNodeForPackage(AppearanceDesktop::class.java)
    private val languages = JComboBox(arrayOf("en", "es", "ru", "si", "tr", "uk", "zh"))
    private val themes = JComboBox(arrayOf("System", "Dark", "Night"))
    private val originalColors = UIManager.getDefaults().keys.toList().filterIsInstance<String>().mapNotNull { key ->
        (UIManager.get(key) as? Color)?.let { key to it }
    }.toMap()
    private val english = load("en")

    init {
        add(JLabel("Language")); add(languages); add(JLabel("Theme")); add(themes)
        add(JLabel("New desktop controls use English when no translation exists"))
        languages.selectedItem = prefs.get("language", java.util.Locale.getDefault().language).takeIf { it in (0 until languages.itemCount).map(languages::getItemAt) } ?: "en"
        themes.selectedItem = prefs.get("theme", "System")
        languages.addActionListener { apply() }; themes.addActionListener { apply() }
    }

    fun apply() {
        val language = languages.selectedItem.toString(); val theme = themes.selectedItem.toString()
        prefs.put("language", language); prefs.put("theme", theme)
        val translated = load(language)
        val dictionary = english.stringPropertyNames().associate { english.getProperty(it) to translated.getProperty(it, english.getProperty(it)) }
        fun translate(component: Component) {
            if (component is JComponent) {
                fun text(value: String): String {
                    val source = component.getClientProperty("look4sat.original") as? String ?: value.also { component.putClientProperty("look4sat.original", it) }
                    return dictionary[source] ?: source
                }
                when (component) {
                    is AbstractButton -> component.text?.let { component.text = text(it) }
                    is JLabel -> component.text?.let { if (dictionary.containsKey(it) || component.getClientProperty("look4sat.original") != null) component.text = text(it) }
                    is JTabbedPane -> for (index in 0 until component.tabCount) {
                        val key = "look4sat.tab.$index"
                        val original = component.getClientProperty(key) as? String ?: component.getTitleAt(index).also { component.putClientProperty(key, it) }
                        component.setTitleAt(index, dictionary[original] ?: original)
                    }
                }
            }
            if (component is Container) component.components.forEach(::translate)
        }
        originalColors.forEach { (key, color) ->
            UIManager.put(key, if (theme == "System") color else when {
                key.endsWith("background", true) -> if (theme == "Night") Color(20, 0, 0) else Color(30, 34, 40)
                key.endsWith("foreground", true) -> if (theme == "Night") Color(230, 75, 60) else Color(225, 230, 235)
                else -> color
            })
        }
        SwingUtilities.updateComponentTreeUI(root); translate(root)
    }

    private fun load(language: String) = Properties().apply {
        javaClass.getResourceAsStream("/languages/$language.properties")?.reader(Charsets.UTF_8)?.use(::load)
    }
}
