package com.muyuchat.mca

import android.app.Application

class McaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The signer set is process-local and fail-closed if Android cannot expose it.
        ImagePromptLanguageProofTrust.initialize(this)
    }
}

