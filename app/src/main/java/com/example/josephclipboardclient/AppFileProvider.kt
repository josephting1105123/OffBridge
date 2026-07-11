package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Content URI for [file] via this app's FileProvider. Single source of the authority string
 * ("<applicationId>.provider", declared in AndroidManifest) that several share/open call sites
 * otherwise hand-build.
 */
fun appFileProviderUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
