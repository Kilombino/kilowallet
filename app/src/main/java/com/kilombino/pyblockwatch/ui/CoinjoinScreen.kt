package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.coinjoin.CoinjoinHub
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.data.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * COINJOIN (advanced): your pools and what they need from you, the open pools on
 * relay.kilombino.com, and a form to open a new one. Joining and signing both unlock the
 * seed with the fingerprint; nothing else ever touches a key.
 */
@Composable
fun CoinjoinScreen(vm: WalletViewModel, accent: Color) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    val version by CoinjoinHub.version.collectAsState()
    // A heartbeat while the tab is open: the countdowns move, and a round's progress shows
    // even if a change slipped past the version flow.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2_000); tick++ } }
    val rev = version * 1_000_000 + tick
    val mine = remember(rev) { CoinjoinHub.states.value.toList() }
    var pools by remember { mutableStateOf<List<Protocol.Terms>?>(null) }
    var loadingPools by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf<Protocol.Terms?>(null) }
    var creating by remember { mutableStateOf(false) }
    // Test pools (from 1 000 sats) were for the betas; the release starts at 10 000.
    val testPools = false
    var joinPassword by remember { mutableStateOf("") }
    // Pull-to-refresh on this tab reloads the list of open pools.
    val refreshReq by CoinjoinHub.refreshRequests.collectAsState()

    fun reloadPools() {
        if (loadingPools) return
        loadingPools = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { CoinjoinHub.fetchPools() } }
            pools = r.getOrNull(); loadingPools = false
            r.exceptionOrNull()?.let { message = "Could not reach the relay: ${it.message}" }
        }
    }

    LaunchedEffect(refreshReq) { if (refreshReq > 0) reloadPools() }

    LaunchedEffect(Unit) {
        CoinjoinHub.load(ctx)
        if (CoinjoinHub.hasActive(ctx)) com.kilombino.pyblockwatch.coinjoin.CoinjoinService.start(ctx)
        reloadPools()
    }

    // Opt-in: until the user accepts the explainer, the tab shows only that.
    var enabled by remember { mutableStateOf(vm.coinjoinEnabled()) }
    val askNotif = rememberNotificationPermission()
    if (!enabled) {
        CoinjoinIntro(accent, onAccept = { vm.answerCoinjoin(true); enabled = true; askNotif() },
            onDecline = { vm.answerCoinjoin(false); com.kilombino.pyblockwatch.data.OpenTab.flow.value = com.kilombino.pyblockwatch.data.OpenTab.BTC },
            declineLabel = "NOT NOW")
        return
    }

    if (!vm.coinjoinSupported()) {
        Panel(accent = accent) {
            SectionLabel("Coinjoin", accent)
            Spacer(Modifier.height(8.dp))
            Explain("Coinjoin needs a spending wallet with native SegWit (bc1q) addresses: every coin and " +
                "every output in a round must look the same, or yours would stand out.")
        }
        return
    }

    Panel(accent = accent) {
        SectionLabel("Coinjoin", accent)
        Spacer(Modifier.height(8.dp))
        Explain("Mix a coin with other people's: everyone puts one coin in and gets back an output of " +
            "exactly the pool's amount, all identical, so nobody watching the chain can tell whose is whose. " +
            "Each person pays their own share of the fee from their change. No server ever holds your " +
            "coins: the relay only passes encrypted messages, and you sign only after checking your output.")
    }

    message?.let { m ->
        Panel(accent = Warn) {
            Text(m, color = Warn, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { message = null }) { Text("ok", color = TextSoft) }
        }
    }

    // ---- our pools
    if (mine.isNotEmpty()) {
        SectionLabel("Your pools", accent)
        mine.forEach { st -> MyPoolCard(vm, st, rev, accent, activity) { message = it } }
    }

    // ---- join / create sheets
    joining?.let { t ->
        if (t.private) Panel(accent = accent) {
            SectionLabel("🔒 Private pool", accent)
            Spacer(Modifier.height(6.dp))
            Explain("Its creator shares the password with the people invited. Without it the pool refuses you.")
            OutlinedTextField(value = joinPassword, onValueChange = { joinPassword = it },
                label = { Text("pool password", style = MaterialTheme.typography.bodySmall) },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }
        CoinPicker(vm, accent, "Join · ${groupSats(t.amount)} sats", t.amount, t.feeRate,
            onCancel = { joining = null; joinPassword = "" },
            onPicked = { u ->
                if (t.private && joinPassword.isEmpty()) { message = "Type the pool's password first."; return@CoinPicker }
                unlockAndPick(activity, vm, u, "Join coinjoin", "Unlock to prove you own the coin",
                    onPick = { pick ->
                        scope.launch {
                            runCatching { withContext(Dispatchers.Default) { CoinjoinHub.join(ctx, t, pick, joinPassword) } }
                                .onFailure { message = it.message }
                            joining = null; joinPassword = ""
                        }
                    }, onError = { message = it })
            })
        return
    }
    if (creating) {
        CreatePool(vm, accent, testPools, onCancel = { creating = false }) { amount, rate, minPeers, peers, hours, password, u ->
            unlockAndPick(activity, vm, u, "Open a coinjoin pool", "Unlock to put your coin in",
                onPick = { pick ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.Default) { CoinjoinHub.create(ctx, pick, amount, rate, peers, hours, minPeers, password) } }
                            .onSuccess { creating = false; reloadPools() }
                            .onFailure { message = it.message }
                    }
                }, onError = { message = it })
        }
        return
    }

    Button(
        onClick = { creating = true },
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    ) { Text("＋  OPEN A POOL", style = MaterialTheme.typography.titleMedium) }

    // ---- open pools
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Open pools", accent)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { reloadPools() }) {
            Text(if (loadingPools) "looking…" else "refresh", color = TextSoft, style = MaterialTheme.typography.bodySmall)
        }
    }
    // Only rounds still running hide their pool: one that ended or was left shows again to rejoin.
    val ours = mine.filter { it.phase !in setOf(PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED,
        PoolSession.Phase.CONFIRMED, PoolSession.Phase.BROADCAST) }.map { it.poolId }.toSet()
    val shown = pools.orEmpty().filter { it.id !in ours && (testPools || it.amount >= Protocol.MIN_AMOUNT) }
    if (pools != null && shown.isEmpty()) Explain("No open pools right now. Open one and others will be notified.")
    shown.forEach { t ->
        Panel(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (t.private) "🔒 " else "") + "${groupSats(t.amount)} sats" + if (t.amount < Protocol.MIN_AMOUNT) "  · TEST" else "",
                        style = MaterialTheme.typography.titleMedium, color = TextMain)
                    Text("${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${t.feeRate} sat/vB · closes in ${remaining(t.expiresAt)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    Text("joining costs you ${CoinjoinTx.feeShare(t.feeRate, true)} sats in fees " +
                        "(${CoinjoinTx.feeShare(t.feeRate, false)} with a coin of exactly ${groupSats(t.amount + CoinjoinTx.feeShare(t.feeRate, false))})",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                Button(
                    onClick = { joining = t },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("JOIN") }
            }
        }
    }

    Panel(accent = accent) {
        SectionLabel("How a round goes", accent)
        Spacer(Modifier.height(8.dp))
        Explain("1. Someone opens a pool with an amount. 2. People join, each proving they own a real coin " +
            "(one seat per coin, which stops one person from filling the pool alone). 3. With two or more in, " +
            "anyone can ask to close: the others accept or refuse; whoever does not answer in " +
            "${Protocol.VOTE_SECONDS / 60} minutes is left out. 4. Each wallet posts its mixed output with no name " +
            "on it, after a random wait. 5. Every wallet builds the same transaction, checks its own output and " +
            "change, and you sign with your fingerprint. 6. Once all have signed it goes out — signed so it is " +
            "valid only on this chain, never on the spamchain. Keep the app (or its notification) alive until then.")
    }
}

