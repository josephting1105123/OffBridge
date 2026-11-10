package com.joseph.clipboard.windows.os

import java.awt.Desktop
import java.io.File

/**
 * Opening a file a paired peer sent. Desktop.open() hands the file to its Windows file association,
 * which for an .exe/.bat/.lnk/... means running it. A peer (or whoever compromised one) shouldn't get
 * code execution out of a double-click on "Open", so executable and script types are only revealed in
 * Explorer, selected, and the user decides from there. The list also covers macro-enabled Office
 * documents and container types (disk images, help files), which are revealed in Explorer instead
 * of opened.
 */
object SafeOpen {
    private val RUNNABLE_EXTENSIONS = setOf(
        "exe", "com", "scr", "pif", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse",
        "wsf", "wsh", "lnk", "url", "msi", "msp", "hta", "cpl", "jar", "reg",
        // more script/host types
        "ps1xml", "psd1", "wsc", "sct", "msc", "inf", "scf", "py", "pyw",
        // installers and app packages
        "msix", "msixbundle", "appx", "appxbundle", "appinstaller", "application", "appref-ms",
        "diagcab", "gadget",
        // shell/handler files
        "settingcontent-ms", "library-ms", "search-ms", "website", "rdp",
        // macro-enabled Office and add-ins
        "docm", "dotm", "xlsm", "xltm", "xlam", "xll", "pptm", "potm", "ppam", "ppsm", "sldm", "wll",
        // help and disk images
        "chm", "iso", "img", "vhd", "vhdx",
    )

    /** True when Windows would execute [fileName] on open rather than show it. Windows ignores
     *  trailing dots and spaces in a name, so "setup.exe. " is still an .exe. */
    fun isRunnable(fileName: String): Boolean {
        val name = fileName.trimEnd('.', ' ')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in RUNNABLE_EXTENSIONS
    }

    /** Opens [file] with its default app, or for a runnable type selects it in Explorer instead. */
    fun openOrReveal(file: File) {
        if (isRunnable(file.name)) {
            ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
        } else if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    }
}
