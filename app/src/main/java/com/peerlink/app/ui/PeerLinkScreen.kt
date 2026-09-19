package com.peerlink.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.peerlink.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerlink.app.core.AppState
import com.peerlink.app.core.CrashLogger
import com.peerlink.app.core.LiveMatchState
import com.peerlink.app.core.MatchPhase
import com.peerlink.app.core.MatchStore
import com.peerlink.app.core.MatchTracker
import com.peerlink.app.core.PeerCoinStats
import com.peerlink.app.core.PeerInfo
import com.peerlink.app.core.PeerSessionPhase
import com.peerlink.app.core.PrimeGameplayTracker
import com.peerlink.app.core.formatCents
import com.peerlink.app.godmode.GodModeManager
import com.peerlink.app.godmode.PrimeActionFeedback
import com.peerlink.app.godmode.PrimeActionPhase
import com.peerlink.app.godmode.PrimeArtMode
import com.peerlink.app.godmode.PrimeGraphicsBackend
import com.peerlink.app.godmode.PrimeGraphicsMode
import com.peerlink.app.godmode.PrimeLinkState
import com.peerlink.app.godmode.PrimeMemoryAggression
import com.peerlink.app.service.CallMonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* PeerLink: charcoal, warm paper, lime and violet. */
private object PL {
    val bg        = Color(0xFF111318)
    val elevated  = Color(0xFF181B22)
    val surface   = Color(0xFF21242D)
    val ink       = Color(0xFFF6F5F0)
    val inkSoft   = Color(0xFFCCCBD2)
    val muted     = Color(0xFFA0A2AF)
    val line      = Color(0xFF323641)
    val lineSoft  = Color(0xFF484C59)
    val gold      = Color(0xFFD2F47A)
    val goldSoft  = Color(0xFFE4F8B4)
    val goldDeep  = Color(0xFF91B750)
    val green     = Color(0xFF7BE3C2)
    val greenGlow = Color(0xFFA5EFDA)
    val indigo    = Color(0xFFB5A2FF)
    val indigoGlow= Color(0xFFD5C8FF)
    val red       = Color(0xFFFFA29A)
    val paper     = Color(0xFFF0F2E9)
    val paperInk  = Color(0xFF252B29)
    val paperSoft = Color(0xFF525D55)
}

// Surfaces have tonal separation; outlines are reserved for actual controls.
private fun Modifier.glass(radius: Dp = 20.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(PL.surface)

// .glass-solid — opaque elevated surface variant.
private fun Modifier.glassSolid(radius: Dp = 20.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(PL.surface)

class PeerLinkActions(
    val saveName: (String) -> Unit,
    val connectToPeer: (PeerInfo) -> Unit,
    val disconnect: () -> Unit,
    val isAdmin: Boolean = false,
    val tryAdminUnlock: (String) -> Boolean = { false },
    val deviceId: String = "",
    val generateKey: (String) -> String = { "" },
    val onSetupPrimeMode: () -> Unit = {},
    val openBatterySettings: () -> Unit = {},
    val exportMatchLogs: () -> Unit = {},
    val callBlockEnabled: () -> Boolean = { false },
    val setCallBlock: (Boolean) -> Unit = {},
    val startDiscovery: () -> Unit = {},
    val setMatchMarker: (Boolean) -> Unit = {},
)

@Composable
fun PeerLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PL.gold, onPrimary = PL.bg,
            secondary = PL.indigo, onSecondary = PL.bg,
            background = PL.bg, onBackground = PL.ink,
            surface = PL.surface, onSurface = PL.ink,
            surfaceVariant = PL.elevated, outline = PL.line,
            onSurfaceVariant = PL.inkSoft, onError = PL.bg, error = PL.red
        ),
        content = content
    )
}

@Composable
fun PeerLinkScreen(
    ctx: Context,
    peers: List<PeerInfo>,
    discovery: DiscoveryUiState,
    actions: PeerLinkActions,
) {
    val prefs = remember { ctx.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE) }
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { prefs.edit().putString("transport_mode", "wifi").apply() }

    var connected by remember { mutableStateOf(AppState.isRunning.get()) }
    var sessionPhase by remember { mutableStateOf(AppState.sessionPhase.get()) }
    var sessionError by remember { mutableStateOf(AppState.sessionError.get()) }
    var tunneled by remember { mutableStateOf(AppState.tunneled.get()) }
    var passed by remember { mutableStateOf(AppState.passedThrough.get()) }
    var myIp by remember { mutableStateOf(AppState.myFabricatedIp) }
    var peerIp by remember { mutableStateOf(AppState.peerFabricatedIp) }
    var since by remember { mutableStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                connected = AppState.isRunning.get()
                since = AppState.sessionStartedElapsedMs.get()
                tunneled = AppState.tunneled.get()
                passed = AppState.passedThrough.get()
                sessionPhase = AppState.sessionPhase.get()
                sessionError = AppState.sessionError.get()
                myIp = AppState.myFabricatedIp
                peerIp = AppState.peerFabricatedIp
                delay(500L)
            }
        }
    }

    LaunchedEffect(Unit) { currentActions.startDiscovery() }

    // ── Auto-restart discovery when Wi-Fi connects ─────────────────────
    // Previously, discovery was started once at screen load. If the user
    // opened the app without a Wi-Fi connection, discovery silently failed
    // (no local IP to bind to). When Wi-Fi connected later, discovery wasn't
    // restarted — the user had to clear/force-stop/reopen the app to see
    // peers. This LaunchedEffect registers a NetworkCallback that watches
    // for Wi-Fi becoming available and re-triggers startDiscovery() when it
    // does. It skips the restart if a session is already active.
    LaunchedEffect(Unit) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return@LaunchedEffect
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    uiScope.launch {
                        if (!AppState.isRunning.get()) {
                            AppState.appendLog("[NET-DISC  ] Wi-Fi path available — reconciling discovery")
                            currentActions.startDiscovery()
                        }
                    }
                }
                override fun onLost(network: android.net.Network) {
                    uiScope.launch {
                        // Let ConnectivityManager promote another Wi-Fi path
                        // before resolving. If none remains, the UI moves to
                        // WAITING_FOR_WIFI instead of falsely saying "scanning".
                        delay(250L)
                        if (!AppState.isRunning.get()) currentActions.startDiscovery()
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        } catch (_: Throwable) {
            // If we can't register the callback, the original one-shot
            // startDiscovery() above still ran — discovery just won't
            // auto-restart on Wi-Fi connect. Not fatal.
        }
    }

    var name by rememberSaveable { mutableStateOf(prefs.getString("username", "") ?: "") }
    var drawerOpen by remember { mutableStateOf(false) }
    var adminOpen by remember { mutableStateOf(false) }
    var adminUnlockOpen by remember { mutableStateOf(false) }


    val primeState by GodModeManager.state.collectAsStateWithLifecycle()
    val primeSetup by GodModeManager.setupSnapshot.collectAsStateWithLifecycle()
    // isPaired now requires PRIME_MODE_ACTIVE — previously it was true during
    // DISCOVERING, PAIRING, and PAIRED_IDLE (which includes "Bootstrap pending"),
    // making all Prime tools clickable long before PrimeServer was actually
    // running. The user reported "all the prime mode buttons turn on fully
    // clickable and am wondering did it connect or not?" — this fixes that.
    // Tools are visually disabled (alpha 0.35) until Prime Mode is truly active.
    val primeActive = primeState == GodModeManager.State.PRIME_MODE_ACTIVE
    val primeReady = primeSetup.bootstrapped || primeSetup.pairedTrusted

    fun primeToast() = Toast.makeText(
        ctx,
        if (primeReady) "Activate Prime Mode first" else "Set up Prime Mode first",
        Toast.LENGTH_SHORT,
    ).show()

    val pager = rememberPagerState(pageCount = { 2 })
    BackHandler(drawerOpen || adminOpen) { drawerOpen = false; adminOpen = false }

    Box(Modifier.fillMaxSize().background(PL.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.ic_peerlink_mark), null, Modifier.size(42.dp))
                Column(Modifier.weight(1f).pointerInput(actions.isAdmin) {
                    detectTapGestures(onLongPress = { if (!actions.isAdmin) adminUnlockOpen = true })
                }) {
                    Text("PeerLink", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = PL.ink, letterSpacing = (-0.6).sp)
                    Text("Better together", fontSize = 12.sp, color = PL.muted)
                }
                if (actions.isAdmin) DarkHeaderBtn(Icons.Rounded.Settings) { adminOpen = true }
                StatusChip(sessionPhase)
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                if (page == 0) MainPage(
                    name, { name = it; actions.saveName(it) },
                    connected, sessionPhase, sessionError, peers, discovery, tunneled, passed, myIp, peerIp, since, primeActive, primeReady,
                    onTapPeer = { p ->
                        if (!connected && (sessionPhase == PeerSessionPhase.IDLE || sessionPhase == PeerSessionPhase.ERROR)) {
                            actions.connectToPeer(p)
                        }
                    },
                    onDisconnect = { actions.disconnect() },
                    onRetryDiscovery = actions.startDiscovery,
                    onToolToggle = { key, want ->
                        if (!primeReady) { primeToast(); false }
                        else if (primeActive && key != "calls") {
                            Toast.makeText(ctx, "Deactivate Prime Mode before changing this setting", Toast.LENGTH_SHORT).show()
                            false
                        }
                        else {
                            when (key) {
                                "shield" -> GodModeManager.setAirplaneShieldEnabled(want)
                                "ram" -> GodModeManager.setKeepGameInRam(want)
                                "calls" -> actions.setCallBlock(want)
                                "auto" -> GodModeManager.setAutoConnectEnabled(want)
                            }; true
                        }
                    },
                    callBlockOn = actions.callBlockEnabled(),
                    ctx = ctx
                ) else CoinPage()
            }
            NavigationBar(containerColor = PL.elevated, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = pager.currentPage == 0 && !drawerOpen,
                    onClick = { uiScope.launch { pager.animateScrollToPage(0) } },
                    icon = { Icon(Icons.Rounded.SportsEsports, null) }, label = { Text("Play") },
                    colors = peerNavigationColors(),
                )
                NavigationBarItem(
                    selected = pager.currentPage == 1 && !drawerOpen,
                    onClick = { uiScope.launch { pager.animateScrollToPage(1) } },
                    icon = { Icon(Icons.Rounded.BarChart, null) }, label = { Text("Activity") },
                    colors = peerNavigationColors(),
                )
                NavigationBarItem(
                    selected = drawerOpen, onClick = { drawerOpen = true },
                    icon = { Icon(Icons.Rounded.Tune, null) }, label = { Text("Settings") },
                    colors = peerNavigationColors(),
                )
            }
        }

        if (drawerOpen || adminOpen) Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(
            indication = null, interactionSource = null) { drawerOpen = false; adminOpen = false })

        SidePanel(drawerOpen, true, { drawerOpen = false }) {
            SettingsMenu(
                primeReady = primeReady,
                primeActive = primeActive,
                onSetupPrime = { drawerOpen = false; actions.onSetupPrimeMode() },
                onBatterySettings = actions.openBatterySettings,
                onExportLogs = actions.exportMatchLogs,
                onMatchMarker = actions.setMatchMarker,
                ctx = ctx,
            )
        }
        SidePanel(adminOpen, false, { adminOpen = false }) { AdminMenu(ctx, actions) }

        if (adminUnlockOpen) AdminUnlockDialog(onClose = { adminUnlockOpen = false }) { entered ->
            if (actions.tryAdminUnlock(entered)) {
                adminUnlockOpen = false; Toast.makeText(ctx, "Admin unlocked", Toast.LENGTH_SHORT).show(); true
            } else false
        }


    }
}

