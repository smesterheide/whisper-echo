package net.mesterheide.whisperecho

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            startService(Intent(this, SubtitleOverlayService::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()

        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, SubtitleOverlayService::class.java))
            finish()
        }
    }
}
