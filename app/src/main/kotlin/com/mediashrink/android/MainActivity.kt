package com.mediashrink.android

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private val BITRATES = intArrayOf(64_000, 96_000, 128_000, 192_000, 256_000)
    }

    private data class OutputDestination(
        val pfd: ParcelFileDescriptor,
        val uri: Uri,
        val displayName: String
    )

    private var selectedFileUri: Uri? = null
    private var fileDurationMs: Long = 0L
    private var fileOriginalSize: Long = 0L
    private var selectedBitrate: Int = 128_000

    private lateinit var btnSelectFile: MaterialButton
    private lateinit var cardFileInfo: MaterialCardView
    private lateinit var tvFileName: android.widget.TextView
    private lateinit var tvFileDuration: android.widget.TextView
    private lateinit var tvOriginalSize: android.widget.TextView
    private lateinit var layoutCodec: TextInputLayout
    private lateinit var layoutBitrate: TextInputLayout
    private lateinit var tvEstimatedSize: android.widget.TextView
    private lateinit var btnCompress: MaterialButton

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        setupDropdowns()
        checkFirstLaunch()
    }

    private fun bindViews() {
        btnSelectFile = findViewById(R.id.btnSelectFile)
        cardFileInfo = findViewById(R.id.cardFileInfo)
        tvFileName = findViewById(R.id.tvFileName)
        tvFileDuration = findViewById(R.id.tvFileDuration)
        tvOriginalSize = findViewById(R.id.tvOriginalSize)
        layoutCodec = findViewById(R.id.layoutCodec)
        layoutBitrate = findViewById(R.id.layoutBitrate)
        tvEstimatedSize = findViewById(R.id.tvEstimatedSize)
        btnCompress = findViewById(R.id.btnCompress)
    }

    private fun setupListeners() {
        btnSelectFile.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        btnCompress.setOnClickListener {
            selectedFileUri?.let { uri -> startCompression(uri) }
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupDropdowns() {
        val codecAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.codec_options)
        )
        (layoutCodec.editText as? android.widget.AutoCompleteTextView)?.apply {
            setAdapter(codecAdapter)
            setText(codecAdapter.getItem(0), false)
        }

        val bitrateLabels = resources.getStringArray(R.array.bitrate_options)
        val bitrateAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, bitrateLabels
        )

        val bitrateDropdown = layoutBitrate.editText as? android.widget.AutoCompleteTextView
        bitrateDropdown?.apply {
            setAdapter(bitrateAdapter)
            setText(bitrateLabels[2], false)
            selectedBitrate = BITRATES[2]

            setOnItemClickListener { _, _, position, _ ->
                selectedBitrate = BITRATES[position]
                updateEstimatedSize()
            }
        }
    }

    private fun checkFirstLaunch() {
        if (AppPrefs.isFirstLaunch(this)) {
            showWelcomeDialog()
            AppPrefs.setFirstLaunchDone(this)
        }
    }

    private fun showWelcomeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.welcome_title))
            .setMessage(getString(R.string.welcome_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun handleFileSelected(uri: Uri) {
        selectedFileUri = uri

        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            fileDurationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            fileDurationMs = 0L
        } finally {
            retriever.release()
        }

        fileOriginalSize = getFileSize(uri)
        val fileName = getFileName(uri)

        tvFileName.text = getString(R.string.file_name, fileName)
        tvFileDuration.text = getString(R.string.file_duration, formatDuration(fileDurationMs))
        tvOriginalSize.text = getString(R.string.file_original_size, formatSize(fileOriginalSize))

        cardFileInfo.visibility = android.view.View.VISIBLE
        layoutCodec.visibility = android.view.View.VISIBLE
        layoutBitrate.visibility = android.view.View.VISIBLE
        tvEstimatedSize.visibility = android.view.View.VISIBLE
        btnCompress.visibility = android.view.View.VISIBLE

        updateEstimatedSize()
    }

    private fun updateEstimatedSize() {
        if (fileDurationMs <= 0) return
        val durationSec = fileDurationMs / 1000.0
        val estimatedBytes = (selectedBitrate / 8.0 * durationSec).toLong()
        tvEstimatedSize.text = getString(R.string.estimated_size, formatSize(estimatedBytes))
    }

    private fun resolveOutputDestination(fileName: String): OutputDestination? {
        val savedFolderUri = AppPrefs.getSaveFolderUri(this)

        if (savedFolderUri != null) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(this, savedFolderUri)
                if (treeDoc != null && treeDoc.canWrite()) {
                    val newFile = treeDoc.createFile("audio/mp4", fileName)
                    if (newFile != null) {
                        val pfd = contentResolver.openFileDescriptor(newFile.uri, "rw")
                        if (pfd != null) {
                            return OutputDestination(pfd, newFile.uri, fileName)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to use custom folder", e)
                // Папка недоступна — используем запасной вариант ниже
            }
        }

        return try {
            val outputDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            val file = File(outputDir, fileName)
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
            )
            OutputDestination(pfd, Uri.fromFile(file), fileName)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to create fallback output file", e)
            null
        }
    }

    private fun startCompression(uri: Uri) {
        val fileName = "compressed_${System.currentTimeMillis()}.m4a"
        val destination = resolveOutputDestination(fileName)

        if (destination == null) {
            Toast.makeText(this, "Не удалось создать выходной файл", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressBar)
        val tvPercent = dialogView.findViewById<android.widget.TextView>(R.id.tvProgressPercent)

        val progressDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val compressor = AudioCompressor(this)

        CoroutineScope(Dispatchers.IO).launch {
            compressor.compress(
                uri,
                destination.pfd,
                destination.uri,
                destination.displayName,
                selectedBitrate,
                object : AudioCompressor.ProgressListener {
                    override fun onProgress(percent: Int) {
                        CoroutineScope(Dispatchers.Main).launch {
                            progressBar.progress = percent
                            tvPercent.text = "$percent%"
                        }
                    }

                    override fun onComplete(outputUri: Uri, displayName: String) {
                        CoroutineScope(Dispatchers.Main).launch {
                            progressDialog.dismiss()

                            val finalSize = try {
                                contentResolver.openFileDescriptor(outputUri, "r")
                                    ?.use { it.statSize } ?: 0L
                            } catch (e: Exception) {
                                0L
                            }

                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.compression_complete, displayName) +
                                        "\n" + formatSize(finalSize),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onError(error: Exception) {
                        CoroutineScope(Dispatchers.Main).launch {
                            progressDialog.dismiss()
                            val errorText = "${error.javaClass.simpleName}: ${error.message ?: "нет описания"}"
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.compression_error, errorText),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "audio_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) {
                size = cursor.getLong(sizeIndex)
            }
        }
        return size
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) {
            String.format(Locale.getDefault(), "%.2f MB", mb)
        } else {
            String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }
}