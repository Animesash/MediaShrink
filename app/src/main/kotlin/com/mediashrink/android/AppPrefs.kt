package com.mediashrink.android

import android.content.Context
import android.net.Uri

object AppPrefs {
    private const val PREFS_NAME = "mediashrink_prefs"
    private const val KEY_FIRST_LAUNCH = "is_first_launch"
    private const val KEY_SAVE_FOLDER_URI = "save_folder_uri"

    fun isFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchDone(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    fun getSaveFolderUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_SAVE_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    fun setSaveFolderUri(context: Context, uri: Uri?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVE_FOLDER_URI, uri?.toString()).apply()
    }
}