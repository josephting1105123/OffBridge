package com.joseph.clipboard.windows.os

import java.awt.AWTException
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

object SystemTrayManager {

    private var trayIcon: TrayIcon? = null

    /** The PNG sizes make-app-icon.ps1 writes next to app.ico, ascending. */
    private val ICON_SIZES = listOf(16, 24, 32, 48, 256)

    /** The placeholder this replaced, kept only so the test can assert it never comes back. */
    internal val RETIRED_PLACEHOLDER: Color = Color(52, 211, 153)

    fun initialize(onOpenApp: () -> Unit, onPushClipboard: () -> Unit, onQuit: () -> Unit) {
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray is not supported on this platform.")
            return
        }

        val tray = SystemTray.getSystemTray()

        // The app mark — the same white glyph on the same #1E1E1E squircle the phone's launcher icon
        // uses, rendered from the same Android vector by make-app-icon.ps1. This used to be a flat
        // emerald square left over from early development ("a basic 16x16 dummy image"), which made
        // the tray the one place the Windows client showed a different logo from Android's.
        val image = loadAppMark(tray.trayIconSize.width)

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
                // Exit is the caller's job: quitting via exitProcess here would skip the
                // app-level daemon/socket teardown (DisposableEffect.onDispose never runs).
                onQuit()
            }
        }

        popup.add(openItem)
        popup.add(pushItem)
        popup.addSeparator()
        popup.add(quitItem)

        trayIcon = TrayIcon(image, "OffBridge", popup).apply {
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

    /**
     * The app mark at the size the tray asked for, from the PNGs packaged beside app.ico.
     *
     * The JDK ships no .ico reader, so the tray cannot use app.ico itself — hence the sibling PNGs,
     * rendered in the same script run from the same Android vector. Prefers the smallest entry that
     * is at least [preferred] (downscaling stays sharp; upscaling 16px into a 32px HiDPI tray does
     * not), then any entry at all, so a missing file costs quality rather than correctness.
     */
    internal fun loadAppMark(preferred: Int): Image {
        val order = (ICON_SIZES.filter { it >= preferred } + ICON_SIZES.reversed()).distinct()
        for (size in order) {
            val stream = SystemTrayManager::class.java.classLoader
                .getResourceAsStream("windows/app-$size.png") ?: continue
            val decoded = stream.use { runCatching { ImageIO.read(it) }.getOrNull() }
            if (decoded != null) return decoded
        }
        System.err.println("SystemTrayManager: windows/app-*.png missing from the classpath — drawing the fallback mark.")
        return fallbackMark(preferred)
    }

    /**
     * Drawn only if the PNGs are missing from the jar. Deliberately the brand's charcoal-and-white,
     * not the emerald square it replaced: a fallback that looks like a different app is worse than
     * one that looks like a rough version of the right app.
     */
    private fun fallbackMark(size: Int): BufferedImage {
        val px = size.coerceAtLeast(16)
        val image = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val corner = (px * 0.44).toInt()          // 22% radius, as a diameter for fillRoundRect
            g.color = Color(0x1E, 0x1E, 0x1E)
            g.fillRoundRect(0, 0, px, px, corner, corner)
            val inset = (px * 0.28).toInt()
            g.color = Color.WHITE
            g.stroke = BasicStroke((px / 12f).coerceAtLeast(1f))
            g.drawRect(inset, inset, px - 2 * inset, px - 2 * inset)
        } finally {
            g.dispose()
        }
        return image
    }
}
