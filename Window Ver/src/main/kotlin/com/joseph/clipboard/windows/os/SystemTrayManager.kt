package com.joseph.clipboard.windows.os

import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import kotlin.system.exitProcess

object SystemTrayManager {

    private var trayIcon: TrayIcon? = null

    fun initialize(onOpenApp: () -> Unit, onPushClipboard: () -> Unit) {
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray is not supported on this platform.")
            return
        }

        val tray = SystemTray.getSystemTray()

        // Create a basic 16x16 dummy image for the tray icon
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color(52, 211, 153) // GreenPrimary
        graphics.fillRect(0, 0, 16, 16)
        graphics.dispose()

        val popup = PopupMenu()

        val openItem = MenuItem("Open").apply {
            addActionListener { onOpenApp() }
        }
        val pushItem = MenuItem("Push Clipboard").apply {
            addActionListener { onPushClipboard() }
        }
        val quitItem = MenuItem("Quit").apply {
            addActionListener { 
                trayIcon?.let { tray.remove(it) }
                exitProcess(0) 
            }
        }

        popup.add(openItem)
        popup.add(pushItem)
        popup.addSeparator()
        popup.add(quitItem)

        trayIcon = TrayIcon(image, "Clipboard Share", popup).apply {
            isImageAutoSize = true
            addActionListener { onOpenApp() } // double click action
        }

        try {
            tray.add(trayIcon)
        } catch (e: AWTException) {
            System.err.println("TrayIcon could not be added.")
        }
    }

    fun showNotification(title: String, message: String, type: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
        trayIcon?.displayMessage(title, message, type)
    }
}