@Composable
private fun peerNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = PL.bg, selectedTextColor = PL.gold,
    indicatorColor = PL.gold,
    unselectedIconColor = PL.muted, unselectedTextColor = PL.muted,
)

@Composable
private fun MainPage(
    name: String, onName: (String) -> Unit,
    connected: Boolean, sessionPhase: PeerSessionPhase, sessionError: String?,
    peers: List<PeerInfo>, discovery: DiscoveryUiState,
    tunneled: Long, passed: Long, myIp: String, peerIp: String,
    since: Long, primeActive: Boolean, primeReady: Boolean,
    onTapPeer: (PeerInfo) -> Unit, onDisconnect: () -> Unit,
    onRetryDiscovery: () -> Unit,
    onToolToggle: (String, Boolean) -> Boolean, callBlockOn: Boolean,
    ctx: Context,
) {
    val liveMatch by MatchTracker.state.collectAsStateWithLifecycle()
    val connectionPending = sessionPhase == PeerSessionPhase.PAIRING ||
        sessionPhase == PeerSessionPhase.STARTING_TUNNEL ||
        sessionPhase == PeerSessionPhase.VERIFYING_PATH ||
        sessionPhase == PeerSessionPhase.STOPPING
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("home-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "identity") { NameRow(name, onName) }
        item(key = "connection") {
            ConnectionCore(connected, sessionPhase, sessionError, myIp, peerIp, since) {
                val launch = ctx.packageManager.getLaunchIntentForPackage("jp.konami.pesam")
                if (launch != null) runCatching { ctx.startActivity(launch) }.onFailure {
                    Toast.makeText(ctx, "Open eFootball from your home screen", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(ctx, "Open your game from your home screen", Toast.LENGTH_SHORT).show()
            }
        }
        if (connected) {
            item(key = "traffic") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), "Game packets", tunneled, PL.green, true)
                    StatCard(Modifier.weight(1f), "Other packets", passed, PL.gold, false)
                }
            }
            item(key = "disconnect") {
                DisconnectCard(AppState.connectedPeerName.ifBlank { "Player" }, onDisconnect)
            }
        } else if (connectionPending) {
            item(key = "progress") { ConnectionProgressCard(sessionPhase, onDisconnect) }
        } else {
            item(key = "players-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nearby players", color = PL.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetryDiscovery) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Refresh")
                    }
                }
            }
            if (peers.isEmpty()) item(key = "no-players") { EmptyPeers(discovery) }
            items(peers.distinctBy { it.ip }, key = { "peer:${it.ip}" }) { peer ->
                PeerRow(peer, false, onTap = { onTapPeer(peer) })
            }
        }
        if (liveMatch.phase == MatchPhase.LIVE) {
            item(key = "match") { LiveScoreStrip(liveMatch) }
        }
        item(key = "prime") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Bolt, null, tint = PL.indigo, modifier = Modifier.size(22.dp))
                    Text("Make it your game", color = PL.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Text(if (primeActive) "Active" else if (primeReady) "Ready" else "Optional",
                        color = if (primeActive) PL.green else PL.muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(if (primeReady) "Your match preferences, in one place." else "Set up Prime Mode in Settings for more control.",
                    color = PL.muted, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(14.dp))
                PrimeDeck(primeReady, primeActive, callBlockOn, onToolToggle, ctx)
            }
        }
    }
}

@Composable
private fun DisconnectCard(peerName: String, onDisconnect: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Playing with", color = PL.muted, fontSize = 12.sp)
            Text(peerName, color = PL.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { confirm = true }) { Text("Disconnect", color = PL.red) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("End this connection?") },
        text = { Text("Disconnecting during a match can interrupt the game for both players.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDisconnect() }) { Text("Disconnect", color = PL.red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep playing") } },
    )
}

/* ─── Components ─── */
@Composable private fun DarkHeaderBtn(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = if (icon == Icons.Rounded.Settings) "Administration" else "Open menu", tint = PL.inkSoft)
    }
}

@Composable private fun StatusChip(phase: PeerSessionPhase) {
    val (label, color) = when (phase) {
        PeerSessionPhase.ACTIVE -> "Connected" to PL.green
        PeerSessionPhase.PAIRING -> "Pairing" to PL.gold
        PeerSessionPhase.STARTING_TUNNEL -> "Starting" to PL.gold
        PeerSessionPhase.VERIFYING_PATH -> "Checking" to PL.indigoGlow
        PeerSessionPhase.STOPPING -> "Stopping" to PL.muted
        PeerSessionPhase.ERROR -> "Retry" to PL.red
        PeerSessionPhase.IDLE -> "Ready" to PL.muted
    }
    Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.10f)).padding(10.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable private fun DarkCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.glass(20.dp).padding(padding), content = content)
}

@Composable private fun SectionTitle(t: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = PL.muted, letterSpacing = 1.8.sp, modifier = Modifier.padding(start = 4.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(PL.line, Color.Transparent))))
    }
}

