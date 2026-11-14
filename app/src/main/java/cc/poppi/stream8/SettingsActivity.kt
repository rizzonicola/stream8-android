package cc.poppi.stream8

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import cc.poppi.stream8.adblock.AdBlockSettings
import cc.poppi.stream8.navigation.PolicyDefaults

/** Impostazioni minime: adblocker, ricarica Stream8, versione, regole di navigazione. */
class SettingsActivity : Activity() {

    /** Le Impostazioni native seguono la lingua scelta sul sito. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(NativeLocale.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = AdBlockSettings(this)

        findViewById<TextView>(R.id.adblock_desc).text =
            getString(R.string.adblock_desc, AdBlockSettings.REFRESH_DAYS)

        findViewById<Switch>(R.id.adblock_switch).apply {
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, checked -> settings.enabled = checked }
        }
        findViewById<Button>(R.id.reload_button).setOnClickListener { reloadStream8() }
        findViewById<Button>(R.id.rules_button).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.nav_rules_title)
                .setMessage(getString(R.string.nav_rules_body, PolicyDefaults.STREAM8_HOST, PolicyDefaults.TMDB_DOMAIN))
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    private fun reloadStream8() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RELOAD_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
