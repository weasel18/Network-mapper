package com.networkmapper.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.networkmapper.app.util.CrashLog

/** After a crash, shows what went wrong once, with Share / Copy so it can be sent for fixing. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report
    if (text != null) CrashReport(text) {
        CrashLog.clear(context)
        report = null
    }
}

@Composable
private fun CrashReport(text: String, done: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = done,
        title = { Text("The app closed unexpectedly last time") },
        text = {
            Column {
                Text(
                    "Sorry about that. Sharing these details helps get it fixed. They contain no map data or keys.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Network Mapper crash report")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                    done()
                }) { Text("Share") }
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Network Mapper crash report", text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    done()
                }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = done) { Text("Dismiss") } },
    )
}