private fun remaining(at: Long): String {
    val s = at - System.currentTimeMillis() / 1000
    return when {
        s <= 0 -> "now"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun unlockAndPick(
    activity: FragmentActivity, vm: WalletViewModel, u: Scanner.SpendableUtxo, title: String, subtitle: String,
    onPick: (CoinjoinHub.Pick) -> Unit, onError: (String) -> Unit,
) {
    runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
        Biometric.authenticate(activity, title, subtitle, cipher,
            onSuccess = { authed -> runCatching { vm.coinjoinPick(authed, u) }.onSuccess(onPick).onFailure { onError(it.message ?: "could not unlock") } },
            onError = onError)
    }.onFailure { onError(it.message ?: "could not unlock") }
}

@Composable
private fun CoinPicker(
    vm: WalletViewModel, accent: Color, title: String, amount: Long, feeRate: Double,
    onCancel: () -> Unit, onPicked: (Scanner.SpendableUtxo) -> Unit,
) {
    var coins by remember { mutableStateOf<List<Scanner.SpendableUtxo>?>(null) }
    var chosen by remember { mutableStateOf<Scanner.SpendableUtxo?>(null) }
    LaunchedEffect(Unit) { coins = runCatching { vm.coinjoinCoins() }.getOrDefault(emptyList()) }
    val min = CoinjoinTx.minimumCoin(amount, feeRate)
    Panel(accent = accent) {
        SectionLabel(title, accent)
        Spacer(Modifier.height(8.dp))
        Explain("Pick ONE confirmed coin of at least ${groupSats(min)} sats. Its leftover comes back to a fresh " +
            "change address; the mixed output goes to another fresh address of this wallet.")
        Spacer(Modifier.height(8.dp))
        val list = coins
        when {
            list == null -> Text("reading your coins…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            list.none { it.value >= min } -> Text("No confirmed coin is big enough.", color = Warn, style = MaterialTheme.typography.bodySmall)
            // The exact coin first: it joins with no change, so the mixed output is tied to nothing.
            else -> list.filter { it.value >= min }.sortedBy { if (it.value == min) 0 else 1 }.forEach { u ->
                val sel = chosen == u
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (sel) accent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { chosen = u }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${groupSats(u.value)} sats", color = TextMain, style = MaterialTheme.typography.bodyMedium)
                        val ch = CoinjoinTx.change(u.value, amount, feeRate) ?: 0
                        val fee = u.value - amount - ch
                        when {
                            u.value == min -> Text("★ EXACT · no change · fee ${groupSats(fee)}",
                                color = Good, style = MaterialTheme.typography.bodySmall)
                            ch == 0L -> Text("no change, but ${groupSats(fee - (min - amount))} extra goes to the miners · fee ${groupSats(fee)}",
                                color = Warn, style = MaterialTheme.typography.bodySmall)
                            else -> Text("change ${groupSats(ch)} · fee ${groupSats(fee)}",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${u.txid.take(10)}…:${u.vout}", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sel) Text("✓", color = accent)
                }
            }
        }
        // The perfect coin: exactly the amount plus this pool's fee, so nobody gets change.
        val exact = amount + CoinjoinTx.feeShare(feeRate, false)
        var making by remember { mutableStateOf(false) }
        if (making) ExactCoinPanel(vm, accent, exact, feeRate) { making = false }
        else if (list != null && list.none { it.value == exact }) TextButton(onClick = { vm.resetSend(); vm.prepareExactCoin(exact, feeRate); making = true }) {
            Text("＋ PREPARE AN EXACT COIN OF ${groupSats(exact)} SATS", color = accent, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("CANCEL") }
            Button(onClick = { chosen?.let(onPicked) }, enabled = chosen != null,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("USE THIS COIN") }
        }
    }
}

@Composable
private fun CreatePool(
    vm: WalletViewModel, accent: Color, allowTest: Boolean, onCancel: () -> Unit,
    onCreate: (amount: Long, rate: Double, minPeers: Int, maxPeers: Int, hours: Int, password: String, coin: Scanner.SpendableUtxo) -> Unit,
) {
    var amountText by remember { mutableStateOf(if (allowTest) "1000" else "100000") }
    var rateText by remember { mutableStateOf("2") }
    var peersText by remember { mutableStateOf("5") }
    var minText by remember { mutableStateOf("2") }
    var password by remember { mutableStateOf("") }
    var hoursText by remember { mutableStateOf("6") }
    var pickCoin by remember { mutableStateOf(false) }
    val minAmount = if (allowTest) Protocol.TEST_MIN_AMOUNT else Protocol.MIN_AMOUNT
    val amount = amountText.filter { it.isDigit() }.toLongOrNull()
    val rate = rateText.replace(',', '.').toDoubleOrNull()
    val peers = peersText.toIntOrNull()
    val minPeers = minText.toIntOrNull()
    val hours = hoursText.toIntOrNull()
    val error = when {
        amount == null || amount < minAmount || amount > Protocol.MAX_AMOUNT ->
            "Amount: ${groupSats(minAmount)} to ${groupSats(Protocol.MAX_AMOUNT)} sats (1 BTC)."
        rate == null || rate < 1.0 || rate > 500.0 -> "Fee: 1 to 500 sat/vB."
        peers == null || peers !in Protocol.MIN_PEERS..Protocol.MAX_PEERS -> "Most people: ${Protocol.MIN_PEERS} to ${Protocol.MAX_PEERS}."
        minPeers == null || minPeers < Protocol.MIN_PEERS || minPeers > peers -> "Fewest people: ${Protocol.MIN_PEERS} to $peers (the most)."
        hours == null || hours !in 1..72 -> "Open for 1 to 72 hours."
        else -> null
    }
    if (pickCoin && error == null) {
        CoinPicker(vm, accent, "Your coin for the pool", amount!!, rate!!, onCancel = { pickCoin = false }) { u ->
            onCreate(amount, rate, minPeers!!, peers!!, hours!!, password, u)
        }
        return
    }
    Panel(accent = accent) {
        SectionLabel("Open a pool", accent)
        Spacer(Modifier.height(8.dp))
        @Composable fun field(label: String, v: String, set: (String) -> Unit) = OutlinedTextField(
            value = v, onValueChange = set, label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        field("amount per person (sats)", amountText) { amountText = it }
        amount?.let { Text("= ${"%.8f".format(it / 1e8)} BTC", color = TextFaint, style = MaterialTheme.typography.bodySmall) }
        field("fee rate (sat/vB)", rateText) { rateText = it }
        field("fewest people to close (${Protocol.MIN_PEERS}–${Protocol.MAX_PEERS - 1})", minText) { minText = it }
        field("most people (${Protocol.MIN_PEERS}–${Protocol.MAX_PEERS})", peersText) { peersText = it }
        field("open for (hours)", hoursText) { hoursText = it }
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("password (optional: makes it private 🔒)", style = MaterialTheme.typography.bodySmall) },
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        if (password.isNotEmpty()) Text("Only people you give this password to can join. It is not announced anywhere.",
            color = TextFaint, style = MaterialTheme.typography.bodySmall)
        if (amount != null && rate != null && rate >= 1.0) {
            val withChange = CoinjoinTx.feeShare(rate, true); val exact = CoinjoinTx.feeShare(rate, false)
            Text("Each person pays their own fee, the same however many join: $withChange sats with change, " +
                "$exact without. A coin of exactly ${groupSats(amount + exact)} sats leaves no change " +
                "(the best for privacy); a bit more than that, up to ${groupSats(amount + withChange + CoinjoinTx.DUST)}, " +
                "also goes in without change and the rest goes to the miners.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("CANCEL") }
            Button(onClick = { pickCoin = true }, enabled = error == null,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("CHOOSE COIN") }
        }
    }
}

@Composable
private fun MyPoolCard(
    vm: WalletViewModel, st: PoolSession.State,
    // The state is mutated in place: this changes with it, so the card is redrawn and not skipped.
    // It must be READ below: Compose leaves unused parameters out of its "did anything change" check.
    rev: Long, accent: Color, activity: FragmentActivity, onMessage: (String) -> Unit) {
    if (rev < 0) return
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val session = CoinjoinHub.session(st.poolId)
    val color = when (st.phase) {
        PoolSession.Phase.CONFIRMED -> Good
        PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> Bad
        PoolSession.Phase.SIGNING, PoolSession.Phase.VOTING -> Warn
        else -> accent
    }
    fun act(f: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(f) }.onFailure { onMessage(it.message ?: "failed") } }
    Panel(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${groupSats(st.terms.amount)} sats", style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
            Text((if (st.terms.private) "🔒 " else "") + (if (st.creator) "your pool" else "joined"), style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        val people = if (st.round.isNotEmpty()) st.round.size else st.seats.size
        Text(when (st.phase) {
            PoolSession.Phase.JOINING -> "Asking to join…"
            PoolSession.Phase.OPEN -> "Waiting for people · $people/${st.terms.maxPeers} · closes in ${remaining(st.terms.expiresAt)}"
            PoolSession.Phase.VOTING -> "Close now with $people people?"
            PoolSession.Phase.CLOSING -> "Closing with $people people · collecting outputs"
            PoolSession.Phase.SIGNING -> "Ready: sign within ${remaining(st.phaseDeadline)}"
            PoolSession.Phase.BROADCAST -> "Sent · waiting for the first confirmation"
            PoolSession.Phase.CONFIRMED -> "Confirmed ✅"
            PoolSession.Phase.ABORTED -> "Cancelled: ${st.reason}. Your coin did not move."
            PoolSession.Phase.REJECTED -> "Refused: ${st.reason}"
        }, style = MaterialTheme.typography.bodySmall, color = color)
        Text("your coin ${groupSats(st.seat.coin.value)} → ${groupSats(st.terms.amount)} mixed" +
            (if (st.seat.changeValue > 0) " + ${groupSats(st.seat.changeValue)} change" else "") +
            " · fee ${groupSats(st.seat.coin.value - st.terms.amount - st.seat.changeValue)}",
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(8.dp))

        @Composable fun btn(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) = Button(
            onClick = onClick, modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = if (primary) color else PanelSoft, contentColor = if (primary) Ink else color),
            shape = RoundedCornerShape(12.dp)) { Text(label) }

        when (st.phase) {
            PoolSession.Phase.OPEN -> {
                var confirmLeave by remember { mutableStateOf(false) }
                if (confirmLeave) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    title = { Text(if (st.creator) "End this pool?" else "Leave this pool?") },
                    text = { Text(if (st.creator)
                        "The round is cancelled for everyone in it, and they are told so. Nobody loses anything: no " +
                            "transaction exists until all have signed, so every coin stays where it is."
                        else "Your seat is freed and your coin is yours to spend again. Nothing was signed, so nothing moves.") },
                    confirmButton = { TextButton(onClick = { confirmLeave = false; act { session?.leave() } }) {
                        Text(if (st.creator) "END POOL" else "LEAVE", color = Bad) } },
                    dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("STAY", color = TextSoft) } },
                    containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (people >= st.terms.minPeers) btn("ASK TO CLOSE NOW", true, Modifier.weight(1f)) { act { session?.requestClose() } }
                    btn(if (st.creator) "END POOL" else "LEAVE", modifier = Modifier.weight(1f)) { confirmLeave = true }
                }
                if (people < st.terms.minPeers) Text("Closes once ${st.terms.minPeers} people are in.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall)
            }
            PoolSession.Phase.VOTING -> {
                val iAsked = st.voteBy == st.token?.let { Protocol.tokenHash(it) }
                if (iAsked || st.votedOn == st.voteId) Text("Waiting for the others to answer…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    btn("ACCEPT", true, Modifier.weight(1f)) { act { session?.vote(true) } }
                    btn("NOT YET", modifier = Modifier.weight(1f)) { act { session?.vote(false) } }
                }
            }
            PoolSession.Phase.SIGNING -> {
                val plan = session?.plan()
                if (st.postedSig) Text("Signed. Waiting for the others (${st.sigs.size}/${plan?.coins?.size ?: "?"})…",
                    color = TextSoft, style = MaterialTheme.typography.bodySmall)
                else {
                    plan?.let {
                        Explain("Checked: ${it.coins.size} people, ${it.outputs.count { o -> o.value == st.terms.amount }} identical outputs of " +
                            "${groupSats(st.terms.amount)} sats, one of them yours; " +
                            (if (st.seat.changeValue > 0) "your change is right" else "no change (an exact coin)") +
                            "; total fee ${groupSats(it.fee)} sats.")
                        Spacer(Modifier.height(6.dp))
                    }
                    btn("SIGN", true, Modifier.fillMaxWidth()) {
                        runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                            Biometric.authenticate(activity, "Sign the coinjoin", "Unlock to sign your coin's input", cipher,
                                onSuccess = { authed -> act { session?.sign(vm.coinjoinKey(authed, st.coinPath)) } },
                                onError = onMessage)
                        }.onFailure { onMessage(it.message ?: "could not unlock") }
                    }
                }
            }
            PoolSession.Phase.BROADCAST, PoolSession.Phase.CONFIRMED -> st.txid?.let { txid ->
                SelectionContainer { Text(txid, color = TextSoft, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { runCatching { uri.openUri("${vm.explorerFor(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B)}/tx/$txid") } }) {
                        Text("open in explorer", color = accent, style = MaterialTheme.typography.bodySmall)
                    }
                    if (st.phase == PoolSession.Phase.CONFIRMED)
                        TextButton(onClick = { CoinjoinHub.remove(ctx, st.poolId) }) { Text("remove", color = TextFaint) }
                }
            }
            PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED ->
                TextButton(onClick = { CoinjoinHub.remove(ctx, st.poolId) }) { Text("remove", color = TextFaint) }
            else -> {}
        }
    }
}

