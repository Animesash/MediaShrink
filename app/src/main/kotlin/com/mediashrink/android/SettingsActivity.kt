package com.mediashrink.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvCurrentFolder: TextView
    private lateinit var btnChooseFolder: MaterialButton
    private lateinit var btnResetFolder: MaterialButton

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleFolderSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarSettings)
        toolbar.setNavigationOnClickListener { finish() }

        tvCurrentFolder = findViewById(R.id.tvCurrentFolder)
        btnChooseFolder = findViewById(R.id.btnChooseFolder)
        btnResetFolder = findViewById(R.id.btnResetFolder)

        btnChooseFolder.setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        btnResetFolder.setOnClickListener {
            AppPrefs.setSaveFolderUri(this, null)
            updateCurrentFolderText()
        }

        updateCurrentFolderText()
    }

    private fun handleFolderSelected(uri: Uri) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        AppPrefs.setSaveFolderUri(this, uri)
        updateCurrentFolderText()

        Toast.makeText(this, getString(R.string.folder_selected_toast), Toast.LENGTH_SHORT).show()
    }

    private fun updateCurrentFolderText() {
        val savedUri = AppPrefs.getSaveFolderUri(this)

        if (savedUri == null) {
            tvCurrentFolder.text = getString(R.string.default_folder_text)
            return
        }

        val docFile = DocumentFile.fromTreeUri(this, savedUri)
        val folderName = docFile?.name ?: savedUri.path ?: "?"
        tvCurrentFolder.text = getString(R.string.current_folder_label, folderName)
    }
}