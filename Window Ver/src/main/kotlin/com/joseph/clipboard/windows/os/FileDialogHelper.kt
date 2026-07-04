package com.joseph.clipboard.windows.os

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

object FileDialogHelper {
    
    init {
        try {
            System.setProperty("sun.awt.windows.useCommonItemDialog", "true")
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun openFilePicker(title: String = "Select File"): File? {
        val frame = Frame()
        val dialog = FileDialog(frame, title, FileDialog.LOAD)
        dialog.isVisible = true
        val file = if (dialog.directory != null && dialog.file != null) File(dialog.directory, dialog.file) else null
        frame.dispose()
        return file
    }

    fun openMultipleFilesPicker(title: String = "Select Files", filterPattern: String? = null, defaultDirectory: String? = null): List<File> {
        return try {
            // Escape single quotes ('' is the PowerShell literal-string escape) so a value containing
            // ' (e.g. a path like D:\Users\O'Brien\Downloads) can't break out of the '...' string and
            // corrupt or inject into the generated script.
            val title = title.replace("'", "''")
            val defaultDirectory = defaultDirectory?.replace("'", "''")
            val filterPattern = filterPattern?.replace("'", "''")
            val scriptContent = """
                Add-Type -AssemblyName System.Windows.Forms
                ${'$'}dialog = New-Object System.Windows.Forms.OpenFileDialog
                ${'$'}dialog.Title = '$title'
                ${'$'}dialog.Multiselect = ${'$'}true
                if ('$defaultDirectory' -ne 'null' -and '$defaultDirectory' -ne '') { ${'$'}dialog.InitialDirectory = '$defaultDirectory' }
                if ('$filterPattern' -ne 'null' -and '$filterPattern' -ne '') { ${'$'}dialog.Filter = "Files|*$filterPattern|All Files|*.*" }
                ${'$'}result = ${'$'}dialog.ShowDialog()
                if (${'$'}result -eq [System.Windows.Forms.DialogResult]::OK) {
                    ${'$'}dialog.FileNames -join '|'
                }
            """.trimIndent()
            
            val tempScript = File.createTempFile("compose_file_picker", ".ps1")
            tempScript.writeText(scriptContent)
            
            val process = ProcessBuilder("powershell", "-Sta", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", tempScript.absolutePath)
                .startDrained("FileDialogHelper file picker")
            val output = process.inputStream.bufferedReader().readText().trim()
            
            tempScript.delete()
            
            if (output.isNotBlank()) {
                output.split("|").filter { it.isNotBlank() }.map { File(it) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun openDirectoryPicker(title: String = "Select Directory"): File? {
        return try {
            // Escape single quotes so a title containing ' can't break out of the '...' argument.
            val title = title.replace("'", "''")
            val scriptContent = """
                ${'$'}dllPath = "${'$'}env:TEMP\ComposeFolderPicker.dll"
                if (-not (Test-Path ${'$'}dllPath)) {
                    ${'$'}code = @"
using System;
using System.Runtime.InteropServices;
public class FolderPicker {
    [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
    class FileOpenDialog {}
    [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IFileOpenDialog {
        [PreserveSig] int Show([In] IntPtr parent);
        void SetFileTypes(); void SetFileTypeIndex(); void GetFileTypeIndex();
        void Advise(); void Unadvise();
        void SetOptions([In] uint fos);
        void GetOptions(out uint fos);
        void SetDefaultFolder(); void SetFolder(); void GetFolder();
        void GetCurrentSelection(); void SetFileName(); void GetFileName();
        void SetTitle([In, MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
        void SetOkButtonLabel(); void SetFileNameLabel();
        void GetResult(out IShellItem ppsi);
    }
    [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellItem {
        void BindToHandler(); void GetParent();
        void GetDisplayName([In] uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
    }
    public static string PickFolder(string title) {
        IFileOpenDialog dialog = (IFileOpenDialog)new FileOpenDialog();
        dialog.SetTitle(title);
        uint options; dialog.GetOptions(out options);
        dialog.SetOptions(options | 0x20); // FOS_PICKFOLDERS
        if (dialog.Show(IntPtr.Zero) == 0) {
            IShellItem item; dialog.GetResult(out item);
            string path; item.GetDisplayName(0x80058000, out path);
            return path;
        }
        return null;
    }
}
"@
                    Add-Type -TypeDefinition ${'$'}code -OutputAssembly ${'$'}dllPath
                }
                Add-Type -Path ${'$'}dllPath
                [FolderPicker]::PickFolder('$title')
            """.trimIndent()
            
            val tempScript = File.createTempFile("compose_folder_picker", ".ps1")
            tempScript.writeText(scriptContent)
            
            val process = ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", tempScript.absolutePath)
                .startDrained("FileDialogHelper folder picker")
            val output = process.inputStream.bufferedReader().readText().trim()
            
            tempScript.delete()
            
            if (output.isNotBlank()) {
                File(output)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