/**
 * Sends [exact] sats to a fresh address of this wallet, through the normal send review and
 * the fingerprint. Once it has a confirmation it shows up in the coin list, and joining with
 * it leaves no change at all, so the mixed output is tied to nothing.
 */
@Composable
private fun ExactCoinPanel(vm: WalletViewModel, accent: Color, exact: Long, feeRate: Double, onDone: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    val state by vm.state.collectAsState()
    Panel(accent = accent) {
        SectionLabel("Exact coin · ${groupSats(exact)} sats", accent)
        Spacer(Modifier.height(6.dp))
        Explain("A payment to yourself of exactly ${groupSats(exact)} sats at $feeRate sat/vB. It needs one confirmation " +
            "(about 10 minutes) before it can join; then it goes in with no change.")
        Spacer(Modifier.height(6.dp))
        when (val p = state.sendPhase) {
            is SendPhase.Review -> {
                Text("from ${p.draft.inputs.size} coin(s) · fee ${groupSats(p.draft.fee)} sats" +
                    (if (p.draft.change > 0) " · change ${groupSats(p.draft.change)}" else ""),
                    color = TextSoft, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                        Biometric.authenticate(activity, "Prepare the exact coin", "Unlock to sign the payment to yourself", cipher,
                            onSuccess = { authed -> vm.confirmSend(authed) }, onError = {})
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text("CONFIRM & SIGN") }
            }
            is SendPhase.Sent -> {
                Text("Sent ✓ — it will appear above after one confirmation.", color = Good, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.resetSend(); onDone() }) { Text("ok", color = TextSoft) }
            }
            is SendPhase.Failed -> {
                Text(p.message, color = Bad, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.resetSend(); onDone() }) { Text("close", color = TextSoft) }
            }
            else -> Text("preparing…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
        }
    }
}
