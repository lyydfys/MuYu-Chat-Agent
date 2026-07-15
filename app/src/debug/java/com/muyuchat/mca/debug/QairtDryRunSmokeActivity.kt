package com.muyuchat.mca.debug

import android.os.Bundle

/**
 * Dedicated entry point for QAIRT package certification.  The debug manifest
 * assigns only this activity to :qairt_smoke; the inherited runner then passes
 * the isolated execution purpose to the engine.
 */
class QairtDryRunSmokeActivity : LocalChatSmokeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("smokeMode", "qairt_dry_run")
        super.onCreate(savedInstanceState)
    }
}