@Composable private fun NameRow(name: String, onSave: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(name) { mutableStateOf(name) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PLAY AS", fontSize = 11.sp, letterSpacing = 1.4.sp, color = PL.indigo)
            Text(name.ifBlank { "Choose your name" }, fontSize = 21.sp, color = PL.ink,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { draft = name; editing = true }) {
            Icon(Icons.Rounded.Edit, "Edit player name", tint = PL.inkSoft, modifier = Modifier.size(20.dp))
        }
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Player name") },
        text = {
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 18) draft = it },
                label = { Text("Name shown to nearby players") }, singleLine = true,
                supportingText = { Text("${draft.length}/18 characters") })
        },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = { onSave(draft.trim()); editing = false }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )
}

@Composable private fun ConnectionCore(
    connected: Boolean, phase: PeerSessionPhase, error: String?,
    myIp: String, peerIp: String, since: Long,
    onOpenGame: () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    var elapsed by remember { mutableStateOf("00:00") }
    LaunchedEffect(since, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (since > 0L) {
                val seconds = ((android.os.SystemClock.elapsedRealtime() - since).coerceAtLeast(0L) / 1000L)
                elapsed = "%02d:%02d".format(seconds / 60L, seconds % 60L)
                delay(1_000L)
            }
            elapsed = "00:00"
        }
    }
    val title = when (phase) {
        PeerSessionPhase.IDLE -> "Good games.\nGreat company."
        PeerSessionPhase.PAIRING -> "Meeting your player."
        PeerSessionPhase.STARTING_TUNNEL -> "Preparing your link."
        PeerSessionPhase.VERIFYING_PATH -> "Checking the connection."
        PeerSessionPhase.ACTIVE -> "Your friend.\nYour next match."
        PeerSessionPhase.STOPPING -> "Ending the connection."
        PeerSessionPhase.ERROR -> "Let's reconnect."
    }
    val description = when (phase) {
        PeerSessionPhase.IDLE -> "Join the same Wi-Fi or hotspot. Find your friend below and connect."
        PeerSessionPhase.PAIRING -> "Keep PeerLink open on both phones while they pair."
        PeerSessionPhase.STARTING_TUNNEL -> "Allow the VPN request on each phone to continue."
        PeerSessionPhase.VERIFYING_PATH -> "Making sure both phones can exchange game traffic."
        PeerSessionPhase.ACTIVE -> "Open your game on both phones and start your match."
        PeerSessionPhase.STOPPING -> "Closing this session before you connect again."
        PeerSessionPhase.ERROR -> error ?: "Check that both phones are on the same network, then try again."
    }
    val paper = if (phase == PeerSessionPhase.ERROR) Color(0xFFFFE4DE) else PL.paper
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
        .background(Brush.linearGradient(listOf(paper, if (connected) Color(0xFFDDF1D3) else Color(0xFFE3E5F4))))
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(7.dp).background(if (connected) Color(0xFF297353) else PL.paperSoft, CircleShape))
                Text(if (connected) "LINK ACTIVE · $elapsed" else "PLAY SIDE BY SIDE", color = PL.paperSoft,
                    fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            }
            Image(painterResource(R.drawable.ic_peerlink_mark), null,
                Modifier.size(54.dp).background(PL.paperInk, CircleShape).padding(7.dp))
        }
        Text(title, color = PL.paperInk, fontSize = 32.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Text(description, color = PL.paperSoft, fontSize = 14.sp, lineHeight = 21.sp)
        if (connected) {
            Button(onClick = onOpenGame, shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = PL.paperInk, contentColor = PL.paper),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text("Open eFootball", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.NorthEast, null, Modifier.size(19.dp))
            }
        } else if (phase == PeerSessionPhase.IDLE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Wifi, null, tint = PL.paperSoft, modifier = Modifier.size(18.dp))
                Text("Same Wi-Fi. Ready when you are.", color = PL.paperSoft, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun IpPill(label: String, ip: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.clip(RoundedCornerShape(100.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = PL.muted, letterSpacing = 0.8.sp)
        Text(ip, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = PL.inkSoft, fontFamily = FontFamily.Monospace)
    }
}


@Composable private fun StatCard(modifier: Modifier, label: String, value: Long, color: Color, up: Boolean) {
    Column(modifier.padding(vertical = 4.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = PL.inkSoft)
        Text("%,d".format(value), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun ConnectionProgressCard(phase: PeerSessionPhase, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), color = PL.gold, strokeWidth = 2.dp)
        Text(if (phase == PeerSessionPhase.STOPPING) "Closing your session…" else "Keep both phones nearby…",
            color = PL.inkSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (phase != PeerSessionPhase.STOPPING) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable private fun EmptyPeers(discovery: DiscoveryUiState) {
    val waiting = discovery.phase == DiscoveryPhase.WAITING_FOR_WIFI
    val denied = discovery.phase == DiscoveryPhase.WAITING_FOR_PERMISSION
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(50.dp).background(PL.indigo.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (waiting) Icons.Rounded.WifiOff else if (denied) Icons.Rounded.Lock else Icons.Rounded.PeopleOutline,
                null, tint = PL.indigo, modifier = Modifier.size(24.dp))
        }
        Text(if (waiting) "Bring both phones onto Wi-Fi" else if (denied) "Allow nearby discovery" else "Looking for your player",
            color = PL.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(discovery.message, color = PL.inkSoft, fontSize = 13.sp, lineHeight = 20.sp)
        Text("Open PeerLink on your friend's phone too. You can use a phone hotspot or the same Wi-Fi network.",
            color = PL.muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable private fun PeerRow(peer: PeerInfo, isConnected: Boolean, isNew: Boolean = false, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !isConnected, onClick = onTap).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(PL.indigo.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Text(peer.name.firstOrNull()?.uppercase() ?: "P", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PL.indigo)
        }
        Column(Modifier.weight(1f)) {
            Text(peer.name.ifBlank { "Nearby player" }, color = PL.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (isConnected) "Connected" else "Available on your network", color = PL.muted, fontSize = 12.sp)
        }
        Box(Modifier.size(44.dp).background(PL.gold, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowForward, contentDescription = "Connect to ${peer.name.ifBlank { "player" }}", tint = PL.bg, modifier = Modifier.size(20.dp))
        }
    }
}





/* ─── Prime Deck ─── */
@Composable private fun PrimeDeck(
    primeReady: Boolean,
    primeActive: Boolean,
    callBlockOn: Boolean,
    onToggle: (String, Boolean) -> Boolean,
    ctx: Context,
) {
    val gm = GodModeManager
    // Fixed: Auto mode now properly reads its state from prefs
    var autoOn by remember { mutableStateOf(gm.isAutoConnectEnabled()) }
    val tools = listOf(
        Tool("shield", "Shield", Icons.Rounded.Shield),
        Tool("ram", "Priority", Icons.Rounded.Memory),
        Tool("calls", "No Calls", Icons.Rounded.PhoneDisabled),
        Tool("auto", "Auto", Icons.Rounded.Autorenew)
    )
    val states = remember(primeReady, primeActive, callBlockOn, autoOn) {
        mutableStateMapOf(
            "shield" to gm.isAirplaneShieldEnabled(),
            "ram" to gm.getKeepGameInRam(),
            "calls" to callBlockOn,
            "auto" to autoOn
        )
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 340.dp && fontScale <= 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tools.chunked(columns).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.forEach { t ->
                        val selected = states[t.key] == true
                        val on = if (t.key == "calls") callBlockOn else primeActive && selected
                        val enabled = primeReady && (t.key == "calls" || !primeActive)
                        val tint = when (t.key) {
                            "shield" -> PL.indigo
                            "calls" -> PL.red
                            "ram" -> PL.green
                            else -> PL.gold
                        }
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .toggleable(value = selected, enabled = enabled, role = Role.Switch) { want ->
                                if (onToggle(t.key, want)) {
                                    states[t.key] = want
                                    if (t.key == "auto") autoOn = want
                                }
                            }.heightIn(min = 68.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(40.dp).background(tint.copy(alpha = if (on) 0.22f else 0.10f), CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(t.icon, null, tint = tint, modifier = Modifier.size(19.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(t.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PL.ink)
                                Text(if (!primeReady) "Set up Prime" else if (on) "On" else if (selected) "Ready" else "Off",
                                    fontSize = 11.sp, color = if (on) tint else PL.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Tool(val key: String, val label: String, val icon: ImageVector)

/* ─── PeerCoin ledger ─── */
@Composable private fun CoinPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by MatchTracker.state.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf(PeerCoinStats(emptyList())) }
    LaunchedEffect(live.lastCompleted?.id) {
        stats = withContext(Dispatchers.IO) {
            runCatching { MatchStore.stats(context) }.getOrDefault(stats)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("Your activity", color = PL.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Text("Your matches and PeerCoins, together.", color = PL.inkSoft, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        if (live.phase != MatchPhase.NO_MATCH) LiveScoreCard(live)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF302C44), Color(0xFF242331))))
            .padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR PEERCOINS", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = PL.indigoGlow, letterSpacing = 1.4.sp)
                Text(formatCents(stats.balanceCents), fontSize = 42.sp, fontWeight = FontWeight.Bold, color = PL.ink, letterSpacing = (-1.5).sp)
            }
            Box(Modifier.size(50.dp).background(PL.indigo, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Star, null, tint = PL.bg, modifier = Modifier.size(26.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoinTile(Modifier.weight(1f), "Settled", "${stats.matchCount}", PL.ink)
            CoinTile(Modifier.weight(1f), "Wins", "${stats.wins}", PL.green)
        }
        if (stats.records.isEmpty()) Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("No matches yet", color = PL.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Connect with a nearby player to get started. Completed sessions appear here.", color = PL.inkSoft, fontSize = 13.sp, lineHeight = 20.sp)
        }
        stats.records.takeLast(5).asReversed().forEach { match ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(match.opponentName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PL.ink)
                        Text(
                            if (match.confirmed) "Verified and settled" else (match.settlementNote ?: "Unverified — no coins"),
                            fontSize = 10.sp,
                            color = if (match.confirmed) PL.green else PL.gold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${match.myGoals} – ${match.opponentGoals}",
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = PL.ink,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        (if (match.settledReward.totalCents > 0) "+" else "") + formatCents(match.settledReward.totalCents),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (match.settledReward.totalCents > 0) PL.green else if (match.settledReward.totalCents < 0) PL.red else PL.muted,
                    )
                }
            }
        }
        Text("Only matches verified from the eFootball final result screen can change this balance.", fontSize = 12.sp, color = PL.inkSoft, lineHeight = 18.sp, modifier = Modifier.padding(4.dp))
    }
}

@Composable private fun LiveScoreCard(state: LiveMatchState) {
    DarkCard(padding = 14.dp) {
        LiveScoreContent(state)
        if (state.statusNote.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(state.statusNote, fontSize = 10.sp, color = PL.muted)
        }
        val rows = state.stats?.rows ?: state.lastCompleted?.stats?.rows
        if (!rows.isNullOrEmpty()) {
            Spacer(Modifier.height(10.dp))
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(statLabel(row.name), fontSize = 11.sp, color = PL.inkSoft, modifier = Modifier.weight(1f))
                    Text("${row.home}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PL.ink)
                    Text(" – ", fontSize = 12.sp, color = PL.muted)
                    Text("${row.away}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PL.ink)
                }
            }
        }
    }
}

private fun statLabel(name: String): String = when (name) {
    "TotalShots" -> "Total shots"
    "ShotsOnTarget" -> "Shots on target"
    "CornerKicks" -> "Corners"
    "FreeKicks" -> "Free kicks"
    "SuccessfulPasses" -> "Successful passes"
    else -> name
}

@Composable private fun LiveScoreStrip(state: LiveMatchState) {
    LiveScoreContent(
        state,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(PL.green.copy(alpha = 0.08f))
            .border(1.dp, PL.green.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable private fun LiveScoreContent(state: LiveMatchState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (state.phase) {
                MatchPhase.LIVE -> "LIVE"
                MatchPhase.SEALED -> "SEALED"
                else -> "WAITING"
            },
            fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = PL.green
        )
        Spacer(Modifier.weight(1f))
        val score = "You ${state.myGoals} – ${state.opponentGoals}"
        Text("$score ${state.opponentName.ifBlank { "Opponent" }}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = PL.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun CoinTile(m: Modifier, label: String, value: String, c: Color) {
    Column(m.padding(vertical = 12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = c)
            Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = PL.muted, letterSpacing = 1.sp)
        }
    }
}



@Composable private fun SidePanel(open: Boolean, fromLeft: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(open, enter = slideInHorizontally(tween(360)) { if (fromLeft) -it else it },
        exit = slideOutHorizontally(tween(300)) { if (fromLeft) -it else it }, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (!fromLeft) Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.92f).statusBarsPadding()
                .shadow(30.dp, if (fromLeft) RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp) else RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp), clip = false)
                .clip(if (fromLeft) RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp) else RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                .background(PL.elevated)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.06f),
                    shape = if (fromLeft) RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp) else RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                )
            ) {
                Column(Modifier.navigationBarsPadding().padding(20.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close panel", tint = PL.inkSoft) }
                    }
                    content()
                }
            }
            if (fromLeft) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable private fun SettingsMenu(
    primeReady: Boolean,
    primeActive: Boolean,
    onSetupPrime: () -> Unit,
    onBatterySettings: () -> Unit,
    onExportLogs: () -> Unit,
    onMatchMarker: (Boolean) -> Unit,
    ctx: Context,
) {
    var markerEnabled by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            markerEnabled = ctx.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE)
                .getBoolean("match_marker_enabled", true) && android.provider.Settings.canDrawOverlays(ctx)
        }
    }
    var open by remember { mutableStateOf(-1) }
    Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = PL.ink, modifier = Modifier.padding(bottom = 14.dp, start = 4.dp))
    GhostBtn("Export match diagnostics", Icons.Rounded.Download) { onExportLogs() }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Match markers", color = PL.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Home / Away marker while playing", color = PL.muted, fontSize = 12.sp)
        }
        Switch(checked = markerEnabled, onCheckedChange = {
            markerEnabled = it; onMatchMarker(it)
        })
    }
    Spacer(Modifier.height(10.dp))
    Acc(0, open, "Settings", "Tools, blocking & Prime setup", Icons.Rounded.Tune, { open = if (open == 0) -1 else 0 }) {
        Para("Keep both phones on the same Wi-Fi or hotspot. If a match feels delayed, save the connection logs after playing so the timing on each phone can be checked.")
        Spacer(Modifier.height(6.dp))
        GhostBtn(
            when {
                primeActive -> "Manage Prime Mode"
                primeReady -> "Activate Prime Mode"
                else -> "Set up Prime Mode"
            },
            Icons.Rounded.AutoAwesome,
        ) { onSetupPrime() }
        Spacer(Modifier.height(8.dp))
        GhostBtn("Battery protection settings", Icons.Rounded.BatterySaver) {
            onBatterySettings()
        }
    }
    Acc(
        1,
        open,
        when {
            primeActive -> "Prime Mode · Active"
            primeReady -> "Prime Mode · Ready"
            else -> "Prime Mode · Not set up"
        },
        "What it does & every feature",
        Icons.Rounded.Bolt,
        { open = if (open == 1) -1 else 1 },
    ) {
        Para("Prime Mode unlocks verified controls during a match. Pair once, configure the tools, then activate Prime Mode.")
        Feat("Shield", "Mis-tap airplane mode mid-match? Only data drops \u2014 your link survives.")
        Feat("Game Priority", "Reduces Doze and standby restrictions. Android does not let a non-root app pin another game permanently in RAM.")
        Feat("No Calls", "Silently rejects calls only while you're playing.")
        Feat("Auto", "When Prime is ready, turns Wi-Fi on silently. PeerLink never turns it off automatically.")
    }
    Acc(2, open, "Rewards", "How PeerCoins work", Icons.Rounded.Stars, { open = if (open == 2) -1 else 2 }) {
        Para("You earn PeerCoins by playing and winning matches over PeerLink.")
        Para("Goal difference sweetens it \u2014 beat your rival by more, earn more.")
    }
}

