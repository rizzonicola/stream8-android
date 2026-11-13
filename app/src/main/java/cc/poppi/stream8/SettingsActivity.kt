package cc.poppi.stream8

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/** Impostazioni minime: adblocker, ricarica Stream8, versione, regole di navigazione. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)

        findViewById<Switch>(R.id.adblock_switch).apply {
            isChecked = prefs.adblockEnabled
            setOnCheckedChangeListener { _, checked -> prefs.adblockEnabled = checked }
        }
        findViewById<Button>(R.id.reload_button).setOnClickListener { reloadStream8() }
        findViewById<Button>(R.id.rules_button).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.nav_rules_title)
                .setMessage(R.string.nav_rules_body)
                .setPositiveButton(android.R.string.ok, null)
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
