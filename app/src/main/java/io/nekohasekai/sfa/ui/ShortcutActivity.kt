package io.nekohasekai.sfa.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity

class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (ShortcutToggleDecision.resolveEntryAction(intent?.action)) {
            ShortcutEntryAction.CREATE_SHORTCUT -> {
                val resultIntent =
                    ShortcutManagerCompat.createShortcutResultIntent(
                        this,
                        ShortcutInfoCompat.Builder(this, ShortcutToggleDecision.SHORTCUT_TOGGLE_ID)
                            .setIntent(Intent(this, ShortcutToggleActivity::class.java).setAction(Intent.ACTION_MAIN))
                            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                            .setShortLabel(getString(R.string.action_toggle))
                            .build(),
                    )
                @Suppress("DEPRECATION")
                resultIntent.putExtra(
                    Intent.EXTRA_SHORTCUT_INTENT,
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER),
                )
                setResult(RESULT_OK, resultIntent)
            }

            ShortcutEntryAction.OPEN_MAIN_ACTIVITY -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }

            ShortcutEntryAction.IGNORE -> {}
        }
        finish()
    }
}