@Composable private fun AdminMenu(ctx: Context, actions: PeerLinkActions) {
    var open by remember { mutableStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp, start = 4.dp)) {
        Text("Admin", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = PL.ink)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(PL.gold.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text("PRIVATE", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = PL.gold, letterSpacing = 1.2.sp)
        }
    }
    var devId by remember { mutableStateOf(TextFieldValue("")) }
    var key by remember { mutableStateOf("") }
    val clip = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }

    Acc(0, open, "Key Generator", "Issue an access key", Icons.Rounded.VpnKey, { open = if (open == 0) -1 else 0 }) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PL.surface).padding(12.dp)) {
            Column {
                Text("THIS DEVICE ID", fontSize = 8.5.sp, color = PL.muted, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Text(actions.deviceId.ifBlank { "\u2014" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PL.gold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(PL.surface).border(1.dp, PL.line, RoundedCornerShape(11.dp)).padding(10.dp, 9.dp)) {
            BasicTextField(devId, { devId = it.copy(text = it.text.uppercase()); key = "" }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(PL.ink, 15.sp, FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(PL.gold), modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (devId.text.isEmpty()) Text("Enter device ID", color = PL.muted, fontSize = 15.sp); inner() } })
        }
        Spacer(Modifier.height(10.dp))
        GhostBtn("Generate key", Icons.Rounded.Bolt) { if (devId.text.isNotBlank()) key = actions.generateKey(devId.text.trim()) }
        if (key.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PL.surface).border(1.dp, PL.gold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .clickable { clip.setPrimaryClip(android.content.ClipData.newPlainText("key", key)); Toast.makeText(ctx, "Key copied", Toast.LENGTH_SHORT).show() }
                .padding(12.dp), contentAlignment = Alignment.Center) {
                Text(key, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PL.gold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
            }
        }
    }

    var crashCount by remember { mutableStateOf(CrashLogger.getCrashCount(ctx)) }
    var report by remember { mutableStateOf<String?>(null) }
    Acc(1, open, "Crash Logs", "Diagnostics", Icons.Rounded.Warning, { open = if (open == 1) -1 else 1 }) {
        Text(if (crashCount > 0) "$crashCount crash${if (crashCount != 1) "es" else ""} recorded" else "No crashes recorded", fontSize = 12.5.sp, color = PL.inkSoft, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostBtn(if (report == null) "View" else "Hide", Icons.Rounded.Visibility, Modifier.weight(1f)) { report = if (report == null) (CrashLogger.getLastReport(ctx) ?: "No report") else null }
            GhostBtn("Copy", Icons.Rounded.ContentCopy, Modifier.weight(1f)) {
                val r = CrashLogger.getLastReport(ctx)
                if (r.isNullOrBlank()) Toast.makeText(ctx, "No crash report to copy", Toast.LENGTH_SHORT).show()
                else {
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("crash", r))
                    Toast.makeText(ctx, "Crash log copied", Toast.LENGTH_SHORT).show()
                }
            }
            GhostBtn("Clear", Icons.Rounded.Delete, Modifier.weight(1f)) { CrashLogger.clearReport(ctx); crashCount = 0; report = null }
        }
        if (report != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)).background(PL.surface).verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(report ?: "", fontSize = 10.sp, color = PL.inkSoft, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Acc(2, open, "Match Logs", "Save & export records", Icons.Rounded.Description, { open = if (open == 2) -1 else 2 }) {
        Para("Match diagnostics are saved automatically while a session is active. Exporting does not pause the tunnel.")
        GhostBtn("Export match logs", Icons.Rounded.FileDownload) { actions.exportMatchLogs(); Toast.makeText(ctx, "Exporting\u2026", Toast.LENGTH_SHORT).show() }
    }
}

@Composable private fun AdminUnlockDialog(onClose: () -> Unit, onSubmit: (String) -> Boolean) {
    var k by remember { mutableStateOf(TextFieldValue("")) }
    var err by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text("Admin access", fontWeight = FontWeight.Bold, color = PL.ink) },
        text = { Column {
            Text("Enter the master key to unlock admin tools.", fontSize = 13.sp, color = PL.inkSoft)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(PL.surface).border(1.5.dp, if (err) PL.red else PL.line, RoundedCornerShape(11.dp)).padding(10.dp, 9.dp)) {
                BasicTextField(k, { k = it; err = false }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(PL.ink, 16.sp, FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(PL.gold), modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> Box { if (k.text.isEmpty()) Text("MASTER KEY", color = PL.muted, fontSize = 15.sp, letterSpacing = 1.sp); inner() } })
            }
            if (err) { Spacer(Modifier.height(6.dp)); Text("Incorrect key", color = PL.red, fontSize = 11.sp) }
        } },
        confirmButton = { TextButton({ if (!onSubmit(k.text)) err = true }) { Text("Unlock", color = PL.gold, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClose) { Text("Cancel", color = PL.muted) } }, containerColor = PL.surface)
}

