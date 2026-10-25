package com.kilombino.pyblockwatch.widget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.kilombino.pyblockwatch.ui.MainActivity
import com.kilombino.pyblockwatch.ui.PanelBg
import com.kilombino.pyblockwatch.ui.Purple
import com.kilombino.pyblockwatch.ui.PyBlockWatchTheme
import com.kilombino.pyblockwatch.ui.TextMain
import com.kilombino.pyblockwatch.ui.TextSoft
import com.kilombino.pyblockwatch.ui.i18n.I18n
import com.kilombino.pyblockwatch.ui.i18n.Text

/**
 * Every tap on the widget lands here first: it says what is about to open (the wallet, the
 * converter, or a web page with its address) and opens it only on OPEN, so a stray touch on the
 * home screen never opens anything.
 */
class WidgetOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_APP
        val url = intent.getStringExtra(EXTRA_URL)
        val what = intent.getStringExtra(EXTRA_WHAT) ?: ""
        val (title, body) = when (target) {
            TARGET_CONVERTER -> "Open the converter?" to "The BTC, sats, Poolcoins, EUR and USD converter, over the home screen."
            TARGET_URL -> "Open this page?" to "$what\n\n$url"
            else -> "Open Kilowallet?" to "The wallet app."
        }
        setContent {
            PyBlockWatchTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(title) },
                    text = { Text(body) },
                    confirmButton = { TextButton(onClick = { open(target, url); finish() }) { Text("OPEN", color = Purple) } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("CANCEL", color = TextSoft) } },
                    containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                )
            }
        }
    }

    private fun open(target: String, url: String?) {
        runCatching {
            when (target) {
                TARGET_CONVERTER -> startActivity(Intent(this, com.kilombino.pyblockwatch.ui.ConverterActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                TARGET_URL -> if (url != null && url.startsWith("https://"))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    companion object {
        const val EXTRA_TARGET = "target"
        const val EXTRA_URL = "url"
        const val EXTRA_WHAT = "what"
        const val TARGET_APP = "app"
        const val TARGET_CONVERTER = "converter"
        const val TARGET_URL = "url"
    }
}
