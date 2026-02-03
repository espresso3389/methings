package com.example.androidvivepython.perm

import android.app.AlertDialog
import android.content.Context

class PermissionBroker(private val context: Context) {
    fun requestConsent(tool: String, detail: String, onResult: (Boolean) -> Unit) {
        val message = if (detail.isNotEmpty()) {
            "$tool: $detail"
        } else {
            tool
        }
        AlertDialog.Builder(context)
            .setTitle("Allow tool access?")
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ -> onResult(true) }
            .setNegativeButton("Deny") { _, _ -> onResult(false) }
            .setCancelable(false)
            .show()
    }
}
