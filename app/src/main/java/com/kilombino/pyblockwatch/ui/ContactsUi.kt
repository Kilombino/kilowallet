package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.data.Contacts

/** "CONTACTS" next to PASTE: opens the saved contacts that fit [fits] and hands back the chosen one. */
@Composable
fun ContactsButton(accent: Color, fits: (Contacts.Kind) -> Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("CONTACTS", color = accent, style = MaterialTheme.typography.bodySmall) }
    if (open) ContactsDialog(accent, fits, onPick = { open = false; onPick(it) }, onDismiss = { open = false })
}

@Composable
private fun ContactsDialog(accent: Color, fits: (Contacts.Kind) -> Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Contacts.all(ctx)) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val usable = list.filter { fits(it.kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contacts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (usable.isEmpty()) Text(
                    if (list.isEmpty()) "No contacts yet. After you pay an Ark address, an XBT address, a " +
                        "reusable Lightning offer or a user@domain, the wallet offers to save it with a name."
                    else "None of your contacts can be paid from here.",
                    style = MaterialTheme.typography.bodySmall)
                usable.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c.dest) }.padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = TextMain)
                            Text(c.kind.label + " · " + shortAddress(c.dest), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                        }
                        if (deleting == c.dest) TextButton(onClick = {
                            Contacts.remove(ctx, c.dest); list = Contacts.all(ctx); deleting = null
                        }) { Text("DELETE?", color = Bad, style = MaterialTheme.typography.bodySmall) }
                        else TextButton(onClick = { deleting = c.dest }) { Text("✕", color = TextSoft) }
                    }
                }
                if (list.isNotEmpty()) Text("Contacts are saved in the Ark backup file, not in the recovery words.",
                    style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = accent) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/** The contact's name under a destination field, when that destination is saved. */
@Composable
fun ContactName(dest: String, accent: Color) {
    val ctx = LocalContext.current
    val c = remember(dest) { if (dest.isBlank()) null else Contacts.find(ctx, dest) } ?: return
    Text("→ " + c.name, style = MaterialTheme.typography.bodySmall, color = accent)
}

/**
 * Offers to save [dest] after a payment. Shows nothing when it can't be saved (a one-off
 * invoice) or is already a contact.
 */
@Composable
fun SaveContactPrompt(dest: String, accent: Color, onDone: (saved: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val skip = remember(dest) { !Contacts.savable(dest) || Contacts.find(ctx, dest) != null }
    if (skip) { LaunchedEffect(dest) { onDone(false) }; return }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Save as a contact?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Give " + shortAddress(dest) + " a name to pick it from CONTACTS next time. " +
                    "It goes into your Ark backup file.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it.take(60); error = null },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { Contacts.save(ctx, name, dest) }
                    .onSuccess { onDone(true) }
                    .onFailure { error = it.message }
            }) { Text("SAVE", color = accent) }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("NOT NOW", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}
