package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppInfo(
    val name: String,
    val packageName: String,
    val version: String,
    val sizeBytes: Long,
    val sourceDir: String,
    val icon: Drawable
) {
    fun getFormattedSize(): String {
        if (sizeBytes < 1024) return "$sizeBytes B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }
}

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var etSearchApp: EditText
    private lateinit var tvAppCount: TextView
    private lateinit var switchMultiSelect: SwitchMaterial
    private lateinit var btnDone: Button
    private lateinit var pbLoadingApps: ProgressBar
    private lateinit var adapter: AppSelectionAdapter

    private val allApps = mutableListOf<AppInfo>()
    private val selectedApps = mutableSetOf<AppInfo>()
    private var isMultiSelect = false

    private val activityJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rvApps = findViewById(R.id.rvApps)
        etSearchApp = findViewById(R.id.etSearchApp)
        tvAppCount = findViewById(R.id.tvAppCount)
        switchMultiSelect = findViewById(R.id.switchMultiSelect)
        btnDone = findViewById(R.id.btnDone)
        pbLoadingApps = findViewById(R.id.pbLoadingApps)

        adapter = AppSelectionAdapter()
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        switchMultiSelect.setOnCheckedChangeListener { _, isChecked ->
            isMultiSelect = isChecked
            if (!isMultiSelect) {
                selectedApps.clear()
            }
            adapter.notifyDataSetChanged()
            updateDoneButton()
        }

        btnDone.setOnClickListener {
            finishWithSelection()
        }

        etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s.toString())
            }
        })

        loadApps()
    }

    private fun loadApps() {
        uiScope.launch {
            val pm = packageManager
            val packages = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
            
            val userApps = packages.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            
            val loadedApps = withContext(Dispatchers.IO) {
                userApps.map { appInfo ->
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    val version = try {
                        val pInfo = pm.getPackageInfo(appInfo.packageName, 0)
                        pInfo.versionName ?: getString(R.string.applist_version_unknown)
                    } catch (e: Exception) { getString(R.string.applist_version_unknown) }
                    val file = File(appInfo.sourceDir)
                    val size = if (file.exists()) file.length() else 0L

                    AppInfo(name, appInfo.packageName, version, size, appInfo.sourceDir, icon)
                }.sortedBy { it.name.lowercase() }
            }

            allApps.addAll(loadedApps)
            pbLoadingApps.visibility = View.GONE
            filterApps("")
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { 
                it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
        tvAppCount.text = getString(R.string.applist_app_count, filtered.size)
        adapter.setApps(filtered)
    }

    private fun updateDoneButton() {
        if (isMultiSelect && selectedApps.isNotEmpty()) {
            btnDone.visibility = View.VISIBLE
            btnDone.text = getString(R.string.applist_select_count, selectedApps.size)
        } else {
            btnDone.visibility = View.GONE
        }
    }

    private fun finishWithSelection() {
        val resultIntent = Intent()
        val uris = ArrayList<Uri>()
        val names = ArrayList<String>()

        selectedApps.forEach {
            val apkFile = File(it.sourceDir)
            val uri = appFileProviderUri(this, apkFile)
            uris.add(uri)
            names.add("${it.name}.apk")
        }

        resultIntent.putParcelableArrayListExtra("app_uris", uris)
        resultIntent.putStringArrayListExtra("app_names", names)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
    }

    inner class AppSelectionAdapter : RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder>() {

        private var apps: List<AppInfo> = listOf()

        fun setApps(newApps: List<AppInfo>) {
            this.apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_selection, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = apps[position]
            holder.bind(app)
        }

        override fun getItemCount() = apps.size

        inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvAppInfo: TextView = itemView.findViewById(R.id.tvAppInfo)
            private val tvAppPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
            private val cbAppSelect: CheckBox = itemView.findViewById(R.id.cbAppSelect)

            fun bind(app: AppInfo) {
                ivAppIcon.setImageDrawable(app.icon)
                tvAppName.text = app.name
                tvAppInfo.text = itemView.context.getString(
                    R.string.applist_app_info, app.getFormattedSize(), app.version
                )
                tvAppPackage.text = app.packageName

                if (isMultiSelect) {
                    cbAppSelect.visibility = View.VISIBLE
                    cbAppSelect.isChecked = selectedApps.contains(app)
                } else {
                    cbAppSelect.visibility = View.GONE
                }

                itemView.setOnClickListener {
                    if (isMultiSelect) {
                        if (selectedApps.contains(app)) {
                            selectedApps.remove(app)
                            cbAppSelect.isChecked = false
                        } else {
                            selectedApps.add(app)
                            cbAppSelect.isChecked = true
                        }
                        updateDoneButton()
                    } else {
                        selectedApps.clear()
                        selectedApps.add(app)
                        finishWithSelection()
                    }
                }
            }
        }
    }
}
