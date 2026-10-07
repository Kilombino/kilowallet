package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.crypto.Bip39Wordlist

/** Whether [w] could still become a BIP-39 word (empty, a prefix of one, or one). */
private fun possible(w: String) = w.isEmpty() || Bip39Wordlist.WORDS.any { it.startsWith(w) }

/** The words in [words] as typed, or null when one is not a BIP-39 word. */
fun seedWordsOrNull(words: List<String>): List<String>? =
    words.map { it.trim().lowercase() }.takeIf { ws -> ws.all { it in Bip39Wordlist.WORDS } }

/**
 * The seed words as numbered boxes (12 or 24). Typing in a box suggests the words that start
 * with what is there, fewer with each letter (four letters always single one out); a tap fills
 * it in and moves on. A box that can no longer be any word turns red. Pasting the whole phrase
 * into one box spreads it over the boxes.
 */
@Composable
fun SeedWordsInput(words: SnapshotStateList<String>, accent: Color = Purple) {
    val focus = remember { List(24) { FocusRequester() } }
    var focused by remember { mutableIntStateOf(-1) }

    fun setCount(n: Int) {
        while (words.size < n) words.add("")
        while (words.size > n) words.removeAt(words.size - 1)
    }

    fun type(i: Int, raw: String) {
        val parts = raw.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size > 1) {
            // A pasted phrase: fill from this box on, growing to 24 if it needs to.
            if (i + parts.size > words.size && i + parts.size <= 24) setCount(24)
            parts.forEachIndexed { k, p -> if (i + k < words.size) words[i + k] = p }
            return
        }
        words[i] = raw.lowercase().filter { it in 'a'..'z' }
    }

    fun pick(i: Int, w: String) {
        words[i] = w
        if (i + 1 < words.size) runCatching { focus[i + 1].requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 12 or 24 words.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Number of words:", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            listOf(12, 24).forEach { n ->
                val sel = words.size == n
                Text("$n", style = MaterialTheme.typography.titleSmall,
                    color = if (sel) Ink else accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) accent else PanelSoft)
                        .clickable { setCount(n) }.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        for (row in 0 until words.size / 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    val w = words[i]
                    val bad = !possible(w)
                    OutlinedTextField(
                        value = w, onValueChange = { type(i, it) },
                        label = { Text("${i + 1}", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true, isError = bad,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            imeAction = if (i + 1 < words.size) ImeAction.Next else ImeAction.Done),
                        keyboardActions = KeyboardActions(onNext = {
                            // Enter with a single match left takes it.
                            val m = Bip39Wordlist.WORDS.filter { it.startsWith(words[i]) }
                            if (words[i].isNotEmpty() && m.size == 1) words[i] = m[0]
                            runCatching { focus[i + 1].requestFocus() }
                        }),
                        modifier = Modifier.weight(1f).focusRequester(focus[i])
                            .onFocusChanged { if (it.isFocused) focused = i },
                    )
                }
            }
            // Suggestions under the row being typed in.
            val f = focused
            if (f in words.indices && f / 2 == row) {
                val typed = words[f]
                val matches = if (typed.isEmpty()) emptyList() else Bip39Wordlist.WORDS.filter { it.startsWith(typed) }
                if (matches.isNotEmpty() && !(matches.size == 1 && matches[0] == typed)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        matches.take(12).forEach { m ->
                            Text(m, style = MaterialTheme.typography.bodyMedium, color = Ink,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(accent)
                                    .clickable { pick(f, m) }.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        if (matches.size > 12) Text("+${matches.size - 12}", color = TextFaint,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp).width(40.dp))
                    }
                }
            }
        }
        // Red message for any box that cannot be a word.
        val wrong = words.withIndex().filter { (_, w) -> !possible(w) }.map { it.index + 1 }
        if (wrong.isNotEmpty()) Text(
            (if (wrong.size == 1) "Word ${wrong[0]} is" else "Words ${wrong.joinToString(", ")} are") +
                " not in the BIP-39 word list. Check the spelling.",
            color = Bad, style = MaterialTheme.typography.bodySmall)
    }
}