@Composable private fun Acc(idx: Int, open: Int, title: String, sub: String, icon: ImageVector, onClick: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)
        .clip(RoundedCornerShape(16.dp)).background(PL.surface).border(1.dp, PL.line, RoundedCornerShape(16.dp))
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(PL.gold.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = PL.gold, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PL.ink)
                Text(sub, fontSize = 11.sp, color = PL.muted)
            }
            Icon(if (open == idx) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = PL.muted)
        }
        AnimatedVisibility(open == idx) { Column(Modifier.padding(15.dp, 0.dp, 15.dp, 14.dp), content = body) }
    }
}

@Composable private fun GhostBtn(label: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(PL.gold.copy(alpha = 0.16f), PL.gold.copy(alpha = 0.07f))))
        .border(1.dp, PL.gold.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
        .alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick).padding(12.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = PL.gold, modifier = Modifier.size(16.dp))
            Text(label, color = PL.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable private fun Para(t: String) = Text(t, fontSize = 12.5.sp, color = PL.inkSoft, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 10.dp))
@Composable private fun Feat(t: String, d: String) = Column(Modifier.padding(bottom = 9.dp)) {
    Text(t, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PL.ink)
    Text(d, fontSize = 11.5.sp, color = PL.muted, lineHeight = 16.sp)
}
/* ───────────── Prime Mode setup — real GodMode backend ───────────── */
@Composable
fun PrimeSetupScreen(
    ctx: Context,
    onBack: () -> Unit,
    onStartPairing: () -> Unit,
) {
    val state by GodModeManager.state.collectAsStateWithLifecycle()
    val status by GodModeManager.status.collectAsStateWithLifecycle()
    val snap by GodModeManager.setupSnapshot.collectAsStateWithLifecycle()
    val wirelessDebugAvailable by GodModeManager.wirelessDebugOn.collectAsStateWithLifecycle()
    val capabilities by GodModeManager.capabilities.collectAsStateWithLifecycle()
    val memory by GodModeManager.memorySnapshot.collectAsStateWithLifecycle()
    val frames by GodModeManager.frameStats.collectAsStateWithLifecycle()
    val primeLinkState by GodModeManager.primeLinkState.collectAsStateWithLifecycle()
    val actionFeedback by GodModeManager.actionFeedback.collectAsStateWithLifecycle()
    val gameplay by PrimeGameplayTracker.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { GodModeManager.refreshSetupState() }
    BackHandler(onBack = onBack)

    // Only durable evidence means paired. DISCOVERING/PAIRING/ERROR are work
    // states, not proof. The old `state != NOT_PAIRED` test hid all setup
    // controls the instant the user tapped Start pairing.
    val isPaired = snap.bootstrapped || snap.pairedTrusted
    val isActive = state == GodModeManager.State.PRIME_MODE_ACTIVE
    val pairingBusy = state == GodModeManager.State.PAIRING || state == GodModeManager.State.DISCOVERING
    val activationBusy = state == GodModeManager.State.CONNECTING || state == GodModeManager.State.BOOTSTRAPPING
    val isBusy = pairingBusy || activationBusy
    var master by remember { mutableStateOf(GodModeManager.isApexMasterEnabled()) }
    var instantVault by remember { mutableStateOf(GodModeManager.isInstantVaultEnabled()) }
    var memoryAggression by remember { mutableStateOf(GodModeManager.getMemoryAggression()) }
    var graphicsMode by remember { mutableStateOf(GodModeManager.getGraphicsMode()) }
    var renderScale by remember { mutableStateOf(GodModeManager.getRenderScale()) }
    var targetFps by remember { mutableIntStateOf(GodModeManager.getTargetFps()) }
    var artMode by remember { mutableStateOf(GodModeManager.getArtMode()) }
    var cpuGameMode by remember { mutableStateOf(GodModeManager.isCpuGameModeEnabled()) }
    var autoTuner by remember { mutableStateOf(GodModeManager.isAutoTunerEnabled()) }
    LaunchedEffect(actionFeedback.changedAtMs) {
        if (actionFeedback.changedAtMs == 0L) return@LaunchedEffect
        if (actionFeedback.action == "graphics_scale" && actionFeedback.phase == PrimeActionPhase.ERROR) {
            renderScale = GodModeManager.getRenderScale()
        }
        if (actionFeedback.phase == PrimeActionPhase.SUCCESS || actionFeedback.phase == PrimeActionPhase.ERROR) {
            val stamp = actionFeedback.changedAtMs
            delay(3_800L)
            if (GodModeManager.actionFeedback.value.changedAtMs == stamp) GodModeManager.clearActionFeedback()
        }
    }
    var pairingInput by remember { mutableStateOf(TextFieldValue("")) }
    var pairingResult by remember { mutableStateOf("") }
    var pairingResultOk by remember { mutableStateOf<Boolean?>(null) }
    var showForgetConfirm by remember { mutableStateOf(false) }
    var shellInput by remember { mutableStateOf(TextFieldValue("pm disable-user --user 0 com.transsion.phonemaster")) }
    var shellOutput by remember { mutableStateOf("") }
    var shellOk by remember { mutableStateOf<Boolean?>(null) }
    var shellBusy by remember { mutableStateOf(false) }
    val shellScope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    val (heroLabel, heroColor) = when {
        isActive -> "Prime Mode Active" to PL.green
        state == GodModeManager.State.PAIRED_IDLE && snap.bootstrapped -> "Prime Mode Ready" to PL.green
        state == GodModeManager.State.PAIRED_IDLE -> "Paired — bootstrap pending" to PL.gold
        pairingBusy -> "Pairing in progress" to PL.indigo
        activationBusy -> "Starting Prime Mode" to PL.indigo
        state == GodModeManager.State.ERROR -> "Prime Mode Error" to PL.red
        else -> "Not set up" to PL.muted
    }

    Column(Modifier.fillMaxSize().background(PL.bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(PL.surface).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, null, tint = PL.ink, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Prime Mode", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = PL.ink)
        }
        Column(Modifier.padding(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // status hero
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PL.surface).border(1.dp, PL.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (isPaired) PL.gold.copy(alpha = 0.16f) else PL.elevated), contentAlignment = Alignment.Center) {
                            Icon(if (isActive) Icons.Rounded.FlashOn else if (isPaired) Icons.Rounded.CheckCircle else Icons.Rounded.Lock, null, tint = if (isPaired) PL.gold else PL.muted, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(heroLabel, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = PL.ink)
                            Text(status.ifBlank { "One-time pairing unlocks verified Prime controls" }, fontSize = 11.sp, color = PL.muted, lineHeight = 15.sp)
                        }
                        Box(Modifier.size(11.dp).clip(CircleShape).background(heroColor))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimeStatusPill(Modifier.weight(1f), "PAIRED", isPaired)
                        PrimeStatusPill(Modifier.weight(1f), "GRANT", snap.bootstrapped)
                        PrimeStatusPill(Modifier.weight(1f), "SERVER", snap.primeServerAlive)
                    }
                }
            }

            // Setup and recovery controls remain visible throughout the flow.
            // Users move between PeerLink and Developer Options several times;
            // no transient state is allowed to remove the next required action.
            PrimeSetupStep(
                "1",
                "Open Wireless Debugging",
                "Turn Wireless Debugging ON, then choose “Pair device with pairing code”. Return here with the six-digit code. Android 11 or newer is required.",
            ) {
                GhostBtn("Open Developer Options", Icons.Rounded.Settings) {
                    GodModeManager.openWirelessDebuggingSettings(ctx)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    if (wirelessDebugAvailable) "Wireless Debugging service detected on this phone."
                    else "PeerLink has not detected Android's Wireless Debugging service yet.",
                    fontSize = 10.5.sp,
                    color = if (wirelessDebugAvailable) PL.green else PL.muted,
                    lineHeight = 15.sp,
                )
            }

            PrimeSetupStep(
                "2",
                if (pairingBusy) "PeerLink is watching for the pairing port" else "Start the pairing watcher",
                "Start this before opening the pairing-code dialog. The watcher and Developer Options button stay available until setup finishes.",
            ) {
                GhostBtn(
                    when (state) {
                        GodModeManager.State.PAIRING -> "Pairing…"
                        GodModeManager.State.DISCOVERING -> "Restart pairing watcher"
                        else -> "Start pairing watcher"
                    },
                    Icons.Rounded.Notifications,
                    enabled = !activationBusy && !isActive && state != GodModeManager.State.PAIRING,
                    onClick = onStartPairing,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    when {
                        GodModeManager.isPairingPortReady -> "Pairing endpoint detected — enter the 6-digit code below."
                        pairingBusy -> "Waiting for Android's pairing endpoint. You can still enter PORT:CODE manually."
                        else -> "Not watching yet. Manual PORT:CODE remains available if automatic detection is blocked."
                    },
                    fontSize = 10.5.sp,
                    color = if (GodModeManager.isPairingPortReady) PL.green else PL.muted,
                    lineHeight = 15.sp,
                )
            }

            PrimeSetupStep(
                "3",
                "Enter the pairing code",
                "Enter six digits after automatic detection. If detection fails, enter PORT:CODE, for example 45678:123456.",
            ) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                        .background(PL.elevated)
                        .border(1.dp, if (pairingInput.text.isNotBlank()) PL.gold.copy(alpha = 0.55f) else PL.line, RoundedCornerShape(11.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    BasicTextField(
                        value = pairingInput,
                        onValueChange = { value ->
                            val filtered = value.text.filter { it.isDigit() || it == ':' }.take(12)
                            pairingInput = value.copy(text = filtered, selection = androidx.compose.ui.text.TextRange(filtered.length))
                            pairingResult = ""
                            pairingResultOk = null
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = PL.ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                        ),
                        cursorBrush = SolidColor(PL.gold),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (pairingInput.text.isEmpty()) {
                                    Text("123456 or 45678:123456", color = PL.muted, fontSize = 13.sp)
                                }
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                GhostBtn(
                    if (state == GodModeManager.State.PAIRING) "Pairing…" else "Pair this phone",
                    Icons.Rounded.Link,
                    enabled = pairingInput.text.isNotBlank() && state != GodModeManager.State.PAIRING && !activationBusy && !isActive,
                ) {
                    GodModeManager.submitPairingInput(pairingInput.text) { success, message ->
                        mainHandler.post {
                            pairingResultOk = success
                            pairingResult = message
                            if (success) pairingInput = TextFieldValue("")
                        }
                    }
                }
                if (pairingResult.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        pairingResult,
                        fontSize = 10.5.sp,
                        color = if (pairingResultOk == true) PL.green else PL.red,
                        lineHeight = 15.sp,
                    )
                }
            }

            if (isPaired) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PL.surface).border(1.dp, PL.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Column {
                        Text("Master switch", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = PL.muted, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Prime Master", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PL.ink)
                                Text("Applies the selected verified controls for your session", fontSize = 11.sp, color = PL.muted)
                            }
                            Switch(
                                checked = master,
                                onCheckedChange = { master = it; GodModeManager.setApexMasterEnabled(it) },
                                enabled = !pairingBusy && !snap.restorePending,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PL.gold),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        when {
                            isActive -> PrimeActionButton("deactivate", "Deactivate Prime Mode", Icons.Rounded.FlashOff, actionFeedback) { GodModeManager.deactivateGodMode() }
                            snap.restorePending -> PrimeActionButton(
                                "deactivate", "Retry phone-settings restore", Icons.Rounded.Restore, actionFeedback,
                                enabled = !isBusy,
                            ) { GodModeManager.deactivateGodMode() }
                            else -> PrimeActionButton(
                                actionId = "activate",
                                label = when {
                                    activationBusy -> "Connecting…"
                                    !master -> "Turn on Prime Master first"
                                    else -> "Activate Now"
                                },
                                icon = Icons.Rounded.FlashOn,
                                feedback = actionFeedback,
                                enabled = !isBusy && master,
                            ) { GodModeManager.activateGodMode() }
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "Forget pairing",
                                color = PL.muted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isBusy && !isActive && !snap.restorePending) { showForgetConfirm = true }
                                    .alpha(if (!isBusy && !isActive && !snap.restorePending) 1f else 0.45f)
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            }

            if (isPaired) {
                PrimeEngineStrip(primeLinkState, snap.primeServerAlive)

                PrimeControlCard(
                    title = "Prime Shell",
                    subtitle = "Run one privileged command. Prime engine must be alive.",
                    icon = Icons.Rounded.Terminal,
                ) {
                    BasicTextField(
                        value = shellInput,
                        onValueChange = { shellInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = PL.ink,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(PL.gold),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PL.elevated)
                                    .border(1.dp, PL.line, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            ) {
                                if (shellInput.text.isEmpty()) {
                                    Text("pm disable-user --user 0 <package>", color = PL.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    GhostBtn(
                        if (shellBusy) "Running…" else "Run command",
                        Icons.Rounded.PlayArrow,
                        enabled = !shellBusy && snap.primeServerAlive && shellInput.text.isNotBlank(),
                    ) {
                        val cmd = shellInput.text
                        shellBusy = true
                        shellScope.launch {
                            val result = withContext(Dispatchers.IO) { GodModeManager.runUserShell(cmd) }
                            shellOutput = buildString {
                                if (result.output.isNotBlank()) append(result.output.trim()).append('\n')
                                append("exit ").append(result.exitCode)
                            }
                            shellOk = result.ok
                            shellBusy = false
                        }
                    }
                    if (!snap.primeServerAlive) {
                        Spacer(Modifier.height(8.dp))
                        Text("Activate Prime first so the engine can run pm.", fontSize = 11.sp, color = PL.muted)
                    }
                    if (shellOutput.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            shellOutput,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (shellOk == true) PL.green else PL.red,
                            lineHeight = 15.sp,
                        )
                    }
                }

                Text(
                    "PRIME CONTROL CENTER · ANDROID ${capabilities.sdk}",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PL.gold,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(top = 3.dp, start = 2.dp),
                )

                PrimeControlCard(
                    title = "Prime Memory",
                    subtitle = "Keep eFootball + PeerLink alive without blindly clearing RAM",
                    icon = Icons.Rounded.Memory,
                ) {
                    if (capabilities.processFreeze) {
                        PrimeSwitchRow(
                            title = "Instant Vault",
                            desc = "Freeze eFootball immediately when you leave it — only when no live P2P match is protected.",
                            checked = instantVault,
                        ) {
                            instantVault = it
                            GodModeManager.setInstantVaultEnabled(it)
                        }
                    } else {
                        PrimeUnavailable("Instant Vault is hidden because this Android/OEM shell does not expose safe process freezing.")
                    }

                    Spacer(Modifier.height(11.dp))
                    Text("MEMORY CANNON", fontSize = 9.sp, color = PL.muted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimeChoice("Balanced", memoryAggression == PrimeMemoryAggression.BALANCED, Modifier.weight(1f)) {
                            memoryAggression = PrimeMemoryAggression.BALANCED
                            GodModeManager.setMemoryAggression(memoryAggression)
                        }
                        PrimeChoice("Aggressive", memoryAggression == PrimeMemoryAggression.AGGRESSIVE, Modifier.weight(1f)) {
                            memoryAggression = PrimeMemoryAggression.AGGRESSIVE
                            GodModeManager.setMemoryAggression(memoryAggression)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (capabilities.processCompact) {
                        val memStatus = if (memory.running) {
                            "${memory.pressure} · ${memory.availableMb} MB available · reserve ${memory.reserveMb} MB"
                        } else "Starts when Prime Mode becomes active"
                        PrimeInfoLine("Memory", memStatus)
                        PrimeInfoLine("Match shield", if (gameplay.matchActive || memory.matchProtected) "ACTIVE · ${gameplay.outboundPps}/${gameplay.inboundPps} pps" else "Waiting for sustained P2P gameplay")
                        if (memory.zramRatio > 0f) PrimeInfoLine("ZRAM", "${String.format("%.2f", memory.zramRatio)}× effective compression")
                        if (memory.gameVaulted) PrimeInfoLine("Vault", "eFootball frozen safely in background")
                        if (memory.protectedForeground.isNotBlank()) PrimeInfoLine("Also protected", memory.protectedForeground)
                        if (memory.lastAction != "None") PrimeInfoLine("Last action", memory.lastAction)
                    } else {
                        PrimeUnavailable("Adaptive compaction is unavailable here; Prime still keeps its normal game/Doze priority controls.")
                    }
                }

                PrimeControlCard(
                    title = "Prime Graphics",
                    subtitle = "User-controlled render load. Auto recommends; you can override it.",
                    icon = Icons.Rounded.Speed,
                ) {
                    if (capabilities.gameDownscale) {
                        PrimeInfoLine(
                            "Verified backend",
                            capabilities.graphicsBackend.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                        )
                        Text("CONTROL", fontSize = 9.sp, color = PL.muted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimeChoice("Auto", graphicsMode == PrimeGraphicsMode.AUTO, Modifier.weight(1f)) {
                                graphicsMode = PrimeGraphicsMode.AUTO
                                GodModeManager.setGraphicsMode(graphicsMode)
                            }
                            PrimeChoice("Manual", graphicsMode == PrimeGraphicsMode.MANUAL, Modifier.weight(1f)) {
                                graphicsMode = PrimeGraphicsMode.MANUAL
                                GodModeManager.setGraphicsMode(graphicsMode)
                            }
                        }
                        Spacer(Modifier.height(11.dp))
                        Text("EFOOTBALL FPS TARGET", fontSize = 9.sp, color = PL.muted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimeChoice("30 FPS", targetFps == 30, Modifier.weight(1f)) {
                                targetFps = 30; GodModeManager.setTargetFps(30)
                            }
                            PrimeChoice("60 FPS", targetFps == 60, Modifier.weight(1f)) {
                                targetFps = 60; GodModeManager.setTargetFps(60)
                            }
                        }
                        Text("Match this to the FPS you selected inside eFootball.", fontSize = 10.sp, color = PL.muted, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(11.dp))
                        Text("RENDER SCALE", fontSize = 9.sp, color = PL.muted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(7.dp))
                        val scales = listOf("1.00" to "Native", "0.90" to "90%", "0.85" to "85%", "0.80" to "80%", "0.75" to "75%", "0.70" to "70%")
                        PrimePicker(
                            selectedKey = renderScale,
                            options = scales,
                            enabled = !(actionFeedback.action == "graphics_scale" && actionFeedback.phase == PrimeActionPhase.WORKING),
                        ) { scale ->
                            renderScale = scale
                            GodModeManager.setRenderScale(scale)
                        }
                        PrimeInlineFeedback("graphics_scale", actionFeedback)
                        Text(
                            if (graphicsMode == PrimeGraphicsMode.AUTO) "Auto starts from your chosen scale and only steps lower after repeated GPU-side jank."
                            else "Manual means Prime never changes your render scale automatically.",
                            fontSize = 10.sp, color = PL.inkSoft, lineHeight = 14.sp,
                        )
                        Text("Render-scale changes take effect after restarting eFootball.", fontSize = 10.sp, color = PL.gold, modifier = Modifier.padding(top = 5.dp))
                        if (frames.totalFrames > 0) {
                            Spacer(Modifier.height(9.dp))
                            PrimeInfoLine("Measured", "${String.format("%.1f", frames.averageFps)} FPS · ${frames.jankyFrames}/${frames.totalFrames} janky frames")
                            PrimeInfoLine("Auto Tuner", frames.note)
                        }
                    } else {
                        val pendingGraphics = capabilities.graphicsBackend == PrimeGraphicsBackend.PENDING
                        PrimeUnavailable(
                            if (pendingGraphics) {
                                "Graphics compatibility has not been verified yet. Prime will test safe Android backends outside live gameplay."
                            } else {
                                "No backbuffer route has passed readback verification on this phone. ${capabilities.graphicsProbeDetail}"
                            }
                        )
                        if (snap.primeServerAlive && !gameplay.matchActive) {
                            Spacer(Modifier.height(8.dp))
                            PrimeActionButton(
                                actionId = "capability_recheck",
                                label = "Recheck graphics compatibility",
                                icon = Icons.Rounded.Autorenew,
                                feedback = actionFeedback,
                                enabled = !activationBusy,
                            ) { GodModeManager.recheckPerformanceCapabilities() }
                        }
                    }
                }

                PrimeControlCard(
                    title = "Prime CPU",
                    subtitle = "Only CPU/runtime controls this phone actually exposes",
                    icon = Icons.Rounded.DeveloperBoard,
                ) {
                    if (capabilities.gamePerformanceMode) {
                        PrimeSwitchRow(
                            title = "Performance Game Mode",
                            desc = "Use the OEM's eFootball performance profile on the next Prime activation.",
                            checked = cpuGameMode,
                        ) { cpuGameMode = it; GodModeManager.setCpuGameModeEnabled(it) }
                    } else {
                        PrimeUnavailable("This phone does not report a Performance Game Mode for eFootball.")
                    }

                    if (capabilities.artCompile) {
                        Spacer(Modifier.height(11.dp))
                        Text("ART RUNTIME", fontSize = 9.sp, color = PL.muted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(7.dp))
                        PrimePicker(
                            selectedKey = artMode.name,
                            options = listOf("DEFAULT" to "Default", "PROFILE" to "Profile", "FULL" to "Full"),
                        ) { selected ->
                            artMode = PrimeArtMode.valueOf(selected)
                            GodModeManager.setArtMode(artMode)
                        }
                        Text("Profile is the safe default. Full AOT compiles more DEX code but uses more storage/time.", fontSize = 10.sp, color = PL.muted, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        PrimeActionButton(
                            actionId = "art_optimize",
                            label = "Optimize eFootball runtime now",
                            icon = Icons.Rounded.Bolt,
                            feedback = actionFeedback,
                            enabled = snap.primeServerAlive && artMode != PrimeArtMode.DEFAULT && !activationBusy,
                        ) { GodModeManager.runArtOptimizationNow() }
                    }
                }

                PrimeControlCard(
                    title = "Prime Auto Tuner",
                    subtitle = "Measures the phone instead of assuming every device behaves the same",
                    icon = Icons.Rounded.AutoAwesome,
                ) {
                    PrimeSwitchRow(
                        title = "Adaptive tuning",
                        desc = "Uses real eFootball frame data and the selected FPS target. Manual Graphics mode is always respected.",
                        checked = autoTuner,
                    ) { autoTuner = it; GodModeManager.setAutoTunerEnabled(it) }
                    Spacer(Modifier.height(9.dp))
                    PrimeInfoLine("Rule #1", "PeerLink packet path stays protected")
                    PrimeInfoLine("Rule #2", "Meet 30/60 FPS smoothly before reducing quality")
                    PrimeInfoLine("Rule #3", "Unsupported Android/OEM controls stay hidden")
                }
            }

            // what it unlocks
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PL.surface).border(1.dp, PL.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Column {
                    Text("What Prime Mode unlocks", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = PL.muted, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Feat("Shield", "Mis-tap airplane mode mid-match? Only data drops — your link survives.")
                    Feat("Prime Memory", "Instant Vault outside matches; Return Shield during multiplayer. Android cannot truly pin another app in RAM, so Prime protects it intelligently instead.")
                    Feat("No Calls", "Silently rejects calls only while you're playing.")
                    Feat("Prime Auto Tuner", "Measures real frame consistency and adapts only supported controls; manual settings always remain available.")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text("Forget Prime pairing?", color = PL.ink, fontWeight = FontWeight.Bold) },
            text = { Text("You will need Wireless Debugging and a new pairing code before Prime Mode can run again.", color = PL.inkSoft) },
            confirmButton = {
                TextButton({
                    showForgetConfirm = false
                    GodModeManager.forgetPairing()
                }) { Text("Forget", color = PL.red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton({ showForgetConfirm = false }) { Text("Cancel", color = PL.muted) }
            },
            containerColor = PL.surface,
        )
    }
}

@Composable
private fun PrimeControlCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PL.surface)
            .border(1.dp, PL.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(PL.elevated), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = PL.inkSoft, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = PL.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = PL.muted, fontSize = 10.5.sp, lineHeight = 14.5.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PrimeSwitchRow(title: String, desc: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PL.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = PL.muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PL.gold),
        )
    }
}

@Composable
private fun PrimeChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "primeChoicePress")
    val bg by animateColorAsState(if (selected) PL.gold.copy(alpha = 0.13f) else PL.elevated, label = "primeChoiceBg")
    val stroke by animateColorAsState(if (selected) PL.gold.copy(alpha = 0.65f) else PL.line, label = "primeChoiceStroke")
    Box(
        modifier
            .height(38.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(9.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = PL.goldSoft, modifier = Modifier.size(13.dp))
            Text(label, color = if (selected) PL.goldSoft else PL.inkSoft, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PrimeInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = PL.muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(92.dp))
        Text(value, color = PL.inkSoft, fontSize = 9.5.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PrimeUnavailable(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PL.elevated).padding(11.dp)) {
        Text(text, color = PL.muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun PrimeEngineStrip(link: PrimeLinkState, serverAlive: Boolean) {
    val (title, detail, tint) = when (link) {
        PrimeLinkState.CONNECTED -> Triple("Engine online", "Privileged controls are available", PL.green)
        PrimeLinkState.RECOVERING -> Triple("Recovering engine", "PeerLink is reconnecting automatically", PL.gold)
        PrimeLinkState.DEGRADED -> Triple("Engine interrupted", "Prime remains protected; automatic retry is pending", PL.red)
        PrimeLinkState.IDLE -> if (serverAlive) Triple("Engine ready", "Prime server is standing by", PL.green)
            else Triple("Engine idle", "Starts when Prime needs privileged controls", PL.muted)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PL.elevated)
            .border(1.dp, PL.line, RoundedCornerShape(12.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (link == PrimeLinkState.RECOVERING) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.8.dp, color = tint)
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = PL.ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = PL.muted, fontSize = 9.5.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun PrimePicker(
    selectedKey: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: selectedKey
    Box {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(PL.elevated)
                .border(1.dp, if (expanded) PL.gold.copy(alpha = 0.55f) else PL.line, RoundedCornerShape(10.dp))
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedLabel, color = PL.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = PL.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = PL.surface,
        ) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = PL.ink, fontSize = 12.sp) },
                    leadingIcon = if (key == selectedKey) ({ Icon(Icons.Rounded.Check, null, tint = PL.gold, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = {
                        expanded = false
                        if (key != selectedKey) onSelect(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun PrimeInlineFeedback(actionId: String, feedback: PrimeActionFeedback) {
    if (feedback.action != actionId || feedback.phase == PrimeActionPhase.IDLE) return
    val tint = when (feedback.phase) {
        PrimeActionPhase.SUCCESS -> PL.green
        PrimeActionPhase.ERROR -> PL.red
        PrimeActionPhase.WORKING -> PL.gold
        else -> PL.muted
    }
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (feedback.phase == PrimeActionPhase.WORKING) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tint)
        } else {
            Icon(
                if (feedback.phase == PrimeActionPhase.SUCCESS) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null, tint = tint, modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(feedback.message, color = tint, fontSize = 9.5.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun PrimeActionButton(
    actionId: String,
    label: String,
    icon: ImageVector,
    feedback: PrimeActionFeedback,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val thisAction = feedback.action == actionId
    val working = thisAction && feedback.phase == PrimeActionPhase.WORKING
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.985f else 1f, label = "primeActionPress")
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .clip(RoundedCornerShape(10.dp)).background(if (working) PL.gold.copy(alpha = 0.12f) else PL.elevated)
                .border(1.dp, if (working) PL.gold.copy(alpha = 0.55f) else PL.line, RoundedCornerShape(10.dp))
                .alpha(if (enabled && !working) 1f else if (working) 0.92f else 0.45f)
                .clickable(enabled = enabled && !working, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (working) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 1.8.dp, color = PL.gold)
            else Icon(icon, null, tint = PL.inkSoft, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text(if (working && feedback.message.isNotBlank()) feedback.message else label, color = PL.ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (thisAction && feedback.phase == PrimeActionPhase.SUCCESS) Icon(Icons.Rounded.CheckCircle, null, tint = PL.green, modifier = Modifier.size(17.dp))
            if (thisAction && feedback.phase == PrimeActionPhase.ERROR) Icon(Icons.Rounded.ErrorOutline, null, tint = PL.red, modifier = Modifier.size(17.dp))
        }
        if (thisAction && (feedback.phase == PrimeActionPhase.SUCCESS || feedback.phase == PrimeActionPhase.ERROR)) {
            Text(
                feedback.message,
                color = if (feedback.phase == PrimeActionPhase.SUCCESS) PL.green else PL.red,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                modifier = Modifier.padding(top = 5.dp, start = 2.dp),
            )
        }
    }
}

@Composable private fun PrimeStatusPill(modifier: Modifier, label: String, on: Boolean) =
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(if (on) PL.green.copy(alpha = 0.14f) else PL.elevated).border(1.dp, if (on) PL.green.copy(alpha = 0.4f) else PL.line, RoundedCornerShape(10.dp)).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) PL.green else PL.muted))
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = if (on) PL.green else PL.muted, letterSpacing = 1.sp)
        }
    }

@Composable private fun PrimeSetupStep(num: String, title: String, desc: String, action: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PL.surface)
            .border(1.dp, PL.line, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(PL.gold.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text(num, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = PL.gold)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = PL.ink)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                desc,
                fontSize = 11.5.sp,
                color = PL.muted,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 36.dp, bottom = 10.dp),
            )
            // IMPORTANT: setup actions contain multiple children (button, status,
            // input, etc.). A Box stacks children at the same origin, which caused
            // the overlap visible on real phones. A Column gives each child its own
            // measured vertical space and remains safe with larger font/display scales.
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 36.dp),
                content = action,
            )
        }
    }
}
