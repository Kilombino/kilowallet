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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.kilombino.pyblockwatch.ui.i18n.Text
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
import com.kilombino.pyblockwatch.coinjoin.Destination
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.data.Scanner
import com.kilombino.pyblockwatch.crypto.ScriptType
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
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
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(2_000); if (com.kilombino.pyblockwatch.ui.AppVisible.value) tick++ }
    }
    val rev = version * 1_000_000 + tick
    val mine = remember(rev) { CoinjoinHub.states.value.toList() }
    var pools by remember { mutableStateOf<List<Protocol.Terms>?>(null) }
    var loadingPools by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf<Protocol.Terms?>(null) }
    // Another seat in one of our pools: the seat it is for.
    var extraFor by remember { mutableStateOf<PoolSession.State?>(null) }
    var creating by remember { mutableStateOf(false) }
    // 0 JOIN, 1 IN PROGRESS, 2 HISTORY: a round going opens on IN PROGRESS.
    var tab by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(if (CoinjoinHub.states.value.any { it.phase !in setOf(PoolSession.Phase.CONFIRMED,
                PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED) } ||
            com.kilombino.pyblockwatch.coinjoin.PerfectCoin.hasWaiting(ctx)) 1 else 0)
    }
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
            // A failed look keeps the list it had: an empty screen would read as "no pools".
            r.getOrNull()?.let { pools = it }; loadingPools = false
            r.exceptionOrNull()?.let { message = "Could not reach the relay: ${it.message}" }
        }
    }

    LaunchedEffect(refreshReq) { if (refreshReq > 0) reloadPools() }

    LaunchedEffect(Unit) {
        CoinjoinHub.load(ctx)
        if (CoinjoinHub.hasActive(ctx)) com.kilombino.pyblockwatch.coinjoin.CoinjoinService.start(ctx)
        // The lock-screen cards follow the rounds as they are now.
        com.kilombino.pyblockwatch.coinjoin.CoinjoinService.refresh(ctx)
        reloadPools()
        // While the tab is open, the open pools are looked up again every minute.
        while (true) { kotlinx.coroutines.delay(60_000); if (com.kilombino.pyblockwatch.ui.AppVisible.value) reloadPools() }
    }

    // Opt-in: until the user accepts the explainer, the tab shows only that.
    var enabled by remember { mutableStateOf(vm.coinjoinEnabled()) }
    val askNotif = rememberNotificationPermission()
    if (!enabled) {
        CoinjoinIntro(accent, onAccept = { vm.answerCoinjoin(true); enabled = true; askNotif() },
            onDecline = { vm.answerCoinjoin(false); com.kilombino.pyblockwatch.data.OpenTab.flow.value = com.kilombino.pyblockwatch.data.OpenTab.BTC },
            declineLabel = "NOT NOW",
            // Looking at the pools and the rounds never needs the notifications.
            onLookOnly = { vm.answerCoinjoin(true, notify = false); enabled = true })
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

    // The keys of a round on its own live in memory only: once the app was closed (or Android
    // stopped it) its rounds ask again. With "on its own" set, one fingerprint resumes them all.
    val asking = mine.filter { st ->
        st.phase in setOf(PoolSession.Phase.OPEN, PoolSession.Phase.VOTING, PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING) &&
            !st.postedSig && !CoinjoinHub.isAuto(st.key)
    }
    if (CoinjoinHub.autoDefault(ctx) && asking.isNotEmpty()) Panel(accent = accent) {
        Text("${asking.size} round" + (if (asking.size == 1) " is" else "s are") + " waiting for you (joined before rounds kept their keys, or asking at each step).",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = {
            runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                Biometric.authenticate(activity, "Run the rounds on their own", "Unlock once for all of them", cipher,
                    onSuccess = { authed ->
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.Default) {
                                    val all = vm.coinjoinKeys(authed, asking.flatMap { it.coinPaths })
                                    var i = 0
                                    asking.forEach { st ->
                                        val n = st.coinPaths.size
                                        CoinjoinHub.startAuto(st.key, all.subList(i, i + n)); i += n
                                    }
                                }
                            }.onSuccess { message = "All rounds run on their own again." }
                             .onFailure { message = it.message ?: "could not unlock" }
                        }
                    },
                    onError = { message = it })
            }.onFailure { message = it.message ?: "could not unlock" }
        }) { Text("RUN ALL ON THEIR OWN", color = accent) }
    }

    message?.let { m ->
        Panel(accent = Warn) {
            Text(m, color = Warn, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { message = null }) { Text("ok", color = TextSoft) }
        }
    }

    message?.let { m ->
        Panel(accent = Warn) {
            Text(m, color = Warn, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { message = null }) { Text("ok", color = TextSoft) }
        }
    }

    val finishedPhases = setOf(PoolSession.Phase.CONFIRMED, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED)
    val active = mine.filter { it.phase !in finishedPhases }
    val finished = mine.filter { it.phase in finishedPhases }

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
        CoinPicker(vm, accent, "Join · ${poolAmount(t)} sats${poolKind(t)}", t.amount, t.feeRate, t.script, t.maxInputs, t.outputs,
            exactFor = WalletViewModel.ExactFor(t.id, joinPassword.ifEmpty { null }, null, com.kilombino.pyblockwatch.coinjoin.PerfectCoin.agreed(t)),
            onCancel = { joining = null; joinPassword = "" },
            perpetualOk = t.outputs == 1,
            onPicked = { u, dest, cap ->
                if (t.private && joinPassword.isEmpty()) { message = "Type the pool's password first."; return@CoinPicker }
                scope.launch {
                    val scripts = runCatching { destinationScripts(ctx, dest, t.script, t.outputs) }
                        .getOrElse { message = it.message ?: "could not find unused addresses"; return@launch }
                    unlockAndPick(activity, vm, u, t.outputs, "Join coinjoin", "Unlock to prove you own the coins", scripts, cap != null,
                        onPick = { pick ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.Default) {
                                    val perpId = cap?.let { com.kilombino.pyblockwatch.coinjoin.Perpetual.newId() }
                                    CoinjoinHub.join(ctx, t, pick, joinPassword, CoinjoinHub.autoDefault(ctx), perpId = perpId)
                                        .also { st -> beginPerpetual(ctx, st, pick, cap) }
                                } }
                                    .onSuccess { st -> Destination.remember(ctx, st.key, dest); tab = 1 }.onFailure { message = it.message }
                                joining = null; joinPassword = ""
                            }
                        }, onError = { message = it })
                }
            })
        return
    }
    // Another seat in a pool this phone is already in, with another coin of its own.
    extraFor?.let { mineSt ->
        val t = mineSt.terms
        // A member of a private pool types its password again (only the creator keeps the pool's key).
        val needsPassword = t.private && !mineSt.creator
        Panel(accent = Warn) {
            Text(ANOTHER_SEAT_NOTE, color = Warn, style = MaterialTheme.typography.bodySmall)
            if (needsPassword) OutlinedTextField(value = joinPassword, onValueChange = { joinPassword = it },
                label = { Text("pool password", style = MaterialTheme.typography.bodySmall) },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }
        CoinPicker(vm, accent, "Another seat · ${poolAmount(t)} sats${poolKind(t)}", t.amount, t.feeRate, t.script, t.maxInputs, t.outputs,
            exactFor = WalletViewModel.ExactFor(t.id, joinPassword.ifEmpty { null }, null, com.kilombino.pyblockwatch.coinjoin.PerfectCoin.agreed(t)),
            onCancel = { extraFor = null; joinPassword = "" },
            perpetualOk = false, noExactCoin = true,
            onPicked = { u, dest, _ ->
                if (needsPassword && joinPassword.isEmpty()) { message = "Type the pool's password first."; return@CoinPicker }
                scope.launch {
                    val scripts = runCatching { destinationScripts(ctx, dest, t.script, t.outputs) }
                        .getOrElse { message = it.message ?: "could not find unused addresses"; return@launch }
                    unlockAndPick(activity, vm, u, t.outputs, "Join coinjoin", "Unlock to prove you own the coins", scripts, false,
                        onPick = { pick ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.Default) {
                                    CoinjoinHub.join(ctx, t, pick, joinPassword.ifEmpty { null }, CoinjoinHub.autoDefault(ctx),
                                        passwordKey = mineSt.pwKey?.let { com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(it) })
                                } }
                                    .onSuccess { st -> Destination.remember(ctx, st.key, dest); tab = 1 }.onFailure { message = it.message }
                                extraFor = null; joinPassword = ""
                            }
                        }, onError = { message = it })
                }
            })
        return
    }
    // A payment from SEND to be paid through a new pool: its sheet opens straight away.
    val payment by com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment.pending.collectAsState()
    if (creating || payment != null) {
        val pay = payment
        CreatePool(vm, accent, testPools, pay, onCancel = {
            creating = false; com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment.pending.value = null
            // A payment goes back to SEND as it was typed, nothing lost.
            pay?.draft?.let { com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment.backToSend.value = it }
        }) { amount, rate, minPeers, peers, hours, password, maxInputs, outputs, u, dest, cap ->
            scope.launch {
                val type = if (u.first().type == ScriptType.P2TR) ScriptType.P2TR else ScriptType.P2WPKH
                // A payment's outputs are the recipients' addresses as typed; nothing is derived for them.
                val scripts = if (dest == Destination.Kind.PAY) pay?.scripts ?: run { message = "the payment was cancelled"; return@launch }
                    else runCatching { destinationScripts(ctx, dest, type, outputs) }
                    .getOrElse { message = it.message ?: "could not find unused addresses"; return@launch }
                // A payment pool (v5): one output per payment, whatever the others' outputs are.
                unlockAndPick(activity, vm, u, scripts?.size ?: outputs, "Open a coinjoin pool", "Unlock to put your coins in", scripts, cap != null,
                    onPick = { pick ->
                        scope.launch {
                            runCatching { withContext(Dispatchers.Default) {
                                val perpId = cap?.let { com.kilombino.pyblockwatch.coinjoin.Perpetual.newId() }
                                CoinjoinHub.create(ctx, pick, amount, rate, peers, hours, minPeers, password,
                                    maxInputs, CoinjoinHub.autoDefault(ctx), outputs, pay?.payments.orEmpty(), perpId = perpId)
                                    .also { st -> beginPerpetual(ctx, st, pick, cap) }
                            } }
                                .onSuccess { st ->
                                    Destination.remember(ctx, st.key, dest); creating = false; tab = 1; reloadPools()
                                    com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment.pending.value = null
                                }
                                .onFailure { message = it.message }
                        }
                    }, onError = { message = it })
            }
        }
        return
    }

    val waitingPerfect by com.kilombino.pyblockwatch.coinjoin.PerfectCoin.waiting.collectAsState()
    LaunchedEffect(rev / 1_000_000) { com.kilombino.pyblockwatch.coinjoin.PerfectCoin.load(ctx) }
    // Perpetual coins: between rounds on IN PROGRESS, stopped ones in HISTORY (their rounds have cards of their own).
    val remixes by com.kilombino.pyblockwatch.coinjoin.Perpetual.list.collectAsState()
    LaunchedEffect(rev / 500_000) { withContext(Dispatchers.IO) { com.kilombino.pyblockwatch.coinjoin.Perpetual.load(ctx) } }
    val remixWaiting = remixes.filter { it.state == com.kilombino.pyblockwatch.coinjoin.Remix.State.WAITING }
    val remixStopped = remixes.filter { it.state == com.kilombino.pyblockwatch.coinjoin.Remix.State.STOPPED && it.seat == null }
    // JOIN · IN PROGRESS · HISTORY: rounds going are shown first when there are any.
    CoinjoinTabs(tab, active.size + remixWaiting.size, accent) { tab = it }
    // A card opens by itself while its round waits on you; a tap opens or folds it.
    val unfolded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    @Composable fun card(st: PoolSession.State) = RoundCard(vm, st, rev, accent, activity,
        open = unfolded[st.key] ?: needsMe(st), onToggle = { unfolded[st.key] = !(unfolded[st.key] ?: needsMe(st)) },
        onAnotherSeat = { extraFor = st; joinPassword = "" }) { message = it }

    if (tab == 1) {
        val perfect = waitingPerfect
        if (active.isEmpty() && perfect.isEmpty() && remixWaiting.isEmpty()) Explain("No rounds going. Join a pool, or open your own, in JOIN.")
        perfect.forEach { w -> PerfectCoinCard(w, accent, vm) }
        remixWaiting.forEach { r -> PerpetualCard(vm, r, accent, activity, embedded = false) { message = it } }
        active.forEach { card(it) }
        return
    }
    if (tab == 2) {
        remixStopped.forEach { r -> PerpetualCard(vm, r, accent, activity, embedded = false) { message = it } }
        if (finished.isEmpty()) Explain("Confirmed and cancelled rounds show here, for a week.")
        else {
            finished.forEach { card(it) }
            TextButton(onClick = { finished.forEach { CoinjoinHub.remove(ctx, it.key) } }) {
                Text("remove all", color = TextFaint, style = MaterialTheme.typography.bodySmall)
            }
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
        PoolSession.Phase.CONFIRMED, PoolSession.Phase.BROADCAST) }.map { it.poolId }.toSet() +
        // A perfect coin being made for a pool: it is already in progress, not one to join again.
        waitingPerfect.mapNotNull { it.poolId }
    val shown = pools.orEmpty().filter { it.id !in ours && (testPools || !Protocol.isTest(it)) }
    if (pools != null && shown.isEmpty()) Explain("No open pools right now. Open one and others will be notified.")
    shown.forEach { t ->
        Panel(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (t.private) "🔒 " else "") + "${poolAmount(t)} sats" + if (Protocol.isTest(t)) "  · TEST POOL" else "",
                        style = MaterialTheme.typography.titleMedium, color = TextMain)
                    Text(poolTypeLine(t), style = MaterialTheme.typography.bodySmall, color = accent)
                    Text("${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${t.feeRate} sat/vB · closes in ${remaining(t.expiresAt)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    val fs = CoinjoinTx.feeShare(t.feeRate, false, 1, t.script, t.outputs)
                    Text("joining costs you ${CoinjoinTx.feeShare(t.feeRate, true, 1, t.script, t.outputs)} sats in fees with one coin " +
                        "($fs with a coin of exactly ${groupSats(t.mixTotal + fs)})",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    if (t.script == ScriptType.P2TR && !vm.taprootPools())
                        Text("Taproot pool: turn on Taproot addresses in settings to join it.",
                            style = MaterialTheme.typography.bodySmall, color = Warn)
                }
                Button(
                    enabled = t.script != ScriptType.P2TR || vm.taprootPools(),
                    onClick = { joining = t },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("JOIN") }
            }
        }
    }

    Panel(accent = accent) {
        SectionLabel("Coinjoin", accent)
        Spacer(Modifier.height(8.dp))
        Explain("Mix your coins with other people's: everyone puts one or more coins in and gets back an output of " +
            "exactly the pool's amount, all identical, so nobody watching the chain can tell whose is whose. " +
            "A pool is either native SegWit (bc1q) or Taproot (bc1p), never both, so no coin stands out. " +
            "Each person pays their own share of the fee from their change. No server ever holds your " +
            "coins: the relay only passes encrypted messages, and you sign only after checking your output." +
            "\n\nPools marked \"blind outputs\" (Kilojoin v3, every pool this version opens): each output is signed blindly " +
            "by the pool's creator and handed in from a fresh key over a connection of its own, so neither the relay, nor the other " +
            "members, nor the creator can tell whose output is whose, and only the creator's own key runs the pool. In older pools " +
            "the relay's operator together with one member could tell. Either way the relay sees which connection sends what " +
            "(over Tor only when Tor is already on).")
    }

    // How rounds run (on its own or asking), and room to run in the background, in sight.
    AutoPanel(accent)
    TelegramAlertsPanel(accent, mine)

    Panel(accent = accent) {
        SectionLabel("How a round goes", accent)
        Spacer(Modifier.height(8.dp))
        Explain("1. Someone opens a pool with an amount and an address type. 2. People join, each proving they own " +
            "real coins of that type (one seat per coin: a coin can't sit twice, but one person with several coins can take " +
            "several seats, so the others in a pool are not proven to be other people). 3. With two or more in, " +
            "anyone can ask to close: the others accept or refuse; whoever does not answer in " +
            "${Protocol.VOTE_SECONDS / 60} minutes is left out. 4. Each wallet posts its mixed output with no name " +
            "on it, after a random wait. 5. Every wallet builds the same transaction, checks its own output and " +
            "change, and signs: on its own if you chose so above (one fingerprint when joining), otherwise with " +
            "your fingerprint. 6. Once all have signed it goes out — signed so it is valid only on this chain, " +
            "never on the spamchain. Keep the app (or its notification) alive until then.")
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

/** "4 × 1 000 000" for a pool of several outputs each (v4), the plain amount otherwise. */
private fun poolAmount(t: Protocol.Terms): String =
    if (t.outputs > 1) "${t.outputs} × ${groupSats(t.amount)}" else groupSats(t.amount)

/** " · Taproot" for a Taproot pool. */
private fun poolKind(t: Protocol.Terms): String = if (t.script == ScriptType.P2TR) " · Taproot" else ""

/** What kind of pool it is, in one line. */
private fun poolTypeLine(t: Protocol.Terms): String =
    (if (t.script == ScriptType.P2TR) "Taproot (bc1p) coins only" else "Native SegWit (bc1q) coins only") +
        (if (t.maxInputs > 1) " · up to ${t.maxInputs} coins each" else " · one coin each") +
        (if (t.v5) " · " + (if (t.outputs > 1) "a payment pool (v5): the creator pays ${t.payments.size} recipient(s); you get ${groupSats(t.mixTotal)} in ${t.outputs} equal outputs"
            else "a payment pool (v5): the creator pays ${t.payments.size} recipient(s); you get ${groupSats(t.mixTotal)} in one output")
        else if (t.outputs > 1) " · ${t.outputs} outputs each (v4)" else if (t.v3) " · blind outputs (v3)" else "")

/**
 * The mixed outputs' scripts for [dest]: null for the hot wallet (its own fresh addresses, picked
 * with the keys), else unused addresses of that wallet, asked to the server (off the main thread).
 */
private suspend fun destinationScripts(ctx: android.content.Context, dest: Destination.Kind, type: ScriptType, outputs: Int): List<ByteArray>? =
    if (dest == Destination.Kind.HOT) null
    else withContext(Dispatchers.IO) { Destination.scripts(ctx, dest, type, outputs) }

/**
 * A perpetual coinjoin from the round [st] just joined or opened with [pick], when [cap] (its fee cap) is
 * set: its keys for the rounds to come are kept, and it goes on by itself once the round confirms.
 */
private fun beginPerpetual(ctx: android.content.Context, st: PoolSession.State, pick: CoinjoinHub.Pick, cap: Double?) {
    if (cap == null || st.perpId == null) return
    val branch = pick.perpBranch ?: error("the wallet's keys were not unlocked")
    require(pick.mixIndex >= 0 && st.terms.outputs == 1) { "a perpetual coinjoin needs one output of this wallet" }
    // Pools it opens later last as long as the one opened here did; joined, the usual 6 hours.
    val hours = if (st.creator) ((st.terms.expiresAt - st.created + 1800) / 3600).toInt() else 6
    com.kilombino.pyblockwatch.coinjoin.Perpetual.start(ctx, st.key, pick.type, branch, pick.mixIndex, pick.mixScripts.first(), st.terms, cap, st.perpId, hours)
}

/** Next to "ask to close" and "leave" while this phone has more than one seat in a pool, and before taking one. */
private const val ANOTHER_SEAT_NOTE = "Another seat of yours counts as one more member: the others get less privacy than they see, " +
    "and spending both results together later links them."

private fun unlockAndPick(
    activity: FragmentActivity, vm: WalletViewModel, u: List<Scanner.SpendableUtxo>, outputs: Int, title: String, subtitle: String,
    mixScripts: List<ByteArray>?, perpetual: Boolean, onPick: (CoinjoinHub.Pick) -> Unit, onError: (String) -> Unit,
) {
    runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
        Biometric.authenticate(activity, title, subtitle, cipher,
            // The seed (PBKDF2) and the keys are worked out off the main thread: done on it, a
            // slow phone showed "app isn't responding" here.
            onSuccess = { authed ->
                activity.lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.Default) { vm.coinjoinPick(authed, u, outputs, mixScripts, perpetual) } }
                        .onSuccess(onPick).onFailure { onError(it.message ?: "could not unlock") }
                }
            },
            onError = onError)
    }.onFailure { onError(it.message ?: "could not unlock") }
}

