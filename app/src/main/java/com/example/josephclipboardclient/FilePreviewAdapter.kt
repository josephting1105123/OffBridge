package com.EntropicSoftwareLab.ClipboardShare

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class FilePreviewAdapter(
    private val context: Context,
    private val items: MutableList<TransferItem>,
    private val onSelectionModeChanged: (Boolean) -> Unit,
    private val onItemsChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    /** Read-only mode: suppresses long-press selection, drag-reorder and the delete overlay. */
    private val displayOnly: Boolean = false
) : RecyclerView.Adapter<FilePreviewAdapter.ViewHolder>() {

    var isSelectionMode = false
    val selectedItemsForDeletion = mutableSetOf<TransferItem>()

    fun enterSelectionMode() {
        if (!isSelectionMode) {
            isSelectionMode = true
            onSelectionModeChanged(true)
            notifyDataSetChanged()
        }
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItemsForDeletion.clear()
        onSelectionModeChanged(false)
        notifyDataSetChanged()
    }

    fun toggleSelectionMode() {
        if (isSelectionMode) exitSelectionMode() else enterSelectionMode()
    }

    fun setItems(newItems: List<TransferItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        // M-10: guard against NO_POSITION / out-of-range indices from a drag that
        // races a list mutation, which would make Collections.swap throw.
        if (fromPosition !in items.indices || toPosition !in items.indices) return
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        onItemsChanged()
    }

    fun deleteSelectedItems() {
        items.removeAll(selectedItemsForDeletion)
        selectedItemsForDeletion.clear()
        exitSelectionMode()
        onItemsChanged()
    }

    fun selectAll() {
        if (!isSelectionMode) enterSelectionMode()
        selectedItemsForDeletion.addAll(items)
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedItemsForDeletion.clear()
        if (isSelectionMode) exitSelectionMode()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPreview: ImageView = itemView.findViewById(R.id.ivPreview)
        private val otherFileContainer: LinearLayout = itemView.findViewById(R.id.otherFileContainer)
        private val tvFileIcon: TextView = itemView.findViewById(R.id.tvFileIcon)
        private val ivFileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val tvOtherFileName: TextView = itemView.findViewById(R.id.tvOtherFileName)
        private val btnCancelFile: View = itemView.findViewById(R.id.btnCancelFile)
        private val selectionOverlay: View = itemView.findViewById(R.id.selectionOverlay)
        private val cardView: CardView = itemView as CardView

        fun bind(item: TransferItem) {
            // Safely get mime type — folder tree URIs can't be resolved
            val mimeType = if (item.isFolder) {
                "inode/directory"
            } else {
                try { context.contentResolver.getType(item.uri) } catch (_: Exception) { null }
                    ?: "application/octet-stream"
            }

            // Display content
            if (mimeType.startsWith("image/")) {
                ivPreview.visibility = View.VISIBLE
                otherFileContainer.visibility = View.GONE
                try {
                    ivPreview.setImageURI(item.uri)
                } catch (_: Exception) {
                    ivPreview.setBackgroundColor(Color.parseColor("#333333"))
                }
            } else {
                ivPreview.visibility = View.GONE
                otherFileContainer.visibility = View.VISIBLE

                if (item.isFolder) {
                    tvFileIcon.visibility = View.GONE
                    ivFileIcon.visibility = View.VISIBLE
                    ivFileIcon.setImageResource(R.drawable.ic_folder)
                    val docFile = try {
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, item.uri)
                    } catch (_: Exception) { null }
                    tvOtherFileName.text = docFile?.name ?: "Folder"
                } else {
                    val displayPath = item.relativePath ?: getFileName(item.uri)
                    val isTextItem = displayPath.matches(Regex("Text \\d+"))
                    if (isTextItem) {
                        tvFileIcon.visibility = View.GONE
                        ivFileIcon.visibility = View.VISIBLE
                        ivFileIcon.setImageResource(R.drawable.ic_text)
                    } else {
                        tvFileIcon.visibility = View.VISIBLE
                        ivFileIcon.visibility = View.GONE
                        tvFileIcon.text = "📄"
                    }
                    val parts = displayPath.split("/")
                    tvOtherFileName.text = if (parts.size > 1) "${parts[0]}/.../${parts.last()}" else parts.last()
                }
            }

            // Selection state
            if (displayOnly) {
                btnCancelFile.visibility = View.GONE
                selectionOverlay.visibility = View.GONE
            } else if (isSelectionMode) {
                val isSelected = selectedItemsForDeletion.contains(item)
                btnCancelFile.visibility = View.GONE
                selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            } else {
                btnCancelFile.visibility = View.VISIBLE
                selectionOverlay.visibility = View.GONE
            }

            // Reset any leftover animation state
            itemView.alpha = 1.0f
            itemView.scaleX = 1.0f
            itemView.scaleY = 1.0f

            // Quick delete X button
            btnCancelFile.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items.removeAt(pos)
                    notifyItemRemoved(pos)
                    onItemsChanged()
                }
            }

            // Long press: enter selection mode OR start drag
            itemView.setOnLongClickListener {
                if (displayOnly) return@setOnLongClickListener true
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true

                val currentItem = items[pos]
                if (!isSelectionMode) {
                    // Add item first, THEN enter selection mode so the callback sees it
                    isSelectionMode = true
                    selectedItemsForDeletion.add(currentItem)
                    onSelectionModeChanged(true)
                    notifyDataSetChanged()
                } else {
                    if (selectedItemsForDeletion.contains(currentItem)) {
                        // Already selected → start drag to reorder
                        onStartDrag(this)
                    } else {
                        // Not selected → just select it
                        selectedItemsForDeletion.add(currentItem)
                        notifyItemChanged(pos)
                    }
                }
                true
            }

            // Single tap: toggle selection or preview
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                val currentItem = items[pos]
                if (isSelectionMode && !displayOnly) {
                    // Toggle selection
                    if (selectedItemsForDeletion.contains(currentItem)) {
                        selectedItemsForDeletion.remove(currentItem)
                    } else {
                        selectedItemsForDeletion.add(currentItem)
                    }
                    if (selectedItemsForDeletion.isEmpty()) {
                        exitSelectionMode()
                    } else {
                        notifyItemChanged(pos)
                        onSelectionModeChanged(true) // refresh button state
                    }
                } else {
                    // Preview
                    if (mimeType.startsWith("image/")) {
                        showFullScreenImageDialog(currentItem.uri)
                    } else if (!currentItem.isFolder) {
                        openFilePreview(currentItem.uri, mimeType)
                    }
                }
            }
        }

        private fun showFullScreenImageDialog(uri: Uri) {
            val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val imageView = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#D9000000"))
                setOnClickListener { dialog.dismiss() }
            }
            try {
                imageView.setImageURI(uri)
            } catch (_: Exception) {
                dialog.dismiss()
                return
            }
            dialog.setContentView(imageView)
            dialog.show()
        }

        private fun openFilePreview(uri: Uri, mimeType: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // No app can handle this file type
            }
        }

        private fun getFileName(uri: Uri): String {
            var result: String? = null
            if (uri.scheme == "content") {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = cursor.getString(index)
                    }
                } finally { cursor?.close() }
            }
            if (result == null) {
                result = uri.path
                val cut = result?.lastIndexOf('/') ?: -1
                if (cut != -1) result = result?.substring(cut + 1)
            }
            return result ?: "Unknown File"
        }
    }
}