@Composable
private fun CoinPicker(
    vm: WalletViewModel, accent: Color, title: String, amount: Long, feeRate: Double,
    type: ScriptType, maxInputs: Int,
    /** Mixed outputs of [amount] each person gets (v4: 2 to 5). */
    outputs: Int,
    /** The pool a perfect coin made here is for: it joins (or opens) it by itself once confirmed. */
    exactFor: WalletViewModel.ExactFor,
    onCancel: () -> Unit,
    /** Coins ticked already (coin control in SEND, for a payment): the ones that fit are preselected. */
    initial: List<Scanner.SpendableUtxo> = emptyList(),
    /** A payment through the pool: the outputs go to its recipients, and there is no perfect coin to make. */
    payment: Boolean = false,
    /** Outputs our fee share pays for: [outputs], or (v5 payment pool, creating it) the number of payments. */
    feeOutputs: Int = outputs,
    /** Opening a payment pool (v5): our outputs are the payments themselves. */
    paymentPool: Boolean = false,
    /** A pool of one output each: the perpetual coinjoin can be chosen (for the hot wallet's outputs). */
    perpetualOk: Boolean = false,
    /** No exact coin to prepare from here (another seat). */
    noExactCoin: Boolean = false,
    /** The coins, where the outputs go, and the perpetual coinjoin's fee cap (null: not perpetual). */
    onPicked: (List<Scanner.SpendableUtxo>, Destination.Kind, Double?) -> Unit,
) {
    val tag = if (type == ScriptType.P2TR) ScriptType.P2TR else null
    val ctx = LocalContext.current
    // Where the mixed outputs go: this wallet by default; another one only when set up here and of the pool's type.
    val dests = remember(type, payment) { if (payment) listOf(Destination.Kind.PAY) else Destination.options(ctx, type) }
    var dest by remember(type, payment) { mutableStateOf(if (payment) Destination.Kind.PAY else Destination.Kind.HOT) }
    val kind = if (type == ScriptType.P2TR) "Taproot (bc1p)" else "native SegWit (bc1q)"
    var coins by remember { mutableStateOf<List<Scanner.SpendableUtxo>?>(null) }
    // The mixed ones, worked out once per list: not again for every coin on every redraw of the tab.
    val mixedCoins = remember(coins) { vm.mixedOutpoints(coins.orEmpty()) }
    val chosen = remember { mutableStateListOf<Scanner.SpendableUtxo>() }
    var reload by remember { mutableStateOf(0) }
    var leftOut by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        coins = runCatching { vm.coinjoinCoins(tag) }.getOrDefault(emptyList())
        // Coins ticked before: those that can go in (confirmed, of the pool's type, free) are ticked here too.
        if (reload == 0 && initial.isNotEmpty()) {
            val keys = initial.map { "${it.txid}:${it.vout}" }.toSet()
            val fit = coins.orEmpty().filter { "${it.txid}:${it.vout}" in keys }.take(maxInputs)
            chosen.clear(); chosen.addAll(fit); leftOut = initial.size - fit.size
        }
    }
    // What goes into the mix: the amount, or (v4) the amount times the outputs each.
    val mixTotal = amount * outputs
    val min = mixTotal + CoinjoinTx.feeShare(feeRate, false, 1, type, feeOutputs)
    // The perfect coin: exactly that plus this pool's fee, so nobody gets change.
    val exact = min
    var making by remember { mutableStateOf(false) }
    var offerPerfect by remember { mutableStateOf(false) }
    var perpetual by remember { mutableStateOf(false) }
    var capText by remember { mutableStateOf(fmtRate(feeRate)) }
    val cap = if (perpetualOk && perpetual && dest == Destination.Kind.HOT) capText.replace(',', '.').toDoubleOrNull() else null
    val capBad = perpetualOk && perpetual && dest == Destination.Kind.HOT && (cap == null || cap < 1.0 || cap > 500.0)
    val total = chosen.sumOf { it.value }
    val change = if (chosen.isEmpty()) null else CoinjoinTx.changeOf(total, mixTotal, feeRate, chosen.size, type, feeOutputs)
    val fee = if (change == null) 0L else total - mixTotal - change
    val mixedLine = if (outputs > 1) "$outputs × ${groupSats(amount)}" else groupSats(amount)

    if (offerPerfect) AlertDialog(
        onDismissRequest = { offerPerfect = false },
        title = { Text("Make the perfect coin?") },
        text = { Text("With ${if (chosen.size == 1) "this coin" else "these ${chosen.size} coins"} you get ${groupSats(change ?: 0)} sats " +
            "of change back, and that change shows on the chain next to your coins. A single coin of exactly " +
            "${groupSats(exact)} sats goes in with no change at all, so nothing ties your mixed output to anything. " +
            "You can make it now: a payment to yourself (from any of your coins, bc1q and bc1p together), " +
            "which needs one confirmation before it can join.") },
        confirmButton = { TextButton(onClick = { offerPerfect = false; vm.resetSend(); making = true }) {
            Text("MAKE THE PERFECT COIN", color = accent) } },
        dismissButton = { TextButton(onClick = { offerPerfect = false; onPicked(chosen.toList(), dest, cap) }) {
            Text("NOT NEEDED, CONTINUE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )

    Panel(accent = accent) {
        SectionLabel(title, accent)
        Spacer(Modifier.height(8.dp))
        if (dests.size > 1) DestinationChooser(dests, dest, !making, type, outputs, accent) { dest = it }
        if (making) {
            ExactCoinPanel(vm, accent, exact, tag, exactFor, dest, outputs) { sent -> making = false; reload++; if (sent) onCancel() }
            return@Panel
        }
        if (paymentPool) Explain((if (maxInputs > 1)
            "Pick up to $maxInputs confirmed $kind coins that together make at least ${groupSats(mixTotal)} sats plus your fee. " +
                "The leftover comes back to a fresh change address."
            else "Pick ONE confirmed $kind coin of at least ${groupSats(min)} sats. Its leftover comes back to a fresh change address.") +
            "\n\n" + "Your outputs are the payments, one to each recipient.")
        else if (payment) Explain((if (maxInputs > 1)
            "Pick up to $maxInputs confirmed $kind coins that together make at least ${groupSats(mixTotal)} sats plus your fee. " +
                "The leftover comes back to a fresh change address."
            else "Pick ONE confirmed $kind coin of at least ${groupSats(min)} sats. Its leftover comes back to a fresh change address.") +
            "\n\n" + "The pool's outputs go to the payment's recipients, one each.")
        else if (dest != Destination.Kind.HOT) Explain((if (maxInputs > 1)
            "Pick up to $maxInputs confirmed $kind coins that together make at least ${groupSats(mixTotal)} sats plus your fee. " +
                "The leftover comes back to a fresh change address."
            else "Pick ONE confirmed $kind coin of at least ${groupSats(min)} sats. Its leftover comes back to a fresh change address.") +
            "\n\n" + (if (outputs > 1) "Each of your $outputs mixed outputs of ${groupSats(amount)} sats goes to its own unused address of the wallet chosen above."
                else "The mixed output goes to an unused address of the wallet chosen above."))
        else Explain(if (maxInputs > 1)
            "Pick up to $maxInputs confirmed $kind coins that together make at least ${groupSats(mixTotal)} sats plus your fee. " +
                "The leftover comes back to a fresh change address; " +
                (if (outputs > 1) "each of your $outputs mixed outputs of ${groupSats(amount)} sats goes to its own fresh address of this wallet."
                else "the mixed output goes to another fresh address of this wallet.")
        else "Pick ONE confirmed $kind coin of at least ${groupSats(min)} sats. Its leftover comes back to a fresh " +
            "change address; " + (if (outputs > 1) "each of your $outputs mixed outputs of ${groupSats(amount)} sats goes to its own fresh address of this wallet."
                else "the mixed output goes to another fresh address of this wallet."))
        if (leftOut > 0) Text("$leftOut of the coins ticked in SEND can't go in (unconfirmed, of another address type, or already in a pool): tick others here.",
            color = Warn, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        val list = coins
        val selectable = list?.filter { maxInputs > 1 || it.value >= min }
        when {
            list == null -> Text("reading your coins…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text("No confirmed $kind coin.", color = Warn, style = MaterialTheme.typography.bodySmall)
            selectable.isNullOrEmpty() -> Text("No confirmed coin is big enough.", color = Warn, style = MaterialTheme.typography.bodySmall)
            // The exact coin first: it joins with no change, so the mixed output is tied to nothing.
            else -> selectable.sortedWith(compareBy({ if (it.value == exact) 0 else 1 }, { -it.value })).forEach { u ->
                val sel = chosen.any { it.txid == u.txid && it.vout == u.vout }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (sel) accent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable {
                            if (sel) chosen.removeAll { it.txid == u.txid && it.vout == u.vout }
                            else if (maxInputs == 1) { chosen.clear(); chosen.add(u) }
                            else if (chosen.size < maxInputs) chosen.add(u)
                        }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text("${groupSats(u.value)} sats" + if (u.value == exact) "  ★ EXACT" else "",
                                color = if (u.value == exact) Good else TextMain, style = MaterialTheme.typography.bodyMedium)
                            if ("${u.txid}:${u.vout}" in mixedCoins) Text(" · mixed", color = Warn, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(vm.coinAddress(u)?.let { shortAddress(it) } ?: "${u.txid.take(10)}…:${u.vout}",
                            color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sel) Text("✓", color = accent)
                }
            }
        }
        // One coin each: coins that cover the amount but not this pool's fee are shown, not hidden.
        if (maxInputs == 1) list?.filter { it.value in mixTotal until min }?.sortedByDescending { it.value }?.forEach { u ->
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text("${groupSats(u.value)} sats", color = TextFaint, style = MaterialTheme.typography.bodyMedium)
                Text("${groupSats(min - u.value)} sats short of this pool's fee (${fmtRate(feeRate)} sat/vB): " +
                    "it fits a pool at a lower rate", color = Warn, style = MaterialTheme.typography.bodySmall)
            }
        }
        // A seat's coins are tied together by the round itself: a mixed one with others undoes its mix.
        val mixedChosen = chosen.count { "${it.txid}:${it.vout}" in mixedCoins }
        if (mixedChosen > 0 && chosen.size > 1) Text(
            "A mixed coin in one seat with other coins ties them together: the round shows they are one person's. Remix mixed coins one at a time.",
            color = Warn, style = MaterialTheme.typography.bodySmall)
        if (chosen.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(when {
                change == null -> "${chosen.size} coin(s), ${groupSats(total)} sats: " +
                    "${groupSats(mixTotal + CoinjoinTx.feeShare(feeRate, false, chosen.size, type, feeOutputs) - total)} sats short"
                change == 0L && total == exact -> "★ exact: no change · fee ${groupSats(fee)} sats"
                change == 0L -> "${chosen.size} coin(s) · no change, ${groupSats(fee)} sats to the miners"
                // Opening a payment pool: what goes in is paid to the recipients, not mixed.
                paymentPool -> "${chosen.size} coin(s) · ${groupSats(total)} sats → ${groupSats(mixTotal)} paid + ${groupSats(change)} change · fee ${groupSats(fee)}"
                else -> "${chosen.size} coin(s) · ${groupSats(total)} sats → $mixedLine mixed + ${groupSats(change)} change · fee ${groupSats(fee)}"
            }, color = if (change == null) Warn else accent, style = MaterialTheme.typography.bodySmall)
        }
        // A payment has no exact coin to make (below), so no word about one.
        if (list != null && !payment && !noExactCoin) Text(if (type == ScriptType.P2TR)
            "Only Taproot (bc1p) coins can join this pool. Your native SegWit coins can still pay for a Taproot exact coin below."
        else "Only native SegWit (bc1q) coins can join this pool. Your Taproot coins can still pay for a native SegWit exact coin below.",
            color = TextFaint, style = MaterialTheme.typography.bodySmall)
        if (perpetualOk && dest == Destination.Kind.HOT) PerpetualChoice(perpetual, capText, accent, { perpetual = it }, { capText = it })
        if (capBad) Text("Fee cap: 1 to 500 sat/vB.", color = Warn, style = MaterialTheme.typography.bodySmall)
        if (!payment && !noExactCoin && list != null && list.none { it.value == exact }) TextButton(onClick = { vm.resetSend(); making = true }) {
            Text("＋ PREPARE AN EXACT COIN OF ${groupSats(exact)} SATS", color = accent, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(if (payment) "BACK" else "CANCEL") }
            Button(onClick = { if ((change ?: 0) > 0 && !payment && !noExactCoin) offerPerfect = true else onPicked(chosen.toList(), dest, cap) },
                enabled = change != null && !capBad,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(if (chosen.size > 1) "USE THESE ${chosen.size} COINS" else "USE THIS COIN")
            }
        }
    }
}

@Composable
private fun CreatePool(
    vm: WalletViewModel, accent: Color, allowTest: Boolean,
    /** A payment from SEND: the amount, the outputs and the address type are the payment's, not to be changed here. */
    pay: com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment.Request?,
    onCancel: () -> Unit,
    onCreate: (amount: Long, rate: Double, minPeers: Int, maxPeers: Int, hours: Int, password: String, maxInputs: Int,
               outputs: Int, coins: List<Scanner.SpendableUtxo>, dest: Destination.Kind, perpetualCap: Double?) -> Unit,
) {
    val ctx = LocalContext.current
    // From your coins (the default): whole coins ticked first, and the amount worked out so they
    // go in with no change. The other way, the amount typed and the coins picked for it.
    var fromCoins by remember { mutableStateOf(pay == null) }
    var taproot by remember { mutableStateOf(pay?.type == ScriptType.P2TR) }
    // A payment pool (v5): the creator's outputs are the payments; the field below is the others' outputs.
    val payV5 = pay?.payments
    var outputsText by remember { mutableStateOf(if (payV5 != null) "1" else pay?.scripts?.size?.toString() ?: "1") }
    var inputsText by remember { mutableStateOf("5") }
    val maxInputs = inputsText.toIntOrNull()
    val type = if (taproot) ScriptType.P2TR else ScriptType.P2WPKH
    var amountText by remember { mutableStateOf(pay?.amount?.toString() ?: if (allowTest) "1000" else "100000") }
    var rateText by remember { mutableStateOf(pay?.let { fmtRate(maxOf(1.0, it.feeRate)) } ?: "1") }
    var peersText by remember { mutableStateOf("5") }
    var minText by remember { mutableStateOf("2") }
    var password by remember { mutableStateOf("") }
    var hoursText by remember { mutableStateOf("6") }
    var pickCoin by remember { mutableStateOf(false) }
    var choosingDest by remember { mutableStateOf(false) }
    // What each person puts in, in all (amount × outputs); each output at least TEST_MIN_AMOUNT.
    val minAmount = if (allowTest) Protocol.TEST_MIN_AMOUNT else Protocol.MIN_AMOUNT
    // v5: the payments' total split for the others: the outputs asked for when it splits evenly, else one.
    val split = payV5?.let { Protocol.v5Split(it.sum(), outputsText.toIntOrNull() ?: 1) }
    val outputs = split?.second ?: outputsText.toIntOrNull()
    val rate = rateText.replace(',', '.').toDoubleOrNull()
    val peers = peersText.toIntOrNull()
    val minPeers = minText.toIntOrNull()
    val hours = hoursText.toIntOrNull()
    // The coins of the pool's type, for opening it from them.
    val tag = if (taproot) ScriptType.P2TR else null
    var coins by remember(taproot) { mutableStateOf<List<Scanner.SpendableUtxo>?>(null) }
    // The mixed ones, worked out once per list: not again for every coin on every redraw of the tab.
    val mixedCoins = remember(coins) { vm.mixedOutpoints(coins.orEmpty()) }
    val picked = remember(taproot) { mutableStateListOf<Scanner.SpendableUtxo>() }
    LaunchedEffect(taproot, fromCoins) { if (fromCoins) coins = runCatching { vm.coinjoinCoins(tag) }.getOrDefault(emptyList()) }
    val coinsTotal = picked.sumOf { it.value }
    val rateOk = rate != null && rate >= 1.0 && rate <= 500.0
    val outputsOk = outputs != null && outputs in 1..Protocol.MAX_OUTPUTS
    val amount = if (split != null) split.first
        else if (!fromCoins) amountText.filter { it.isDigit() }.toLongOrNull()
        else if (picked.isEmpty() || !rateOk || !outputsOk) null
        else CoinjoinTx.noChangeAmount(coinsTotal, picked.size, rate!!, type, outputs!!)
    val error = when {
        fromCoins && !rateOk -> "Fee: 1 to 500 sat/vB."
        fromCoins && !outputsOk -> "Mixed outputs per person: 1 to ${Protocol.MAX_OUTPUTS}."
        fromCoins && picked.isEmpty() -> "Tick the coins to put in."
        fromCoins && (maxInputs == null || maxInputs !in 1..Protocol.MAX_INPUTS) -> "Coins per person: 1 to ${Protocol.MAX_INPUTS}."
        fromCoins && picked.size > maxInputs!! -> "You ticked ${picked.size} coins: set the most coins per person to at least ${picked.size}."
        fromCoins && (amount == null || amount < Protocol.TEST_MIN_AMOUNT || amount * outputs!! < minAmount) ->
            "These coins make ${groupSats(maxOf(0L, amount ?: 0L) * outputs!!)} sats in all: a pool takes at least ${groupSats(minAmount)} each, and ${groupSats(Protocol.TEST_MIN_AMOUNT)} per output."
        fromCoins && amount!! > Protocol.MAX_AMOUNT ->
            "These coins make ${groupSats(amount)} sats per output: a pool takes at most ${groupSats(Protocol.MAX_AMOUNT)} (1 BTC). Tick fewer coins or give more outputs."
        amount == null || amount < Protocol.TEST_MIN_AMOUNT || amount > Protocol.MAX_AMOUNT ->
            "Amount per output: ${groupSats(Protocol.TEST_MIN_AMOUNT)} to ${groupSats(Protocol.MAX_AMOUNT)} sats (1 BTC)."
        outputs != null && amount * outputs < minAmount ->
            "${groupSats(amount)} × $outputs = ${groupSats(amount * outputs)} sats each: a pool takes at least ${groupSats(minAmount)} each in all."
        rate == null || rate < 1.0 || rate > 500.0 -> "Fee: 1 to 500 sat/vB."
        peers == null || peers !in Protocol.MIN_PEERS..Protocol.MAX_PEERS -> "Most people: ${Protocol.MIN_PEERS} to ${Protocol.MAX_PEERS}."
        minPeers == null || minPeers < Protocol.MIN_PEERS || minPeers > peers -> "Fewest people: ${Protocol.MIN_PEERS} to $peers (the most)."
        hours == null || hours !in 1..72 -> "Open for 1 to 72 hours."
        maxInputs == null || maxInputs !in 1..Protocol.MAX_INPUTS -> "Coins per person: 1 to ${Protocol.MAX_INPUTS}."
        outputs == null || outputs !in 1..Protocol.MAX_OUTPUTS -> "Mixed outputs per person: 1 to ${Protocol.MAX_OUTPUTS}."
        else -> null
    }
    if (pickCoin && error == null) {
        val exactFor = WalletViewModel.ExactFor(null, null, org.json.JSONObject().put("amount", amount!!).put("feeRate", rate!!)
            .put("maxPeers", peers!!).put("hours", hours!!).put("minPeers", minPeers!!).put("password", password).put("maxInputs", maxInputs!!)
            .put("outputs", outputs!!))
        CoinPicker(vm, accent, if (pay != null) "Your coins for the payment" else "Your coins for the pool", amount, rate, type, maxInputs, outputs, exactFor,
            onCancel = { pickCoin = false }, initial = pay?.coins.orEmpty(), payment = pay != null,
            feeOutputs = payV5?.size ?: outputs, paymentPool = payV5 != null, perpetualOk = pay == null && outputs == 1) { u, dest, cap ->
            onCreate(amount, rate, minPeers!!, peers!!, hours!!, password, maxInputs, outputs, u, dest, cap)
        }
        return
    }
    // From your coins, then where the outputs go (when there is more than this wallet).
    val dests = remember(type) { Destination.options(ctx, type) }
    var dest by remember(type) { mutableStateOf(Destination.Kind.HOT) }
    var perpetual by remember { mutableStateOf(false) }
    var capText by remember { mutableStateOf("") }
    val perpetualOk = pay == null && fromCoins && outputs == 1
    val cap = if (perpetualOk && perpetual) (capText.ifEmpty { rateText }).replace(',', '.').toDoubleOrNull() else null
    val capBad = perpetualOk && perpetual && (cap == null || cap < 1.0 || cap > 500.0)
    fun open(d: Destination.Kind) = onCreate(amount!!, rate!!, minPeers!!, peers!!, hours!!, password, maxInputs!!, outputs!!, picked.toList(), d,
        if (d == Destination.Kind.HOT) cap else null)
    if (choosingDest && fromCoins && error == null) {
        Panel(accent = accent) {
            SectionLabel("Open a pool from your coins", accent)
            Spacer(Modifier.height(8.dp))
            Text(if (outputs!! > 1) "Pool of $outputs × ${groupSats(amount!!)} sats · change 0" else "Pool of ${groupSats(amount!!)} sats per output · change 0",
                color = Good, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            DestinationChooser(dests, dest, true, type, outputs, accent) { dest = it }
            if (cap != null && dest != Destination.Kind.HOT) Text("A perpetual coinjoin only remixes outputs of this wallet: with another destination it is off.",
                color = Warn, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { choosingDest = false }, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("BACK") }
                Button(onClick = { open(dest) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("OPEN THE POOL") }
            }
        }
        return
    }
    Panel(accent = accent) {
        SectionLabel(if (pay != null) "Pay through a coinjoin" else "Open a pool", accent)
        Spacer(Modifier.height(8.dp))
        @Composable fun field(label: String, v: String, set: (String) -> Unit) = OutlinedTextField(
            value = v, onValueChange = set, label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        if (pay != null) {
            // The payment as SEND had it: what is paid, to whom, and when.
            if (payV5 != null) {
                // A payment pool (v5): each recipient's own amount; the others get the total in equal outputs.
                Text("${groupSats(payV5.sum())} sats to ${payV5.size} recipient(s)", style = MaterialTheme.typography.titleMedium, color = TextMain)
                pay.addresses.forEachIndexed { i, a ->
                    Text(shortAddress(a) + " · " + groupSats(payV5[i]) + " sats", color = accent, style = MaterialTheme.typography.bodySmall)
                }
                Text((if (pay.type == ScriptType.P2TR) "Taproot (bc1p)" else "Native SegWit (bc1q)") + " pool · a payment pool (v5)",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Explain("A new payment pool opens: each of the others puts in ${groupSats(payV5.sum())} sats too and gets it back in " +
                    "equal outputs, while your outputs are the payments. On the chain the payments show as odd amounts, and as " +
                    "one person's; which coins paid them stays hidden among the members, best when everyone uses perfect coins. " +
                    "The payment only goes out when the pool closes, which can take hours. If it doesn't fill, nothing is paid " +
                    "and your coins stay here: you can cancel it and send normally.")
                field("outputs for each of the others (1–${Protocol.MAX_OUTPUTS})", outputsText) { outputsText = it }
                split?.let { (d, k) ->
                    Text(if (k > 1) "The others get ${groupSats(payV5.sum())} sats back as $k × ${groupSats(d)}."
                        else "The others get ${groupSats(payV5.sum())} sats back as one output.",
                        color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    if ((outputsText.toIntOrNull() ?: 1) > 1 && k == 1)
                        Text("It doesn't split evenly into ${outputsText.toIntOrNull()} outputs of at least ${groupSats(minAmount)} sats: one output each.",
                            color = Warn, style = MaterialTheme.typography.bodySmall)
                }
            } else {
            Text(if (pay.scripts.size > 1) "${pay.scripts.size} recipients × ${groupSats(pay.amount)} sats"
                else "${groupSats(pay.amount)} sats to 1 recipient",
                style = MaterialTheme.typography.titleMedium, color = TextMain)
            pay.addresses.forEach { a -> Text(shortAddress(a), color = accent, style = MaterialTheme.typography.bodySmall) }
            Text((if (pay.type == ScriptType.P2TR) "Taproot (bc1p)" else "Native SegWit (bc1q)") + " pool · one output per recipient",
                color = TextFaint, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Explain("A new pool of exactly this amount opens, and its outputs for you go straight to the recipients: on the chain " +
                "the payment looks like any other output of the round. The payment only goes out when the pool closes, " +
                "which can take hours. If it doesn't fill, nothing is paid and your coins stay here: you can cancel it and send normally.")
            }
            Spacer(Modifier.height(6.dp))
        } else {
            // How the pool's amount is set: from whole coins (no change), or typed.
            Text("Pool amount", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            listOf(true to "From your coins: tick them, no change (the best for privacy)", false to "Type the amount").forEach { (c, label) ->
                val sel = fromCoins == c
                // The mark apart from the words, so the words are found in the translations.
                TextButton(onClick = { fromCoins = c }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sel) "●" else "○", color = if (sel) accent else TextMain, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = if (sel) accent else TextMain,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            // The pool's one address type: its coins, mixed outputs and change are all of it.
            Text("Address type", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            listOf(false to "Native SegWit (bc1q)", true to "Taproot (bc1p)").forEach { (t, label) ->
                val sel = taproot == t
                val can = !t || vm.taprootPools()
                TextButton(onClick = { if (can) taproot = t }, enabled = can, modifier = Modifier.fillMaxWidth()) {
                    // The mark apart from the words, so the words are found in the translations.
                    val c = if (sel) accent else if (can) TextMain else TextFaint
                    Text(if (sel) "●" else "○", color = c, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text(label + if (!can) " — turn on Taproot in settings" else "",
                        color = c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            Text(if (taproot) "Only Taproot coins can join, and everyone gets Taproot outputs back."
                else "Only native SegWit coins can join, and everyone gets native SegWit outputs back.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall)
            field("mixed outputs per person (1–${Protocol.MAX_OUTPUTS})", outputsText) { outputsText = it }
            Text(if ((outputs ?: 1) > 1) (if (fromCoins) "Each person gets $outputs mixed outputs of the pool's amount, each to an unused address of their own. "
                    else "Each person gets $outputs mixed outputs of the amount below, each to an unused address of their own. ") +
                    "Only Kilowallet 1.2.0 or newer (Kilojoin v4) sees this pool."
                else "One mixed output each: every Kilojoin v3 wallet can join it.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall)
            if (!fromCoins) {
                field(if ((outputs ?: 1) > 1) "amount of each output (sats)" else "amount per person (sats)", amountText) { amountText = it }
                amount?.let { Text("= ${"%.8f".format(it / 1e8)} BTC" + if ((outputs ?: 1) > 1) " · ${groupSats(it * outputs!!)} sats per person in all" else "",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall) }
            }
        }
        field("fee rate (sat/vB)", rateText) { rateText = it }
        if (fromCoins) {
            // Whole coins, ticked: the pool's amount comes out of them.
            Spacer(Modifier.height(8.dp))
            SectionLabel("Your coins", accent)
            Explain("Tick whole coins: the pool's amount is worked out from them so they go in with no change at all. " +
                "The others join with coins of their own.")
            val kind = if (taproot) "Taproot (bc1p)" else "native SegWit (bc1q)"
            val list = coins
            when {
                list == null -> Text("reading your coins…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text("No confirmed $kind coin.", color = Warn, style = MaterialTheme.typography.bodySmall)
                else -> list.forEach { u ->
                    val sel = picked.any { it.txid == u.txid && it.vout == u.vout }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (sel) accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                if (sel) picked.removeAll { it.txid == u.txid && it.vout == u.vout }
                                else if (picked.size < Protocol.MAX_INPUTS) picked.add(u)
                            }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text("${groupSats(u.value)} sats", color = TextMain, style = MaterialTheme.typography.bodyMedium)
                                if ("${u.txid}:${u.vout}" in mixedCoins) Text(" · mixed", color = Warn, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(vm.coinAddress(u)?.let { shortAddress(it) } ?: "${u.txid.take(10)}…:${u.vout}",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall)
                        }
                        if (sel) Text("✓", color = accent)
                    }
                }
            }
            // A seat's coins are tied together by the round itself: a mixed one with others undoes its mix.
            if (picked.count { "${it.txid}:${it.vout}" in mixedCoins } > 0 && picked.size > 1) Text(
                "A mixed coin in one seat with other coins ties them together: the round shows they are one person's. Remix mixed coins one at a time.",
                color = Warn, style = MaterialTheme.typography.bodySmall)
            if (picked.isNotEmpty() && amount != null && amount > 0 && rateOk && outputsOk) {
                val fee = CoinjoinTx.feeShare(rate!!, false, picked.size, type, outputs!!)
                val rounding = coinsTotal - fee - amount * outputs
                Spacer(Modifier.height(4.dp))
                Text(if (outputs > 1) "Pool of $outputs × ${groupSats(amount)} sats · change 0" else "Pool of ${groupSats(amount)} sats per output · change 0",
                    color = if (error == null) Good else Warn, style = MaterialTheme.typography.bodyMedium)
                Text("${picked.size} coin(s), ${groupSats(coinsTotal)} sats · fee ${groupSats(fee)} sats" +
                    if (rounding > 0) " · $rounding sats of rounding to the miners" else "",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }
        field("fewest people to close (${Protocol.MIN_PEERS}–${Protocol.MAX_PEERS - 1})", minText) { minText = it }
        field("most people (${Protocol.MIN_PEERS}–${Protocol.MAX_PEERS})", peersText) { peersText = it }
        field("open for (hours)", hoursText) { hoursText = it }
        field("most coins per person (1–${Protocol.MAX_INPUTS})", inputsText) { inputsText = it }
        if (payV5 != null) Text("Only Kilowallet 1.2.7 or newer (Kilojoin v5) sees this pool.",
            color = TextFaint, style = MaterialTheme.typography.bodySmall)
        else if ((outputs ?: 1) == 1) Text(if (!taproot && maxInputs == 1) "One coin each: Kilowallet versions before 0.27 can join it too."
            else "Only Kilowallet 0.27 or newer sees this pool.",
            color = TextFaint, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("password (optional: makes it private 🔒)", style = MaterialTheme.typography.bodySmall) },
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        if (password.isNotEmpty()) Text("Only people you give this password to can join. It is not announced anywhere.",
            color = TextFaint, style = MaterialTheme.typography.bodySmall)
        if (!fromCoins && payV5 == null && amount != null && rate != null && rate >= 1.0 && outputs != null && outputs in 1..Protocol.MAX_OUTPUTS) {
            val withChange = CoinjoinTx.feeShare(rate, true, 1, type, outputs); val exact = CoinjoinTx.feeShare(rate, false, 1, type, outputs)
            val mixTotal = amount * outputs
            Text("Each person pays their own fee, the same however many join: with one coin $withChange sats with change, " +
                "$exact without (each extra coin adds ${"%.0f".format(java.util.Locale.ROOT, kotlin.math.ceil(rate * CoinjoinTx.inputVb(type)))}). " +
                "A coin of exactly ${groupSats(mixTotal + exact)} sats leaves no change " +
                "(the best for privacy); a bit more than that, up to ${groupSats(mixTotal + withChange + CoinjoinTx.DUST)}, " +
                "also goes in without change and the rest goes to the miners.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
        if (perpetualOk) PerpetualChoice(perpetual, capText.ifEmpty { rateText }, accent, { perpetual = it }, { capText = it })
        if (capBad) Text("Fee cap: 1 to 500 sat/vB.", color = Warn, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(if (pay != null) "BACK" else "CANCEL") }
            if (fromCoins) Button(onClick = { if (dests.size > 1) choosingDest = true else open(Destination.Kind.HOT) }, enabled = error == null && !capBad,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(if (dests.size > 1) "CONTINUE" else "OPEN THE POOL") }
            else Button(onClick = { pickCoin = true }, enabled = error == null,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                // Coins ticked in SEND already: the picker opens with them ticked, only to confirm.
                Text(if (pay?.coins.isNullOrEmpty()) "CHOOSE COINS" else "CONTINUE WITH YOUR COINS")
            }
        }
    }
}

@Composable
private fun MyPoolCard(
    vm: WalletViewModel, st: PoolSession.State,
    // The state is mutated in place: this changes with it, so the card is redrawn and not skipped.
    // It must be READ below: Compose leaves unused parameters out of its "did anything change" check.
    rev: Long, accent: Color, activity: FragmentActivity,
    /** Inside a [RoundCard]: no panel of its own, and the amount and status are already above. */
    embedded: Boolean = false, onMessage: (String) -> Unit) {
    if (rev < 0) return
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val session = CoinjoinHub.session(st.key)
    val color = when (st.phase) {
        PoolSession.Phase.CONFIRMED -> Good
        PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> Bad
        PoolSession.Phase.SIGNING, PoolSession.Phase.VOTING -> Warn
        else -> accent
    }
    fun act(f: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(f) }.onFailure { onMessage(it.message ?: "failed") } }
    @Composable fun frame(content: @Composable () -> Unit) { if (embedded) Column { content() } else Panel(accent = color) { content() } }
    frame {
        val people = if (st.round.isNotEmpty()) st.round.size else st.seats.size
        if (!embedded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${poolAmount(st.terms)} sats" + if (st.terms.script == ScriptType.P2TR) " · Taproot" else "",
                style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
            Text((if (st.terms.private) "🔒 " else "") + (if (st.creator) "your pool" else "joined"), style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
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
        }
        // Whole sentences (one per case), so each is found in the translations as it is.
        val coins = if (st.seat.coins.size > 1) "your ${st.seat.coins.size} coins" else "your coin"
        // The creator of a payment pool (v5): its outputs are the payments, not mixed outputs.
        val out = if (st.terms.v5 && st.creator) "${groupSats(st.terms.mixTotal)} paid to ${st.terms.payments.size} recipient(s)"
            else "${poolAmount(st.terms)} mixed"
        val fee = groupSats(st.seat.inputValue - st.terms.mixTotal - st.seat.changeValue)
        Text("$coins ${groupSats(st.seat.inputValue)} → $out" +
            (if (st.seat.changeValue > 0) " + ${groupSats(st.seat.changeValue)} change · fee $fee" else " · fee $fee"),
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
        // Only on this phone: where the mixed outputs go when it is not this wallet.
        val goesTo = remember(st.key) { Destination.of(ctx, st.key) }
        val which = remember(st.key) { Destination.whichOf(ctx, st.key) }
        if (goesTo != Destination.Kind.HOT) Text((if (goesTo == Destination.Kind.PAY) "the payments go to: " else "mixed outputs go to: ") +
            Destination.label(goesTo) + (which?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = accent)
        if (goesTo == Destination.Kind.PAY && st.phase in setOf(PoolSession.Phase.JOINING, PoolSession.Phase.OPEN, PoolSession.Phase.VOTING))
            Text("A payment: it goes out only when the pool closes. If the pool doesn't fill, nothing is paid; cancel it and send normally.",
                style = MaterialTheme.typography.bodySmall, color = TextFaint)
        if (goesTo != Destination.Kind.HOT && st.mixScripts.isNotEmpty()) {
            var show by remember(st.key) { mutableStateOf(false) }
            TextButton(onClick = { show = !show }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (show) "hide the addresses" else "show the addresses", color = accent, style = MaterialTheme.typography.labelSmall)
            }
            if (show) AddressesShown(remember(st.key) { Destination.describe(ctx, goesTo, st.terms.script, st.mixScripts) })
        }
        if (CoinjoinHub.isAuto(st.key) && st.phase in setOf(PoolSession.Phase.OPEN, PoolSession.Phase.VOTING,
                PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING)) {
            // The switch above says it all; here only a quiet mark.
            Text("on its own ✓", style = MaterialTheme.typography.labelSmall, color = Good)
        } else if (st.phase in setOf(PoolSession.Phase.OPEN, PoolSession.Phase.VOTING, PoolSession.Phase.CLOSING,
                PoolSession.Phase.SIGNING) && !st.postedSig) {
            // A round joined while asking (or before this existed): one fingerprint now, nothing later.
            TextButton(onClick = {
                runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                    Biometric.authenticate(activity, "Run the round on its own", "Unlock once: the wallet accepts closing and signs after its checks", cipher,
                        onSuccess = { authed -> act { CoinjoinHub.startAuto(st.key, vm.coinjoinKeys(authed, st.coinPaths)) } },
                        onError = onMessage)
                }.onFailure { onMessage(it.message ?: "could not unlock") }
            }) { Text("let it run on its own", color = accent, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(8.dp))

        @Composable fun btn(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) = Button(
            onClick = onClick, modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = if (primary) color else PanelSoft, contentColor = if (primary) Ink else color),
            shape = RoundedCornerShape(12.dp)) { Text(label) }

        when (st.phase) {
            PoolSession.Phase.JOINING -> {
                val wait = st.joinAfter - System.currentTimeMillis() / 1000
                if (wait > 0) Text(if (st.creator) "Your pool opens in $wait s: a random wait, so it can't be tied to your last round by its timing."
                    else "Joining in $wait s: a random wait, so your seats don't show up together.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall)
                else Text("Waiting for the pool's creator to let you in. If nobody answers in " +
                    "${PoolSession.JOIN_TIMEOUT / 60} minutes the request is dropped.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                btn("CANCEL", modifier = Modifier.fillMaxWidth()) { act { session?.leave() } }
            }
            PoolSession.Phase.OPEN, PoolSession.Phase.VOTING -> {
                val manySeats = CoinjoinHub.seatsIn(st.poolId).size > 1
                var confirmLeave by remember { mutableStateOf(false) }
                if (confirmLeave) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    title = { Text(if (st.creator) "Cancel this pool?" else if (manySeats) "Leave with this seat?" else "Leave this pool?") },
                    text = { Text(if (st.creator && manySeats)
                        "The round is cancelled for everyone in it, your other seats too, and they are told so. Nobody loses anything: no " +
                            "transaction exists until all have signed, so every coin stays where it is."
                        else if (st.creator)
                        "The round is cancelled for everyone in it, and they are told so. Nobody loses anything: no " +
                            "transaction exists until all have signed, so every coin stays where it is."
                        else if (manySeats) "Only this seat leaves (its coin of ${groupSats(st.seat.inputValue)} sats is yours to spend again); your other seat stays in the pool."
                        else "Your seat is freed and your coin is yours to spend again. Nothing was signed, so nothing moves.") },
                    confirmButton = { TextButton(onClick = { confirmLeave = false; act { session?.leave() } }) {
                        Text(if (st.creator) "CANCEL POOL" else "LEAVE", color = Bad) } },
                    dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("STAY", color = TextSoft) } },
                    containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                )
                var confirmClose by remember { mutableStateOf(false) }
                if (confirmClose) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmClose = false },
                    title = { Text("Close the pool now with $people people?") },
                    text = { Text("Everyone in the pool is asked to accept within ${Protocol.VOTE_SECONDS / 60} minutes; whoever " +
                        "does not answer is left out of this round. If enough say yes, the round starts: each wallet posts " +
                        "its mixed output and then you sign, so keep the app open until it is sent. Nobody else can join " +
                        "once it closes.") },
                    confirmButton = { TextButton(onClick = { confirmClose = false; act { session?.requestClose() } }) {
                        Text("ASK TO CLOSE", color = accent) } },
                    dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("NOT NOW", color = TextSoft) } },
                    containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                )
                if (manySeats) Text(ANOTHER_SEAT_NOTE, color = Warn, style = MaterialTheme.typography.bodySmall)
                if (st.phase == PoolSession.Phase.OPEN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (people >= st.terms.minPeers) btn("ASK TO CLOSE NOW", true, Modifier.weight(1f)) { confirmClose = true }
                        btn(if (st.creator) "CANCEL POOL" else if (manySeats) "LEAVE THIS SEAT" else "LEAVE", modifier = Modifier.weight(1f)) { confirmLeave = true }
                    }
                    if (people < st.terms.minPeers) Text("Closes once ${st.terms.minPeers} people are in.",
                        color = TextFaint, style = MaterialTheme.typography.bodySmall)
                } else {
                    val iAsked = st.voteBy == st.token?.let { Protocol.tokenHash(it) }
                    val by = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(st.voteDeadline * 1000))
                    if (iAsked || st.votedOn == st.voteId) {
                        Text("Waiting for the others to answer, until $by. Whoever has not answered by then is left " +
                            "out; if too few are left, the pool reopens without them.",
                            color = TextSoft, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        btn(if (st.creator) "CANCEL POOL" else if (manySeats) "LEAVE THIS SEAT" else "LEAVE", modifier = Modifier.fillMaxWidth()) { confirmLeave = true }
                    } else {
                        Text("Answer before $by.", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            btn("ACCEPT", true, Modifier.weight(1f)) { act { session?.vote(true) } }
                            btn("NOT YET", modifier = Modifier.weight(1f)) { act { session?.vote(false) } }
                        }
                    }
                }
            }
            PoolSession.Phase.SIGNING -> {
                val plan = session?.plan()
                if (st.postedSig) Text("Signed. Waiting for the others (${st.sigs.size}/${plan?.coins?.size ?: "?"} coins)…",
                    color = TextSoft, style = MaterialTheme.typography.bodySmall)
                else {
                    plan?.let {
                        // Whole sentences (one per case), so each is found in the translations as it is.
                        val ppl = st.round.size; val n = it.coins.size; val same = it.outputs.count { o -> o.value == st.terms.amount }
                        val each = groupSats(st.terms.amount); val total = groupSats(it.fee); val mine = st.terms.outputs
                        Explain(when {
                            mine > 1 && st.seat.changeValue > 0 -> "Checked: $ppl people ($n coins), $same identical outputs of $each sats, $mine of them yours; your change is right; total fee $total sats."
                            mine > 1 -> "Checked: $ppl people ($n coins), $same identical outputs of $each sats, $mine of them yours; no change (an exact coin); total fee $total sats."
                            st.seat.changeValue > 0 -> "Checked: $ppl people ($n coins), $same identical outputs of $each sats, one of which is yours; your change is right; total fee $total sats."
                            else -> "Checked: $ppl people ($n coins), $same identical outputs of $each sats, one of which is yours; no change (an exact coin); total fee $total sats."
                        })
                        Spacer(Modifier.height(6.dp))
                    }
                    btn("SIGN", true, Modifier.fillMaxWidth()) {
                        runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                            Biometric.authenticate(activity, "Sign the coinjoin", "Unlock to sign your coins' inputs", cipher,
                                onSuccess = { authed -> act { session?.sign(vm.coinjoinKeys(authed, st.coinPaths)) } },
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
                        TextButton(onClick = { CoinjoinHub.remove(ctx, st.key) }) { Text("remove", color = TextFaint) }
                }
            }
            PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED ->
                TextButton(onClick = { CoinjoinHub.remove(ctx, st.key) }) { Text("remove", color = TextFaint) }
            else -> {}
        }
    }
}

/**
 * "On its own": one fingerprint when joining and the wallet does the rest of the round (accept
 * closing, ask to close before it expires, sign once its checks pass). See [CoinjoinHub.isAuto].
 */
@Composable
private fun AutoPanel(accent: Color) {
    Panel(accent = accent) { CoinjoinAutoSetting(accent) }
}

/** The same choice, in settings and on the join and create sheets. */
@Composable
internal fun CoinjoinAutoSetting(accent: Color) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(CoinjoinHub.autoDefault(ctx)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Run the round on its own", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain(if (on) "One fingerprint when you join; then it accepts closing and signs by itself, only " +
                    "if the transaction pays your exact output and change. It carries on after the app is closed and opened again: " +
                    "the keys of the round's coins are kept encrypted on this phone until the round ends." +
                    "\n\nThey work without your fingerprint while the round runs: that is what lets it sign alone."
                    else "Off: you accept closing and sign yourself, with your fingerprint, at each step.")
            }
            // Turning it on also asks Android to let the wallet run in the background, which a
            // round on its own needs with the phone put away.
            androidx.compose.material3.Switch(checked = on, onCheckedChange = {
                on = it; CoinjoinHub.setAutoDefault(ctx, it)
                if (it && !batteryFree(ctx)) askBatteryFree(ctx)
            },
                colors = switchColors(accent))
        }
        // A round on its own has to keep going with the screen off and the app closed; Android's
        // battery saver may pause it otherwise. Offered here, never asked for elsewhere.
        var free by remember { mutableStateOf(batteryFree(ctx)) }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) free = batteryFree(ctx) }
            lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
        }
        if (on && !free) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Explain("Android may pause the wallet in the background. Let it run, so you can put the phone away.", Modifier.weight(1f))
                TextButton(onClick = { askBatteryFree(ctx) }) { Text("ALLOW", color = accent) }
            }
        }
    }
}

private fun batteryFree(ctx: android.content.Context): Boolean =
    (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@android.annotation.SuppressLint("BatteryLife")
private fun askBatteryFree(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Sends [exact] sats to a fresh address of this wallet, through the normal send review and
 * the fingerprint. Once it has a confirmation it shows up in the coin list, and joining with
 * it leaves no change at all, so the mixed output is tied to nothing.
 *
 * Its own fee is chosen here, from the node's estimate: the pool's fee rate only sizes the
 * coin (it is what the round will pay), it says nothing about how busy the mempool is now.
 */
@Composable
private fun ExactCoinPanel(vm: WalletViewModel, accent: Color, exact: Long, tag: ScriptType?, exactFor: WalletViewModel.ExactFor,
                           dest: Destination.Kind, outputs: Int, onDone: (sent: Boolean) -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    val destScope = rememberCoroutineScope()
    var destError by remember { mutableStateOf<String?>(null) }
    val state by vm.state.collectAsState()
    var sources by remember { mutableStateOf<List<Scanner.SpendableUtxo>?>(null) }
    val picked = remember { mutableStateListOf<Scanner.SpendableUtxo>() }
    var sourcesError by remember { mutableStateOf<String?>(null) }
    var sourcesTry by remember { mutableStateOf(0) }
    LaunchedEffect(sourcesTry) {
        sources = null; sourcesError = null
        runCatching { vm.exactCoinSources() }.onSuccess { sources = it }.onFailure { sourcesError = it.message ?: "could not read your coins" }
    }
    var suggested by remember { mutableStateOf<Double?>(null) }
    var asked by remember { mutableStateOf(false) }
    var rateText by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    // The other wallet's unused addresses being looked up (before the payment is prepared).
    var findingDest by remember { mutableStateOf(false) }
    val step by vm.sendStep.collectAsState()
    // The mixed ones, worked out once per list: not again for every coin on every redraw.
    val mixedSources = remember(sources) { vm.mixedOutpoints(sources.orEmpty()) }
    LaunchedEffect(Unit) {
        val r = runCatching { vm.suggestedBtcFeeRate() }.getOrNull()
        suggested = r; asked = true
        if (rateText.isEmpty()) rateText = r?.let { fmtRate(maxOf(1.0, it)) } ?: "1"
    }
    Panel(accent = accent) {
        SectionLabel("Exact coin · ${groupSats(exact)} sats", accent)
        Spacer(Modifier.height(6.dp))
        if (!started) {
            Explain("A payment to yourself of exactly ${groupSats(exact)} sats, to a fresh " +
                (if (tag == ScriptType.P2TR) "Taproot (bc1p)" else "native SegWit (bc1q)") + " address of this wallet. It needs one " +
                "confirmation before it can join; then it goes in with no change.")
            Spacer(Modifier.height(6.dp))
            SectionLabel("Coin control · pay it with", accent)
            Text("Tick one or more coins, native SegWit and Taproot alike; with none ticked the biggest never-mixed ones go first. " +
                "The exact coin itself is always ${if (tag == ScriptType.P2TR) "Taproot" else "native SegWit"}, the pool's type.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall)
            sourcesError?.let { e ->
                Text("Could not read your coins: $e", color = Warn, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { sourcesTry++ }) { Text("TRY AGAIN", color = accent) }
            }
            sources?.let { list ->
                if (list.isEmpty()) Text("No confirmed coin to pay it with.", color = Warn, style = MaterialTheme.typography.bodySmall)
                list.forEach { u ->
                    val sel = picked.any { it.txid == u.txid && it.vout == u.vout }
                    Row(Modifier.fillMaxWidth().clickable {
                        if (sel) picked.removeAll { it.txid == u.txid && it.vout == u.vout } else picked.add(u)
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sel) "☑" else "☐", color = if (sel) accent else TextFaint, modifier = Modifier.width(26.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text("${groupSats(u.value)} sats · ${vm.coinKind(u)}", color = if (sel) accent else TextMain,
                                    style = MaterialTheme.typography.bodySmall)
                                if ("${u.txid}:${u.vout}" in mixedSources) Text(" · mixed", color = Warn, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(vm.coinAddress(u)?.let { shortAddress(it) } ?: "", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // A mixed coin may pay for it, but not without saying what that costs.
                val mixedPicked = picked.count { "${it.txid}:${it.vout}" in mixedSources }
                if (mixedPicked > 0) Text(
                    if (picked.size > mixedPicked) "A mixed coin spent together with other coins ties them back together: the mix is undone for both."
                    else "The change of this payment comes back linked to the mixed coin. Better to use coins that were never mixed.",
                    color = Warn, style = MaterialTheme.typography.bodySmall)
                if (picked.isNotEmpty()) Text("ticked: ${picked.size} coin(s), ${groupSats(picked.sumOf { it.value })} sats" +
                    if (picked.sumOf { it.value } < exact) " — not enough for ${groupSats(exact)} plus the fee" else "",
                    color = if (picked.sumOf { it.value } < exact) Warn else accent, style = MaterialTheme.typography.bodySmall)
            } ?: if (sourcesError == null) Text("reading your coins…", color = TextSoft, style = MaterialTheme.typography.bodySmall) else Unit
            Spacer(Modifier.height(6.dp))
            Text("What this payment pays to get mined:", color = TextMain, style = MaterialTheme.typography.bodySmall)
            Text(when {
                !asked -> "asking your node how busy the mempool is…"
                suggested != null -> "your node suggests ${fmtRate(suggested!!)} sat/vB for the next few blocks"
                else -> "your node has no estimate (a quiet mempool): 1 sat/vB is usually enough"
            }, color = TextSoft, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = rateText, onValueChange = { rateText = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } },
                label = { Text("sat/vB", style = MaterialTheme.typography.bodySmall) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            val rate = rateText.toDoubleOrNull()?.takeIf { it in 0.1..1000.0 }
            destError?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDone(false) }, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("CANCEL") }
                Button(onClick = { rate?.let { r ->
                    // The progress shows at once; everything that waits on the server happens behind it.
                    destError = null; started = true
                    destScope.launch {
                        // The mixed outputs' addresses in another wallet are found now, before paying: the
                        // coin joins on its own later, when there may be no connection to ask.
                        findingDest = dest != Destination.Kind.HOT
                        val scripts = runCatching { destinationScripts(activity.applicationContext, dest,
                            if (tag == ScriptType.P2TR) ScriptType.P2TR else ScriptType.P2WPKH, outputs) }
                            .getOrElse { destError = it.message ?: "could not find unused addresses"; findingDest = false; started = false; return@launch }
                        findingDest = false
                        vm.exactFor = exactFor.withDest(scripts); vm.prepareExactCoin(exact, r, tag, picked.toList())
                    }
                } },
                    enabled = rate != null && sources != null && (picked.isEmpty() || picked.sumOf { it.value } >= exact),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(if (picked.isEmpty()) "PREPARE" else "PREPARE WITH ${picked.size}")
                }
            }
            return@Panel
        }
        when (val p = state.sendPhase) {
            is SendPhase.Review -> {
                Text("to ${shortAddress(p.draft.toAddress)} · from ${p.draft.inputs.size} coin(s) · fee ${groupSats(p.draft.fee)} sats",
                    color = TextSoft, style = MaterialTheme.typography.bodySmall)
                if (p.draft.change > 0) Text("change ${groupSats(p.draft.change)} sats back to " +
                    (p.draft.changeAddress?.let { shortAddress(it) } ?: "a fresh address of yours"),
                    color = TextSoft, style = MaterialTheme.typography.bodySmall)
                val concern = remember(p.draft) { vm.feeConcern(p.draft) }
                var feeOk by remember(p.draft) { mutableStateOf(false) }
                if (concern != null) FeeGate(concern, feeOk) { feeOk = !feeOk }
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    vm.seedCipherOrToast()?.let { cipher ->
                        Biometric.authenticate(activity, "Prepare the exact coin", "Unlock to sign the payment to yourself", cipher,
                            onSuccess = { authed -> vm.confirmSend(authed) }, onError = { vm.toast(it) })
                    }
                }, enabled = concern == null || feeOk, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text("CONFIRM & SIGN") }
                // Back to the coin control and the fee, keeping what was ticked.
                TextButton(onClick = { vm.resetSend(); started = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("edit coins or fee", color = TextSoft)
                }
            }
            is SendPhase.Sent -> {
                // What went out, and where the pool goes from here; CLOSE goes to the BTC tab.
                val uri = LocalUriHandler.current
                val copier = rememberCopier()
                Text("Sent ✓", color = Good, style = MaterialTheme.typography.bodySmall)
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Perfect coin sent") },
                    text = {
                        Column {
                            Text("${groupSats(exact)} sats to a fresh address of yours.", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                            Spacer(Modifier.height(8.dp))
                            Text(p.txid, style = MaterialTheme.typography.bodySmall, color = TextSoft,
                                modifier = Modifier.clickable { copier.copy(p.txid) })
                            Spacer(Modifier.height(8.dp))
                            Text((if (exactFor.create != null) "At its first confirmation (about 10 minutes) your pool opens by itself"
                                  else "At its first confirmation (about 10 minutes) it joins the pool by itself") +
                                " and the round runs on its own: you can close the app. Follow it in COINJOIN → IN PROGRESS.",
                                style = MaterialTheme.typography.bodySmall, color = TextSoft)
                            Row {
                                TextButton(onClick = { copier.copy(p.txid) }) {
                                    Text(if (copier.copied) "COPIED ✓" else "COPY TXID", color = if (copier.copied) Good else accent)
                                }
                                TextButton(onClick = { runCatching { uri.openUri("${vm.explorerFor(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B)}/tx/${p.txid}") } }) {
                                    Text("EXPLORER", color = accent)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.resetSend(); onDone(true)
                            com.kilombino.pyblockwatch.data.OpenTab.flow.value = com.kilombino.pyblockwatch.data.OpenTab.BTC
                        }) { Text("CLOSE", color = accent) }
                    },
                    containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                )
            }
            is SendPhase.Failed -> {
                Text(p.message, color = Bad, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.resetSend(); onDone(false) }) { Text("close", color = TextSoft) }
            }
            // Editing (the other wallet's addresses being found) or Preparing: the wait, said as it goes.
            else -> WorkingLine(accent, "Preparing the payment to yourself…",
                step = if (findingDest) "Finding unused addresses in the other wallet…" else step,
                detail = "${groupSats(exact)} sats to a fresh address of yours.")
        }
    }
}

private fun fmtRate(r: Double): String =
    if (r >= 10) "%.0f".format(java.util.Locale.ROOT, r) else "%.1f".format(java.util.Locale.ROOT, r)

/**
 * Optional Telegram alerts for the user's own pools through @Coinjoinpoolbot (see CoinjoinAlerts).
 * Off by default; turning it on first says what the bot's server learns.
 */
@Composable
private fun TelegramAlertsPanel(accent: Color, mine: List<com.kilombino.pyblockwatch.coinjoin.PoolSession.State>) {
    val ctx = LocalContext.current.applicationContext
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var on by remember { mutableStateOf(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.enabled(ctx)) }
    // null: not known yet, or the bot was out of reach when asked (not the same as an unpressed Start).
    var linked by remember { mutableStateOf<Boolean?>(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.knownLinked(ctx).takeIf { it }) }
    var asking by remember { mutableStateOf(false) }
    // How the last request to the bot went, and what still waits to be sent again.
    var status by remember { mutableStateOf(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.status(ctx)) }
    var waiting by remember { mutableStateOf(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.waiting(ctx)) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(on) {
        while (on) {
            kotlinx.coroutines.delay(5_000)
            status = com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.status(ctx); waiting = com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.waiting(ctx)
        }
    }
    LaunchedEffect(on) {
        // Until the user presses Start in the bot, check now and then (over Tor, so not too often).
        while (on && linked != true) {
            linked = withContext(Dispatchers.IO) { com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.check(ctx) }
            status = com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.status(ctx)
            if (linked == true) {
                // Pools already running get followed too.
                mine.filter { it.phase !in setOf(com.kilombino.pyblockwatch.coinjoin.PoolSession.Phase.CONFIRMED,
                    com.kilombino.pyblockwatch.coinjoin.PoolSession.Phase.ABORTED, com.kilombino.pyblockwatch.coinjoin.PoolSession.Phase.REJECTED) }
                    .forEach { com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.follow(ctx, it) }
                break
            }
            kotlinx.coroutines.delay(15_000)
        }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Telegram alerts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("@${com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.BOT} will tell you about every pool you create or join, " +
                    "step by step: someone joined or left, someone asks to close, your turn to sign, sent, confirmed, cancelled.",
                    style = MaterialTheme.typography.bodySmall)
                Text("⚠ Privacy: to do it, the bot's server learns that your Telegram account is in those pools " +
                    "(and Telegram sees the messages). It never learns which mixed output is yours, and it forgets " +
                    "each pool when it ends. The app talks to it over Tor; turning \"Only over Tor\" off lets it use https " +
                    "from your IP when Tor isn't running (Telegram already sees it). If that is too much, " +
                    "leave this off: the app's own notifications already tell you everything.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.enable(ctx); on = true; linked = false; asking = false
                runCatching { uri.openUri(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.linkUrl(ctx)) }
            }) { Text("ENABLE AND OPEN TELEGRAM", color = accent) }
        },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("NOT NOW", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🔔 Telegram alerts", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain(when {
                    !on -> "Follow your pools step by step in @${com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.BOT}. Optional."
                    linked == true -> "On: your pools are followed in @${com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.BOT}."
                    linked == false -> "Waiting for you to press Start in @${com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.BOT}…"
                    status?.error != null -> "Could not reach the bot to check whether you pressed Start in @${com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.BOT}: trying again."
                    else -> "Checking with @${com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.BOT}…"
                })
            }
            when {
                !on -> TextButton(onClick = { asking = true }) { Text("ENABLE", color = accent) }
                linked != true -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        runCatching { uri.openUri(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.linkUrl(ctx)) }
                    }) { Text("OPEN BOT", color = accent) }
                    TextButton(onClick = {
                        com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.disable(ctx); on = false; linked = null
                    }) { Text("CANCEL", color = TextSoft) }
                }
                else -> TextButton(onClick = {
                    com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.disable(ctx); on = false; linked = null
                }) { Text("TURN OFF", color = TextSoft) }
            }
        }
        if (on) {
            status?.let { st ->
                Text(if (st.error == null) "Last delivery: ok at ${hhmm(st.at / 1000)}" else "Last delivery failed at ${hhmm(st.at / 1000)}: ${st.error}",
                    style = MaterialTheme.typography.bodySmall, color = if (st.error == null) Good else Warn)
            }
            if (waiting > 0) Text(if (waiting == 1) "1 alert waiting to be sent again." else "$waiting alerts waiting to be sent again.",
                style = MaterialTheme.typography.bodySmall, color = TextFaint)
            // The bot has no test message: this asks it whether the phone is linked, the same way every alert goes.
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.check(ctx).also { com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.flush(ctx) }
                    }
                    if (r != null) linked = r
                    status = com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.status(ctx); waiting = com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.waiting(ctx)
                    checking = false
                }
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (checking) "checking… (over Tor it can take a few minutes)" else "CHECK THE CONNECTION TO THE BOT",
                    color = accent, style = MaterialTheme.typography.labelSmall)
            }
            if (com.kilombino.pyblockwatch.chain.Tor.available) {
                var torOnly by remember { mutableStateOf(com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.torOnly(ctx)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only over Tor", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                        Text(if (torOnly) "Alerts wait for Tor to start; if it doesn't, they wait here and nothing goes over https."
                            else "Over Tor when it is already running, otherwise over https (cjbot.kilombino.com): the bot's server sees this phone's address, as Telegram already does.",
                            style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
                    Switch(checked = torOnly, onCheckedChange = { torOnly = it; com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.setTorOnly(ctx, it) })
                }
            }
            // Why Tor doesn't come up, to copy and send: Arti's own log, without addresses or keys.
            if (com.kilombino.pyblockwatch.chain.Tor.available) {
                var torLog by remember { mutableStateOf<String?>(null) }
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                TextButton(onClick = { torLog = if (torLog == null) com.kilombino.pyblockwatch.chain.Tor.logTail(ctx).ifBlank { "(Tor has not run yet)" } else null },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (torLog == null) "SHOW TOR'S LOG" else "HIDE TOR'S LOG", color = accent, style = MaterialTheme.typography.labelSmall)
                }
                torLog?.let { l ->
                    Text(l, style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(l)) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("COPY THE LOG", color = accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- round cards

/** The five steps of a round, as the card's bar shows them. */
private const val STEPS = 5

/** Where a round is, 1..5: people, closing vote, outputs and signatures, sent, confirmed. */
private fun stepOf(st: PoolSession.State): Int = when (st.phase) {
    PoolSession.Phase.JOINING, PoolSession.Phase.OPEN -> 1
    PoolSession.Phase.VOTING -> 2
    PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING -> 3
    PoolSession.Phase.BROADCAST -> 4
    PoolSession.Phase.CONFIRMED -> 5
    PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> 0
}

/** True while the round waits on this person (an answer or a signature by hand). */
internal fun needsMe(st: PoolSession.State): Boolean {
    if (CoinjoinHub.isAuto(st.key)) return false
    return when (st.phase) {
        PoolSession.Phase.VOTING -> st.voteBy != st.token?.let { Protocol.tokenHash(it) } && st.votedOn != st.voteId
        PoolSession.Phase.SIGNING -> !st.postedSig
        else -> false
    }
}

private fun hhmm(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at * 1000))

/** Steps done in green, the current one in the accent, the rest as empty rings. */
@Composable
private fun RoundSteps(step: Int, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..STEPS) {
            val done = i < step || step == STEPS
            val now = i == step && step < STEPS
            androidx.compose.foundation.layout.Box(
                Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (done) Good else if (now) accent else Color.Transparent)
                    .then(if (done || now) Modifier else Modifier.border(1.5.dp, TextFaint, androidx.compose.foundation.shape.CircleShape))
            )
            if (i < STEPS) androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).height(2.dp).background(if (i < step) Good else TextFaint.copy(alpha = 0.5f))
            )
        }
    }
}

/**
 * One round at a glance: the amount, the bar of its five steps, what is happening and where
 * your coin is now. Tapping it shows the whole card ([MyPoolCard]), which opens by itself
 * while the round waits on you.
 */
@Composable
private fun RoundCard(
    vm: WalletViewModel, st: PoolSession.State, rev: Long, accent: Color, activity: FragmentActivity,
    open: Boolean, onToggle: () -> Unit, onAnotherSeat: () -> Unit, onMessage: (String) -> Unit,
) {
    if (rev < 0) return
    val step = stepOf(st)
    val over = step == 0
    val people = if (st.round.isNotEmpty()) st.round.size else st.seats.size
    val color = if (over) Bad else if (step == STEPS) Good else accent
    val status = when (st.phase) {
        PoolSession.Phase.JOINING -> "Asking to join"
        PoolSession.Phase.OPEN -> "Waiting for people · $people/${st.terms.maxPeers} people · closes at ${hhmm(st.terms.expiresAt)}"
        PoolSession.Phase.VOTING -> if (needsMe(st)) "Closing vote · your answer, by ${hhmm(st.voteDeadline)}" else "Closing vote · $people people"
        PoolSession.Phase.CLOSING -> "Closing · $people people · collecting outputs"
        PoolSession.Phase.SIGNING -> if (st.postedSig) "Signed · waiting for the others" else if (needsMe(st)) "Ready to sign" else "Signing"
        PoolSession.Phase.BROADCAST -> "Sent · waiting for a block"
        PoolSession.Phase.CONFIRMED -> "Confirmed"
        PoolSession.Phase.ABORTED -> "Cancelled"
        PoolSession.Phase.REJECTED -> "Refused"
    }
    // A payment through the pool: its outputs are the recipients', never this wallet's.
    val pay = remember(st.key) { Destination.of(activity.applicationContext, st.key) == Destination.Kind.PAY }
    val where = when (st.phase) {
        PoolSession.Phase.BROADCAST -> if (pay) "The payment left in the group transaction. Waiting for a block."
            else "Your coin left in the group transaction. Waiting for a block."
        PoolSession.Phase.CONFIRMED -> if (pay) "Done: paid ${groupSats(st.terms.mixTotal)} sats to the recipients."
            else if (st.terms.outputs > 1) "Done: your ${st.terms.outputs} mixed coins of ${groupSats(st.terms.amount)} sats are in your wallet."
            else "Done: your mixed coin of ${groupSats(st.terms.amount)} sats is in your wallet."
        PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> "${st.reason.replaceFirstChar { it.uppercase() }}. " +
            if (pay) "Nothing was paid: send it normally if you like." else "Your coin did not move."
        else -> if (pay) "Not paid yet: the payment goes out when the pool closes." else "Your coin is still in your wallet — it hasn't left."
    }
    Panel(accent = color) {
        Column(Modifier.fillMaxWidth().clickable { onToggle() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A payment pool's creator pays its recipients: no "each" about it.
                Text((if (st.terms.private) "🔒 " else "") + (if (pay) "💸 " else "") +
                    (if (st.terms.v5 && st.creator) "${groupSats(st.terms.mixTotal)} sats to ${st.terms.payments.size} recipient(s)" else "${poolAmount(st.terms)} sats each") +
                    (if (st.terms.script == ScriptType.P2TR) " · Taproot" else ""),
                    style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
                // The bar shows the step; here, how long the current one has left.
                val deadline = when (st.phase) {
                    PoolSession.Phase.JOINING, PoolSession.Phase.OPEN -> st.terms.expiresAt
                    PoolSession.Phase.VOTING -> st.voteDeadline
                    PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING -> st.phaseDeadline
                    else -> 0L
                }
                var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
                // Ticks only while this card is on screen and the app is in front.
                if (deadline > 0) LaunchedEffect(deadline, AppVisible.value) {
                    if (!AppVisible.value) return@LaunchedEffect
                    while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(1_000) }
                }
                val left = deadline - now
                Text((if (deadline > 0 && left > 0) "%d:%02d:%02d ".format(left / 3600, left % 3600 / 60, left % 60) else "") +
                    if (open) "⌄" else "›",
                    style = MaterialTheme.typography.titleMedium, color = color)
            }
            // More than one seat of this phone in the pool: which one this card is.
            CoinjoinHub.seatLabel(st)?.let { Text("Your $it", style = MaterialTheme.typography.labelSmall, color = accent) }
            if (!over) RoundSteps(step, accent)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = color)
            Spacer(Modifier.height(2.dp))
            Text(where, style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        // A perpetual coin's round: what it does after, and its switch.
        st.perpId?.let { id -> com.kilombino.pyblockwatch.coinjoin.Perpetual.list.value.firstOrNull { it.id == id } }
            ?.takeIf { it.seat == st.key }?.let { r -> PerpetualCard(vm, r, accent, activity, embedded = true, onMessage = onMessage) }
        // Another seat in this pool, with another coin: on the first seat's card, while it takes people.
        val mySeats = CoinjoinHub.seatsIn(st.poolId)
        if (st.phase == PoolSession.Phase.OPEN && mySeats.firstOrNull()?.key == st.key && st.seats.size < st.terms.maxPeers &&
            st.terms.expiresAt - System.currentTimeMillis() / 1000 > 300 && mySeats.size < st.terms.maxPeers)
            TextButton(onClick = onAnotherSeat, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("＋ JOIN WITH ANOTHER UTXO", color = accent, style = MaterialTheme.typography.labelMedium)
            }
        if (open) {
            Spacer(Modifier.height(8.dp))
            MyPoolCard(vm, st, rev, accent, activity, embedded = true, onMessage = onMessage)
        }
    }
}

/** JOIN · IN PROGRESS (n) · HISTORY. */
@Composable
private fun CoinjoinTabs(tab: Int, inProgress: Int, accent: Color, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("POOLS", "IN PROGRESS", "HISTORY").forEachIndexed { i, label ->
            val on = i == tab
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) accent else Color.Transparent)
                .clickable { onTab(i) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Ink else TextSoft, maxLines = 1)
                if (i == 1 && inProgress > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$inProgress", style = MaterialTheme.typography.labelSmall, color = if (on) accent else Ink,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (on) Ink else accent).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
        }
    }
}

/** A perfect coin sent and waiting for its first confirmation; then its pool goes on by itself. */
@Composable
private fun PerfectCoinCard(w: com.kilombino.pyblockwatch.coinjoin.PerfectCoin.Waiting, accent: Color, vm: WalletViewModel) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${groupSats(w.value)} sats · perfect coin", style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
            StillDot(accent)
        }
        RoundSteps(0, accent)
        Text("Waiting for its first confirmation", style = MaterialTheme.typography.bodyMedium, color = accent)
        Text(if (w.create != null) "Then your pool opens by itself and runs on its own." else "Then it joins the pool by itself and the round runs on its own.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
        Row {
            TextButton(onClick = { runCatching { uri.openUri("${vm.explorerFor(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B)}/tx/${w.txid}") } }) {
                Text("explorer", color = accent, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { com.kilombino.pyblockwatch.coinjoin.PerfectCoin.cancel(ctx, w.txid) }) {
                Text("don't join, keep the coin", color = TextFaint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Where the mixed outputs go, when there is more than this wallet to choose from. */
@Composable
private fun DestinationChooser(dests: List<Destination.Kind>, dest: Destination.Kind, enabled: Boolean, type: ScriptType, outputs: Int,
                               accent: Color, onDest: (Destination.Kind) -> Unit) {
    val ctx = LocalContext.current
    Text("Mixed outputs go to", style = MaterialTheme.typography.bodyMedium, color = TextMain)
    dests.forEach { d ->
        TextButton(onClick = { onDest(d) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            val c = if (dest == d) accent else TextMain
            Text(if (dest == d) "●" else "○", color = c, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Text(Destination.label(d) + (if (d == Destination.Kind.HOT) " (default)" else "") +
                (Destination.which(ctx, d)?.let { " · $it" } ?: ""),
                color = c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
    if (dest != Destination.Kind.HOT) Text("Unused receive addresses of that wallet; the change, if any, comes back here. " +
        "Nobody in the round can tell: an output is just an address of the pool's type.",
        color = TextFaint, style = MaterialTheme.typography.bodySmall)
    if (dest != Destination.Kind.HOT) DestinationPreview(dest, type, outputs, accent)
    Spacer(Modifier.height(8.dp))
}

/**
 * The addresses the mixed outputs would go to in another wallet, with their paths, to compare with
 * that wallet (Sparrow, Shrike…) before joining: asked to the server only when wanted.
 */
@Composable
private fun DestinationPreview(dest: Destination.Kind, type: ScriptType, outputs: Int, accent: Color) {
    val ctx = LocalContext.current
    var shown by remember(dest, type, outputs) { mutableStateOf<List<Destination.Shown>?>(null) }
    var error by remember(dest, type, outputs) { mutableStateOf<String?>(null) }
    var loading by remember(dest, type, outputs) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (shown == null) TextButton(enabled = !loading, onClick = {
        loading = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Destination.preview(ctx, dest, type, outputs) } }
                .onSuccess { shown = it }.onFailure { error = it.message ?: "could not reach the server" }
            loading = false
        }
    }) { Text(if (loading) "looking…" else "SHOW THE ADDRESSES", color = accent, style = MaterialTheme.typography.labelSmall) }
    error?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
    shown?.let {
        Text(if (it.size > 1) MANY else ONE, color = TextFaint, style = MaterialTheme.typography.bodySmall)
        AddressesShown(it)
    }
}

private const val ONE = "The next unused receive address of that wallet, as of now. Compare it with the wallet itself (in Sparrow or Shrike: Addresses › Receive)."
private const val MANY = "The next unused receive addresses of that wallet, as of now. Compare them with the wallet itself (in Sparrow or Shrike: Addresses › Receive)."

/** Full addresses with their paths, selectable to copy. */
@Composable
private fun AddressesShown(list: List<Destination.Shown>) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column {
            list.forEach { a ->
                androidx.compose.material3.Text(a.address, style = MaterialTheme.typography.bodySmall, color = TextMain)
                Text(a.path, style = MaterialTheme.typography.labelSmall, color = TextFaint)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** The perpetual coinjoin's checkbox, with its fee cap, on the join and create sheets. */
@Composable
private fun PerpetualChoice(on: Boolean, capText: String, accent: Color, onOn: (Boolean) -> Unit, onCap: (String) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().clickable { onOn(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "☑" else "☐", color = if (on) accent else TextFaint, modifier = Modifier.width(26.dp))
        Text("♻ Perpetual coinjoin", color = if (on) accent else TextMain, style = MaterialTheme.typography.bodyMedium)
    }
    Text(PERPETUAL_EXPLAIN, color = TextFaint, style = MaterialTheme.typography.bodySmall)
    if (on) OutlinedTextField(value = capText, onValueChange = { onCap(it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }) },
        label = { Text("fee cap (sat/vB)", style = MaterialTheme.typography.bodySmall) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
}

private const val PERPETUAL_EXPLAIN = "When the round confirms, your mixed coin joins another pool by itself (or opens one with the " +
    "whole coin, no change), again and again; each round costs it its fee share. It stops when you turn it off or spend the coin, " +
    "when it falls below a pool's minimum, or when fees rise above your cap. Only the hot wallet's output remixes, never a change. " +
    "While it is on, the key of this wallet's receive addresses of this type stays on the phone, encrypted, so it can sign without you."

/** What a perpetual coin is doing, as one line. */
private fun perpetualLine(r: com.kilombino.pyblockwatch.coinjoin.Remix): String = when (r.state) {
    com.kilombino.pyblockwatch.coinjoin.Remix.State.SEATED -> "♻ Perpetual: in a round now"
    com.kilombino.pyblockwatch.coinjoin.Remix.State.WAITING -> {
        val s = r.nextAt - System.currentTimeMillis() / 1000
        if (s > 0) "♻ Perpetual: looking for its next pool in ${s} s" else "♻ Perpetual: looking for its next pool"
    }
    com.kilombino.pyblockwatch.coinjoin.Remix.State.STOPPED -> "♻ Perpetual: stopped (${r.reason})"
}

/**
 * A perpetual coin: its rounds, what it cost, its fee cap and the switch. Shown on its round's card
 * ([embedded]) and on a card of its own between rounds (or once it stopped).
 */
@Composable
private fun PerpetualCard(vm: WalletViewModel, r: com.kilombino.pyblockwatch.coinjoin.Remix, accent: Color, activity: FragmentActivity,
                          embedded: Boolean, onMessage: (String) -> Unit) {
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    val on = r.state != com.kilombino.pyblockwatch.coinjoin.Remix.State.STOPPED
    var editing by remember(r.id) { mutableStateOf(false) }
    var capText by remember(r.id) { mutableStateOf(fmtRate(r.cap)) }
    // Off with its coin still there (turned off, fees over the cap…): it can go on again.
    val canTurnOn = !on && r.reason != com.kilombino.pyblockwatch.coinjoin.Remix.MOVED &&
        r.reason != com.kilombino.pyblockwatch.coinjoin.Remix.BELOW_MINIMUM && (r.txid != null || r.seat != null)
    @Composable fun body() {
        if (!embedded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.txid != null) "${groupSats(r.value)} sats · perpetual coin" else "perpetual coin",
                    style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
                if (r.state == com.kilombino.pyblockwatch.coinjoin.Remix.State.WAITING) StillDot(accent)
            }
        }
        Text(perpetualLine(r), style = MaterialTheme.typography.bodySmall, color = if (on) Good else TextFaint)
        if (r.rounds > 0) Text("Rounds so far: ${r.rounds} (${groupSats(r.startValue)} → ${groupSats(r.value)} sats)",
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Text("Fee cap: ${fmtRate(r.cap)} sat/vB", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        if (editing) {
            OutlinedTextField(value = capText, onValueChange = { capText = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } },
                label = { Text("fee cap (sat/vB)", style = MaterialTheme.typography.bodySmall) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            val c = capText.toDoubleOrNull()?.takeIf { it in 1.0..500.0 }
            TextButton(enabled = c != null, onClick = { c?.let { com.kilombino.pyblockwatch.coinjoin.Perpetual.setCap(ctx, r.id, it) }; editing = false }) {
                Text("SAVE", color = accent)
            }
        }
        Row {
            if (on) TextButton(onClick = { com.kilombino.pyblockwatch.coinjoin.Perpetual.turnOff(ctx, r.id) }) {
                Text("TURN OFF", color = Bad, style = MaterialTheme.typography.bodySmall)
            } else if (canTurnOn) TextButton(onClick = {
                // On again: one fingerprint gives back the key its next rounds sign with.
                runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                    Biometric.authenticate(activity, "Perpetual coinjoin", "Unlock to let it go on by itself", cipher,
                        onSuccess = { authed -> scope.launch {
                            runCatching { withContext(Dispatchers.Default) { com.kilombino.pyblockwatch.coinjoin.Perpetual.turnOn(ctx, r.id, vm.perpetualBranch(authed, r.type)) } }
                                .onFailure { onMessage(it.message ?: "could not unlock") }
                        } }, onError = onMessage)
                }.onFailure { onMessage(it.message ?: "could not unlock") }
            }) { Text("TURN ON AGAIN", color = accent, style = MaterialTheme.typography.bodySmall) }
            if (!editing && (on || canTurnOn)) TextButton(onClick = { editing = true }) {
                Text("change the fee cap", color = accent, style = MaterialTheme.typography.bodySmall)
            }
            if (!on && !embedded && r.seat == null) TextButton(onClick = { com.kilombino.pyblockwatch.coinjoin.Perpetual.forget(ctx, r.id) }) {
                Text("remove", color = TextFaint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (embedded) Column { body() } else Panel(accent = if (on) accent else TextFaint) { body() }
}
