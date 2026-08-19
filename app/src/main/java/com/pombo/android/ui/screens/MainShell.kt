package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.NetStatus
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors

private enum class Tab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.Outlined.ChatBubbleOutline),
    EXPLORE("Explore", Icons.Outlined.Explore),
    CONTACTS("Contacts", Icons.Outlined.People),
    SETTINGS("Settings", Icons.Outlined.Settings),
    PROFILE("Profile", Icons.Outlined.Person)
}

/**
 * Main app shell (web mobile design): per-tab content area +
 * floating bottom pill nav with a glass effect.
 */
@Composable
fun MainShell(vm: AppViewModel) {
    // A guest has no channels, so discovery is the useful landing tab; a
    // signed-in account lands on its conversations.
    val guestNow by vm.isGuest.collectAsState()
    val hasWalletNow by vm.hasWallet.collectAsState()
    val chatOrigin by vm.chatOrigin.collectAsState()

    // Closing a chat re-creates this whole composable, so the starting tab is
    // derived from where the chat was opened from — that state lives in the
    // ViewModel precisely because everything here is thrown away in between.
    var tab by remember {
        mutableStateOf(
            if (chatOrigin == com.pombo.android.AppViewModel.ChatOrigin.EXPLORE) Tab.EXPLORE else Tab.CHATS
        )
    }
    LaunchedEffect(guestNow, hasWalletNow) {
        // The guest session starts asynchronously, so at first frame neither
        // flag is set yet — wait until the identity kind is actually known
        // before choosing the landing tab, otherwise it always lands on Chats.
        if (!vm.landedOnce && (guestNow || hasWalletNow)) {
            tab = if (guestNow) Tab.EXPLORE else Tab.CHATS
            vm.landedOnce = true
        }
    }
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    val isGuest by vm.isGuest.collectAsState()

    // Settings has many panels: the pill's Settings button opens a dropdown to
    // jump straight to one, and panels are swipeable left/right from there.
    val settingsPager = androidx.compose.foundation.pager.rememberPagerState(
        pageCount = { SettingsPanel.entries.size }
    )
    var settingsMenu by remember { mutableStateOf(false) }
    /** Centre x of the Settings pill item, reported by PillNav. */
    var settingsAnchorX by remember { mutableFloatStateOf(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Back gesture = navigate back, not leave the app. Declared innermost-last,
    // because the LAST enabled handler is the one that runs: the open dropdown
    // wins over the tab, and only on the landing tab does back fall through to
    // the system and actually minimise Pombo.
    val homeTab = if (guestNow) Tab.EXPLORE else Tab.CHATS
    androidx.activity.compose.BackHandler(enabled = tab != homeTab) { tab = homeTab }
    androidx.activity.compose.BackHandler(enabled = settingsMenu) { settingsMenu = false }

    var showImportKey by remember { mutableStateOf(false) }
    // Web walletFlows: "Create New Account" opens the avatar/name wizard, not
    // an instant wallet.
    var showAccountSetup by remember { mutableStateOf(false) }
    // Restore-from-backup, reachable before any account exists (guest mode) —
    // the same picker + password flow Settings uses.
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreUri = uri }
    if (showConnect) ConnectAccountDialog(
        onCreate = { showConnect = false; showAccountSetup = true },
        // Web: import opens its own modal, it does not leave the current view.
        onImport = { showConnect = false; showImportKey = true },
        onRestore = { showConnect = false; restoreLauncher.launch(arrayOf("application/json", "*/*")) },
        onDismiss = { showConnect = false }
    )
    restoreUri?.let { uri ->
        BackupPasswordDialog(
            title = "Restore Account Backup",
            hint = "Enter the password this backup was created with.",
            confirmLabel = "Restore",
            onDismiss = { restoreUri = null }
        ) { pwd ->
            restoreUri = null
            vm.importBackupFrom(uri, pwd)
        }
    }
    if (showAccountSetup) CreateAccountWizard(vm, onDismiss = { showAccountSetup = false })
    if (showImportKey) ImportKeyDialog(
        onDismiss = { showImportKey = false },
        onImport = { key -> showImportKey = false; vm.importWallet(key) }
    )

    Box(Modifier.fillMaxSize()) {
        // Tab content, clearing the floating pill. This must track the pill's
        // own inset + offset: raising the pill to the CSS `bottom: 2rem` above
        // the navigation bar without widening this reserve left it overlapping
        // the "New DM" button.
        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                // Explore is the one tab whose list runs to the bottom edge and
                // scrolls UNDER the floating pill, as on the web; its LazyColumn
                // adds the clearance as contentPadding instead, so the last card
                // can still be scrolled clear of the glass. Reserving it out here
                // for every tab left a dead black band under the pill.
                .padding(bottom = if (tab == Tab.EXPLORE) 0.dp else 32.dp + PILL_NAV_HEIGHT)
        ) {
            when (tab) {
                Tab.CHATS -> ChatsTab(vm, onCreate = { showCreate = true }, onJoin = { showJoin = true }, onConnect = { showConnect = true })
                Tab.EXPLORE -> ExploreTab(vm, onCreate = { showCreate = true }, onConnect = { showConnect = true })
                Tab.CONTACTS -> ContactsTab(vm)
                Tab.SETTINGS -> SettingsTab(vm, settingsPager)
                Tab.PROFILE -> ProfileTab(vm, onAddAccount = { showConnect = true })
            }
        }

        // Tap-anywhere-else closes the settings dropdown. Declared before the
        // pill Column, so it is drawn under both the menu and the pill and
        // catches only the taps that miss them.
        if (settingsMenu) {
            Box(Modifier.fillMaxSize().clickableNoRipple { settingsMenu = false })
        }

        // Floating pill nav + guest label underneath (web: .pill-guest-label).
        // CSS `#mobile-pill-nav { bottom: 2rem; padding-bottom:
        // env(safe-area-inset-bottom) }` — 32px above the safe area. The old
        // flat 14dp sat too low and ignored the inset entirely, so on
        // gesture-nav devices the pill crowded the system gesture bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (settingsMenu) {
                // Centred on the Settings icon (web anchors it to that pill
                // item), not on the pill as a whole — but clamped so a menu
                // wider than the space left of the icon cannot run off screen.
                val density = androidx.compose.ui.platform.LocalDensity.current
                val screenW = with(density) {
                    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
                }
                var menuW by remember { mutableFloatStateOf(0f) }
                val margin = with(density) { 8.dp.toPx() }
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                val x = (settingsAnchorX - menuW / 2f)
                                    .coerceIn(margin, (screenW - menuW - margin).coerceAtLeast(margin))
                                androidx.compose.ui.unit.IntOffset(x.toInt(), 0)
                            }
                            .onSizeChanged { menuW = it.width.toFloat() }
                    ) {
                        SettingsDropdown(
                            current = SettingsPanel.entries[settingsPager.currentPage],
                            // Guests have no inbox to sync to, so the web hides this.
                            syncRow = if (!isGuest) ({ SyncDevicesRow(vm) }) else null
                        ) { panel ->
                            settingsMenu = false
                            tab = Tab.SETTINGS
                            scope.launch { settingsPager.animateScrollToPage(panel.ordinal) }
                        }
                    }
                }
            }
            PillNav(
                current = tab,
                address = vm.address.collectAsState().value,
                ensAvatarUrl = vm.ensAvatars.collectAsState().value[vm.address.collectAsState().value?.lowercase()],
                onSettingsAnchor = { settingsAnchorX = it },
                onSelect = { picked ->
                    if (picked == Tab.SETTINGS) {
                        // Settings only offers its panel list — navigation happens
                        // when a panel is picked, not on the pill tap itself.
                        settingsMenu = !settingsMenu
                    } else {
                        settingsMenu = false
                        tab = picked
                        // Remember where the user is, so a chat opened next
                        // returns to this tab.
                        vm.setChatOrigin(
                            if (picked == Tab.EXPLORE) com.pombo.android.AppViewModel.ChatOrigin.EXPLORE
                            else com.pombo.android.AppViewModel.ChatOrigin.CHATS
                        )
                    }
                }
            )
            if (isGuest) {
                Spacer(Modifier.height(6.dp))
                Text("Guest mode — data not saved", color = PomboColors.Accent, fontSize = 11.sp)
            }
        }

        // Full-screen panel drawn INSIDE this window (last child = on top), not
        // as an androidx Dialog — see the note in CreateChannelDialog.
        if (showCreate) CreateChannelDialog(vm, onDismiss = { showCreate = false }) { spec ->
            vm.createChannel(spec); showCreate = false
        }
    }

    if (showJoin) JoinChannelDialog(onDismiss = { showJoin = false }) { id, pwd ->
        vm.joinChannel(id, pwd); showJoin = false
    }
}

@Composable
private fun PillNav(
    current: Tab,
    address: String?,
    /** ENS picture for my own address, when the account has one. */
    ensAvatarUrl: String? = null,
    onSelect: (Tab) -> Unit,
    /** Centre x of the Settings item, in root coordinates. */
    onSettingsAnchor: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // .pill-nav-inner: rgba(255,255,255,0.06) fill, 0.08 border, radius 2rem,
    // and crucially `backdrop-filter: blur(20px)`. Compose cannot blur an
    // in-layout surface, and over a true-black background a bare 6% white fill
    // is nearly invisible — the pill read as a faint outline instead of a
    // frosted slab. The denser base stands in for the blur.
    val pillShape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .background(Color(0xFF0E0E10).copy(alpha = 0.88f), pillShape)
            .background(Color.White.copy(alpha = 0.06f), pillShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), pillShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tab.entries.forEach { t ->
            if (t == Tab.PROFILE) {
                val active = current == t
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(38.dp)
                        .background(Color.White.copy(alpha = if (active) 0.14f else 0.08f), CircleShape)
                        .border(1.5.dp, if (active) PomboColors.Accent else Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickableNoRipple { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) {
                    val addr = address
        if (addr != null) Avatar(
                        address, size = 34.dp, cornerRadiusFraction = 0.5,
                        ensAvatarUrl = ensAvatarUrl
                    )
                    else Icon(t.icon, contentDescription = t.label, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }
            } else {
                PillItem(
                    t.label, t.icon, active = current == t,
                    // The settings dropdown is centred on THIS icon, so its
                    // centre has to travel out of the pill's own layout.
                    modifier = if (t == Tab.SETTINGS) {
                        Modifier.onGloballyPositioned { onSettingsAnchor(it.boundsInRoot().center.x) }
                    } else Modifier
                ) { onSelect(t) }
            }
        }
    }
}

@Composable
private fun PillItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clickableNoRipple(onClick)
            .background(if (active) PomboColors.Accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Selection is carried by the orange trough alone: icon and label keep
        // the idle grey in both states, so the active tab reads as highlighted
        // rather than recoloured.
        Icon(
            icon, contentDescription = label,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PomboHeader(status: NetStatus, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .background(PomboColors.Background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.pombo.android.R.drawable.pombo_logo),
            contentDescription = "Pombo",
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Pombo", color = PomboColors.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/**
 * Settings-only header: the current panel's name sits centred and marked
 * orange with an underline — the treatment the web gives its active filter
 * tab (.channel-filter-tab: uppercase, tracking-widest, #F6851B + 2px
 * border). The other tabs keep the brand header above.
 */
@Composable
private fun SettingsHeader(title: String) {
    Box(
        Modifier.fillMaxWidth()
            .background(PomboColors.Background)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 36.dp)
    ) {
        Column(
            Modifier.align(Alignment.Center).width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title.uppercase(),
                color = PomboColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.em
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(PomboColors.Accent, RoundedCornerShape(1.dp))
            )
        }
    }
}

private enum class ChannelFilter(val label: String) { ALL("All"), PERSONAL("Personal"), COMMUNITIES("Communities") }

/**
 * Folds a reordered slice back into the full channel order.
 *
 * A filtered tab shows only some of the channels, so a drag there says how
 * those rows sit relative to EACH OTHER — nothing about the ones hidden by the
 * filter. Keeping the global positions the slice already occupies and refilling
 * them in the new sequence is what makes that true: the dragged channel really
 * moves in the one saved order (so every other view sees it), while channels
 * the filter hid never shift under the user.
 *
 * In the All tab the slice IS the whole list, so this is the identity it used
 * to be.
 */
private fun mergeOrder(global: List<String>, slice: List<String>): List<String> {
    val inSlice = slice.toHashSet()
    val slots = global.withIndex().filter { it.value in inSlice }.map { it.index }
    if (slots.size != slice.size) return slice + global.filterNot { it in inSlice }
    val out = global.toMutableList()
    slots.forEachIndexed { i, pos -> out[pos] = slice[i] }
    return out
}

@Composable
private fun ChatsTab(vm: AppViewModel, onCreate: () -> Unit, onJoin: () -> Unit, onConnect: () -> Unit = {}) {
    val status by vm.status.collectAsState()
    val channels by vm.channels.collectAsState()
    val busy by vm.busy.collectAsState()
    val isGuest by vm.isGuest.collectAsState()
    val channelImages by vm.channelImages.collectAsState()
    val channelPreviews by vm.channelPreviews.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    // Preview sender labels resolve ENS at RENDER — the name usually arrives after
    // the preview was cached, so baking it in at fetch time is not enough.
    val ensNames by vm.ensNames.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val channelOrder by vm.channelOrder.collectAsState()
    var filter by remember { mutableStateOf(ChannelFilter.ALL) }

    // Landing on Chats is the moment the list is about to be read, so it is
    // when the background channels are worth a look. Fires on every entry into
    // the tab; the per-channel floor inside keeps that from hammering.
    LaunchedEffect(Unit) { vm.scanChannelsActivity() }

    // Apply the user's manual drag order: known ids first in their saved order,
    // everything else after in natural order (stable sort).
    val ordered = remember(channels, channelOrder) {
        channels.sortedBy { ch ->
            channelOrder.indexOf(ch.messageStreamId).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }
    val filtered = when (filter) {
        ChannelFilter.ALL -> ordered
        ChannelFilter.PERSONAL -> ordered.filter { it.type == "dm" || it.type == "native" || it.type == "gated" }
        ChannelFilter.COMMUNITIES -> ordered.filter { it.type == "public" || it.type == "password" }
    }

    Column(Modifier.fillMaxSize()) {
        PomboHeader(status) {
            if (isGuest) {
                // Web: guest shows the orange "Create Account" button instead of the actions
                Row(
                    Modifier.background(PomboColors.Accent, RoundedCornerShape(12.dp))
                        .clickableNoRipple(onConnect)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Create Account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bell: recent channel invites. Android addition — on the
                    // web an invite only ever exists as a transient toast; here
                    // unanswered ones stay reachable until accepted/dismissed.
                    val invites by vm.pendingInvites.collectAsState()
                    var invitesOpen by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            Modifier.size(32.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                .clickableNoRipple { invitesOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Notifications, contentDescription = "Channel invites",
                                tint = PomboColors.Text, modifier = Modifier.size(17.dp)
                            )
                        }
                        if (invites.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .size(14.dp)
                                    .background(PomboColors.Accent, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (invites.size > 9) "9+" else invites.size.toString(),
                                    color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = invitesOpen,
                            onDismissRequest = { invitesOpen = false },
                            modifier = Modifier
                                .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                .widthIn(min = 230.dp, max = 300.dp)
                        ) {
                            Text(
                                "CHANNEL INVITES",
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.1.em,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                            if (invites.isEmpty()) {
                                Text(
                                    "No pending invites",
                                    color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                        .padding(bottom = 12.dp)
                                )
                            } else invites.asReversed().forEach { invite ->
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                    Text(
                                        invite.name, color = PomboColors.Text,
                                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "From: ${invite.from.take(6)}…${invite.from.takeLast(4)}",
                                        color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            Modifier.background(Color.White.copy(alpha = 0.90f), RoundedCornerShape(6.dp))
                                                .clickableNoRipple { invitesOpen = false; vm.acceptInvite(invite) }
                                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Accept", color = Color(0xFF0A0A0A), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Box(
                                            Modifier.background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                                                .clickableNoRipple { vm.dismissInvite(invite.inviteId) }
                                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Dismiss", color = Color.White.copy(alpha = 0.70f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }

                            // ---- Active transfers: every download in flight and
                            // every file this device is serving, with a way out.
                            val transferProgress by vm.fileProgress.collectAsState()
                            val transferUploads by vm.uploadStats.collectAsState()
                            val activeSeeds by vm.activeSeeds.collectAsState()
                            val storageUploads by vm.storageUploads.collectAsState()
                            val storageDownloads by vm.storageDownloads.collectAsState()
                            val activeDownloads = transferProgress.values
                                .filter { !it.done && it.failure == null }
                            val activeStorageUp = storageUploads.values.filter { it.stage != "done" && it.error == null }
                            val activeStorageDown = storageDownloads.values.filter { it.status == "downloading" }
                            androidx.compose.material3.HorizontalDivider(
                                color = Color.White.copy(alpha = 0.08f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Text(
                                "ACTIVE TRANSFERS",
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.1.em,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                            if (activeDownloads.isEmpty() && activeSeeds.isEmpty() &&
                                activeStorageUp.isEmpty() && activeStorageDown.isEmpty()
                            ) {
                                Text(
                                    "No active transfers",
                                    color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                        .padding(bottom = 12.dp)
                                )
                            } else {
                                activeDownloads.forEach { p ->
                                    val speed = com.pombo.android.ui.formatSpeed(p.bytesPerSecond)
                                    TransferRow(
                                        icon = Icons.Filled.ArrowDownward, iconTint = PomboColors.Accent,
                                        badge = "mesh", name = p.fileName,
                                        sub = com.pombo.android.ui.formatBytes(p.received) + " of " +
                                            com.pombo.android.ui.formatBytes(p.total) + if (speed.isEmpty()) "" else " · $speed",
                                        onCancel = { vm.cancelTransfer(p.fileId) }
                                    )
                                }
                                activeSeeds.forEach { seed ->
                                    val up = transferUploads[seed.fileId]
                                    val rate = com.pombo.android.ui.formatSpeed(up?.bytesPerSecond)
                                    TransferRow(
                                        icon = Icons.Filled.ArrowUpward, iconTint = Color(0xFF4ADE80).copy(alpha = 0.7f),
                                        badge = "mesh", name = seed.fileName.ifEmpty { seed.fileId.take(8) },
                                        sub = com.pombo.android.ui.formatBytes(seed.fileSize) +
                                            (if (rate.isEmpty()) "" else " · ↑ $rate") +
                                            (if (up != null && up.leechers > 0) " · ${up.leechers} peer${if (up.leechers == 1) "" else "s"}" else ""),
                                        onCancel = { vm.stopSeeding(seed.fileId) }
                                    )
                                }
                                // Storage-node transfers — survive a channel switch, so they carry
                                // their own channel name (the open channel may be a different one).
                                activeStorageUp.forEach { u ->
                                    val info = vm.storageTransferInfo(u.transferId)
                                    val stats = if (u.stage == "sending") {
                                        val sp = com.pombo.android.ui.formatSpeed(u.instBps ?: u.avgBps)
                                        "${u.percent}%" + if (sp.isEmpty()) "" else " · $sp"
                                    } else u.phase
                                    TransferRow(
                                        icon = Icons.Filled.ArrowUpward, iconTint = Color(0xFF8B5CF6),
                                        badge = "storage", name = info?.fileName ?: u.transferId.take(8),
                                        sub = (info?.channelName?.let { "$it · " } ?: "") + stats,
                                        onCancel = null
                                    )
                                }
                                activeStorageDown.forEach { d ->
                                    val info = vm.storageTransferInfo(d.transferId)
                                    val transferred = if (d.total > 0)
                                        com.pombo.android.ui.formatBytes(d.received.toLong() * d.fileSize / d.total) else "0 B"
                                    val sp = com.pombo.android.ui.formatSpeed(d.bytesPerSec)
                                    val stats = d.phase ?: ("${d.percent}% · $transferred" + if (sp.isEmpty()) "" else " · $sp")
                                    TransferRow(
                                        icon = Icons.Filled.ArrowDownward, iconTint = Color(0xFF8B5CF6),
                                        badge = "storage", name = info?.fileName ?: d.transferId.take(8),
                                        sub = (info?.channelName?.let { "$it · " } ?: "") + stats,
                                        onCancel = null
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Join by stream ID. Creating a channel lives in Explore.
                    Box(
                        Modifier.size(32.dp).background(Color.White.copy(alpha = 0.08f), CircleShape).clickableNoRipple(onJoin),
                        contentAlignment = Alignment.Center
                    ) { Text("#", color = PomboColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Text(
            "CHANNELS",
            color = Color.White.copy(alpha = 0.30f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.15.em,
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
        )

        // Filter tabs (All / Personal / Communities) — active gets an orange underline
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            androidx.compose.material3.HorizontalDivider(
                color = Color.White.copy(alpha = 0.06f),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChannelFilter.entries.forEach { f ->
                    val active = f == filter
                    Column(
                        Modifier.weight(1f).clickableNoRipple { filter = f },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            f.label.uppercase(),
                            color = if (active) PomboColors.Accent else Color.White.copy(alpha = 0.30f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.15.em,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                        Box(
                            Modifier.fillMaxWidth().height(2.dp)
                                .background(if (active) PomboColors.Accent else Color.Transparent)
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (busy) "…" else "No channels yet",
                    color = PomboColors.TextDim, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            // Swipe left/right anywhere on the list to move between the filter
            // tabs, like the web (initChannelFilterSwipe: >50px horizontal).
            var swipeAccum by remember { mutableFloatStateOf(0f) }
            val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Drag-to-reorder (hold and drag) in EVERY tab, like the web: there
            // is one saved order (secureStorage.channelOrder) and each view is a
            // window onto it, so a drag in Personal moves the channel for All
            // and Communities too. See [mergeOrder] for how a reordered slice
            // folds back into the full list.
            var draggingKey by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var localOrder by remember { mutableStateOf(filtered) }
            LaunchedEffect(filtered) { if (draggingKey == null) localOrder = filtered }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(filter) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val order = ChannelFilter.entries
                                val i = order.indexOf(filter)
                                if (swipeAccum < -swipeThreshold && i < order.lastIndex) filter = order[i + 1]
                                else if (swipeAccum > swipeThreshold && i > 0) filter = order[i - 1]
                                swipeAccum = 0f
                            },
                            onDragCancel = { swipeAccum = 0f },
                            onHorizontalDrag = { _, delta -> swipeAccum += delta }
                        )
                    },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(localOrder, key = { _, ch -> ch.messageStreamId }) { _, ch ->
                    val isDragging = ch.messageStreamId == draggingKey
                    LaunchedEffect(ch.messageStreamId) { vm.ensureChannelPreview(ch) }
                    Box(
                        Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .then(
                                Modifier.pointerInput(ch.messageStreamId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingKey = ch.messageStreamId; dragOffset = 0f },
                                        onDragEnd = {
                                            vm.setChannelOrder(
                                                mergeOrder(
                                                    ordered.map { it.messageStreamId },
                                                    localOrder.map { it.messageStreamId }
                                                )
                                            )
                                            draggingKey = null; dragOffset = 0f
                                        },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val cur = localOrder.indexOfFirst { it.messageStreamId == draggingKey }
                                            if (cur < 0) return@detectDragGesturesAfterLongPress
                                            val row = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == draggingKey } ?: return@detectDragGesturesAfterLongPress
                                            val step = row.size + 2.dp.toPx()   // row height + spacing
                                            if (dragOffset > step / 2 && cur < localOrder.lastIndex) {
                                                localOrder = localOrder.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                                dragOffset -= step
                                            } else if (dragOffset < -step / 2 && cur > 0) {
                                                localOrder = localOrder.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                                dragOffset += step
                                            }
                                        }
                                    )
                                }
                            )
                    ) {
                        ChannelListItem(
                            ch,
                            image = channelImages[ch.adminStreamId],
                            preview = channelPreviews[ch.messageStreamId],
                            peerEnsAvatarUrl = ch.peerAddress?.let { ensAvatars[it.lowercase()] },
                            ensNames = ensNames,
                            unread = unreadCounts[ch.messageStreamId] ?: 0,
                            onOpen = { vm.openChannel(ch.messageStreamId, com.pombo.android.AppViewModel.ChatOrigin.CHATS) }
                        )
                    }
                }
            }
        }

        // DM affordance under the channel list (web: #dm-inbox-setup /
        // #dm-new-btn-wrap). Hidden for guests; before the inbox exists it
        // offers to create it, after that it starts new conversations.
        val isGuest by vm.isGuest.collectAsState()
        val hasDmInbox by vm.hasDmInbox.collectAsState()
        var showNewDm by remember { mutableStateOf(false) }
        var showDmSetup by remember { mutableStateOf(false) }
        LaunchedEffect(isGuest) { vm.refreshDmInbox() }

        if (!isGuest) {
            // Extra bottom room so the button clears the floating pill nav
            // instead of sitting against it.
            Box(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasDmInbox) {
                    AccentPillButton("New DM", Icons.Filled.Add) { showNewDm = true }
                } else {
                    // Web only shows the cost line inside its create-inbox
                    // modal. `hasDmInbox` reads false while the bridge is
                    // still connecting, so a cost line here flashed on every start.
                    AccentPillButton("Create DM Inbox", Icons.Filled.MailOutline) { showDmSetup = true }
                }
            }
        }
        if (showNewDm) NewDmDialog(
            onDismiss = { showNewDm = false },
            onStart = { address, localName -> showNewDm = false; vm.startDm(address, localName) }
        )
        if (showDmSetup) CreateDmInboxDialog(
            vm,
            onDismiss = { showDmSetup = false },
            onCreate = { provider, custom, days -> vm.setupDmInbox(provider, custom, days) }
        )
    }
}

/**
 * One row of the Active Transfers list — shared by mesh and storage transfers.
 * The transport badge (MESH/STORAGE) distinguishes them, and storage rows prefix
 * their stats with the channel name (they survive channel switches). [onCancel]
 * null hides the cancel affordance (storage has no cancel API yet).
 */
@Composable
private fun TransferRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    badge: String,
    name: String,
    sub: String,
    onCancel: (() -> Unit)?
) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.pombo.android.ui.TransportBadge(badge)
                Text(
                    name, color = PomboColors.Text, fontSize = 12.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Text(
                sub, color = Color.White.copy(alpha = 0.40f), fontSize = 10.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (onCancel != null) {
            Icon(
                Icons.Filled.Close, contentDescription = "Cancel",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp).clickableNoRipple { onCancel() }
            )
        }
    }
}

/** Web: the shared accent button style used by both DM buttons. */
@Composable
private fun AccentPillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PomboColors.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Web: #new-dm-modal — address/ENS plus an optional device-local name. */
@Composable
private fun NewDmDialog(onDismiss: () -> Unit, onStart: (String, String?) -> Unit) {
    var address by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text("New DM", color = PomboColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            Text("ADDRESS OR ENS", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                placeholder = { Text("0x... or name.eth", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text("LOCAL NAME (OPTIONAL)", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = localName, onValueChange = { if (it.length <= 30) localName = it },
                placeholder = { Text("e.g. Alice", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text("Stored locally, only visible to you", color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .clickable(enabled = address.isNotBlank()) { onStart(address.trim(), localName) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Start DM", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun ChannelListItem(
    channel: Channel,
    image: ByteArray?,
    preview: com.pombo.android.core.LatestMessageStore.Preview?,
    /** ENS picture of the DM peer, when they have one. */
    peerEnsAvatarUrl: String? = null,
    /** Resolved ENS names (address -> name) for the preview's sender label. */
    ensNames: Map<String, String> = emptyMap(),
    /** Unread messages since this channel was last opened. */
    unread: Int = 0,
    onOpen: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            // `#channel-list .channel-item` on mobile: white 5% fill and a
            // white 8.5% border (components.css:2389). The rows were drawn
            // flat, so the list read as loose text on black rather than the
            // stack of cards the web shows.
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.085f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = remember(image) {
                    android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size).asImageBitmap()
                },
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(46.dp).clip(CircleShape)
            )
        } else if (channel.type == "dm" && channel.peerAddress != null) {
            // Peer's ENS picture when they have one, otherwise the avatar
            // generated from their address. Keying on messageStreamId
            // (peer/Pombo-DM-1) generated a different picture from the one
            // their messages carry inside the chat.
            Avatar(
                channel.peerAddress, size = 46.dp, cornerRadiusFraction = 0.5,
                ensAvatarUrl = peerEnsAvatarUrl
            )
        } else {
            Avatar(channel.messageStreamId, size = 46.dp, cornerRadiusFraction = 0.5)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name, color = PomboColors.Text, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Web: the ONLY row indicator is a megaphone for read-only
                // (announcement) channels — no lock for password channels
                // (the chat header already shows the type). ChannelListUI.js:609.
                if (channel.readOnly) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.Campaign, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(14.dp)
                    )
                }
            }
            // Last message preview, cached like the web sidebar. DMs drop the
            // sender prefix (web omitSender), channels keep "sender: body".
            // The ENS name is resolved HERE (not baked into the stored label) —
            // it usually resolves after the preview was cached.
            preview?.let { p ->
                val ensLabel = p.senderAddress.takeIf { it.isNotEmpty() }
                    ?.let { ensNames[it.lowercase()] }
                Text(
                    if (channel.type == "dm") p.text else "${ensLabel ?: p.sender}: ${p.text}",
                    color = PomboColors.TextDim, fontSize = 11.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        // Unread badge — web caps the label at "+30" and tints it accent.
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (unread >= 30) "+30" else "$unread",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun StatusDot(status: NetStatus) {
    val color = when (status) {
        NetStatus.CONNECTED -> PomboColors.Success
        NetStatus.CONNECTING, NetStatus.BOOTING -> PomboColors.Accent
        else -> PomboColors.Danger
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

/**
 * Order and grouping follow the web's #pill-settings-dropdown, which separates
 * the panels with `<hr>` into: what the account IS, what it KEEPS PRIVATE, and
 * what the app is. `section` marks which group a panel belongs to, so the
 * dropdown can draw the dividers without hard-coding positions.
 *
 * The enum order also drives the settings pager, so swiping between panels
 * follows the same sequence as the menu.
 */
internal enum class SettingsPanel(val label: String, val icon: ImageVector, val section: Int) {
    ACCOUNT("Account", Icons.Outlined.Person, 0),
    WALLET("Wallet", Icons.Outlined.AccountBalanceWallet, 0),
    NOTIFICATIONS("Notifications", Icons.Outlined.Notifications, 0),
    API("API", Icons.Outlined.Link, 0),
    CONTENT("Content", Icons.Outlined.SmartDisplay, 0),
    PRIVACY("Privacy", Icons.Outlined.Shield, 1),
    SECURITY("Security", Icons.Outlined.Lock, 1),
    DM_INBOX("DM Inbox", Icons.Outlined.MailOutline, 1),
    ABOUT("About", Icons.Outlined.Info, 2)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsTab(vm: AppViewModel, pagerState: androidx.compose.foundation.pager.PagerState) {
    val status by vm.status.collectAsState()

    // Any tap outside a text field drops its focus, which is also what
    // commits the display-name edit below.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { focusManager.clearFocus() }
        }
    ) {
        // The header names the panel being viewed (Account, Wallet, …), so the
        // old label under it would just repeat the word.
        SettingsHeader(SettingsPanel.entries[pagerState.currentPage].label)
        Spacer(Modifier.height(8.dp))
        // Panels are swiped horizontally; the pill dropdown jumps straight to one.
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            // Scroll lives HERE, once, for every panel. Panels used to be fixed
            // Columns that simply clipped whatever ran past the screen — Account
            // grew past it and Import/Export became unreachable. Security and
            // Privacy carried their own scroller; those were removed, since two
            // verticalScrolls of the same orientation nested means the inner one
            // measures against infinite height and never scrolls at all.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 16.dp)
            ) {
                when (SettingsPanel.entries[page]) {
                    SettingsPanel.ACCOUNT -> AccountPanel(vm)
                    SettingsPanel.WALLET -> WalletPanel(vm)
                    SettingsPanel.NOTIFICATIONS -> NotificationsSection(vm)
                    SettingsPanel.SECURITY -> SecurityPanel(vm)
                    SettingsPanel.DM_INBOX -> DmInboxPanel(vm)
                    SettingsPanel.PRIVACY -> PrivacyPanel(vm)
                    SettingsPanel.API -> ApiPanel(vm)
                    SettingsPanel.CONTENT -> ContentPanel(vm)
                    SettingsPanel.ABOUT -> AboutPanel()
                }
            }
        }
    }
}

/**
 * Web settings-panel-profile: display name beside the avatar, the account
 * address with a copy button, then Backup & Restore. The backup file is the
 * web's `pombo-account-backup` format, so it restores on either client.
 */
@Composable
private fun AccountPanel(vm: AppViewModel) {
    val username by vm.username.collectAsState()
    val address by vm.address.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    val isGuest by vm.isGuest.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // A primary ENS name takes over the display name entirely (web
    // SettingsUI.show): the field shows it, locked, with an "ENS Verified"
    // badge where the hint normally sits.
    val ensNames by vm.ensNames.collectAsState()
    LaunchedEffect(address) { address?.let { vm.ensureEns(it) } }
    // Device Sync below is shown only with an inbox, and the flag is otherwise
    // only refreshed on connect — re-check on entry so the section is not
    // missing on a panel opened before that landed.
    LaunchedEffect(address) { vm.refreshDmInbox() }
    val ensName = address?.let { ensNames[it.lowercase()] }
    var editing by remember(username, ensName) { mutableStateOf(ensName ?: username ?: "") }

    // Export: ask for a password, then pick where to save. The password rides
    // in state between the two steps because SAF answers via callback.
    var askExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val pwd = exportPassword
        exportPassword = null
        if (uri != null && pwd != null) vm.exportBackupTo(uri, pwd)
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importUri = uri }

    if (askExportPassword) BackupPasswordDialog(
        title = "Export Account Backup",
        hint = "Choose a password to protect the backup (min 8 characters). You will need it to restore, on Android or on the web.",
        confirmLabel = "Export",
        minLength = 8,
        onDismiss = { askExportPassword = false }
    ) { pwd ->
        askExportPassword = false
        exportPassword = pwd
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        exportLauncher.launch("pombo-account-backup-$stamp.json")
    }
    importUri?.let { uri ->
        BackupPasswordDialog(
            title = "Restore Account Backup",
            hint = "Enter the password this backup was created with.",
            confirmLabel = "Restore",
            onDismiss = { importUri = null }
        ) { pwd ->
            importUri = null
            vm.importBackupFrom(uri, pwd)
        }
    }

    SettingsSection {
        // Web: name field capped at 180px with the 56px avatar beside it.
        // Saved when the field loses focus — the same commit point as the
        // web's `change` event; setUsername shows the confirmation toast.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(max = 180.dp)) {
                SettingsFieldLabel("Display Name")
                OutlinedTextField(
                    // Web: with ENS active the field CARRIES the ENS name.
                    value = ensName ?: editing,
                    onValueChange = { if (it.length <= 18) editing = it },
                    placeholder = { Text("Enter your name", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                    colors = pomboFieldColors(),
                    singleLine = true,
                    // Web: with a primary ENS name the field is `disabled` and
                    // carries it — the on-chain name takes precedence over a
                    // local one.
                    enabled = ensName == null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { state ->
                            if (ensName == null && !state.isFocused &&
                                editing.trim() != (username ?: "")
                            ) {
                                vm.setUsername(editing)
                            }
                        }
                )
                Spacer(Modifier.height(6.dp))
                // The ENS badge replaces the default hint, as on the web.
                if (ensName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircleOutline, contentDescription = null,
                            tint = Color(0xFF4ADE80), modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        // text-xs text-blue-400
                        Text("ENS Verified", color = Color(0xFF60A5FA), fontSize = 12.sp)
                    }
                } else {
                    Text("Visible to other users", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
                }
            }
            // Centred in the gap between the name field and the card's edge,
            // not glued to the margin.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Avatar(
                    address ?: "guest", size = 72.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = address?.let { ensAvatars[it.lowercase()] }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsFieldLabel("Account Address")
        // Tapping the address itself copies it — no separate button.
        Box(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .clickableNoRipple {
                    address?.let {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                        vm.toast("Address copied", com.pombo.android.ui.ToastKind.SUCCESS)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                address ?: "—",
                color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1
            )
        }

        // Sync publishes to the account's own DM inbox (partition 1);
        // pushSync/pullSync both bail on `inboxExists()`, so without an inbox
        // the whole block is absent rather than disabled.
        val hasDmInbox by vm.hasDmInbox.collectAsState()
        if (hasDmInbox) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Spacer(Modifier.height(20.dp))

            // Moves this account's state between the user's own devices —
            // separate from which endpoints the client talks to (API panel).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Sync, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Device Sync", color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sync your app state, including sent DMs from other devices",
                color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            val syncMode by vm.syncMode.collectAsState()
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp)
            ) {
                com.pombo.android.data.SyncMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickableNoRipple { vm.setSyncMode(mode) }
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = syncMode == mode,
                            onClick = { vm.setSyncMode(mode) },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = PomboColors.Accent,
                                unselectedColor = Color.White.copy(alpha = 0.30f)
                            )
                        )
                        Text(
                            mode.label,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Storage, contentDescription = null,
                tint = Color.White.copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Backup & Restore", color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Text("Export data to restore on another device", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(
                label = "Export", icon = Icons.Outlined.FileUpload,
                emphasized = true, enabled = !isGuest, modifier = Modifier.weight(1f)
            ) { askExportPassword = true }
            BackupButton(
                label = "Import", icon = Icons.Outlined.FileDownload,
                emphasized = false, enabled = true, modifier = Modifier.weight(1f)
            ) { importLauncher.launch(arrayOf("application/json")) }
        }
        // Channel passwords are included: Channel.toJson() carries `password`,
        // and the backup payload is exportSyncState(), which builds `channels`
        // from it — the whole file is scrypt/AES-GCM sealed with the export
        // password anyway.
    }
}

/** Web label style: 12px uppercase, wide tracking. */
@Composable
private fun SettingsFieldLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.80f), fontSize = 11.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
    )
    Spacer(Modifier.height(8.dp))
}

/** The web's Export (bright) / Import (dim) pair of bordered buttons. */
@Composable
private fun BackupButton(
    label: String,
    icon: ImageVector,
    emphasized: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.25f)
        emphasized -> Color.White
        else -> Color.White.copy(alpha = 0.60f)
    }
    Row(
        modifier
            .background(
                Color.White.copy(alpha = if (emphasized) 0.10f else 0.05f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (emphasized) 0.20f else 0.10f),
                RoundedCornerShape(12.dp)
            )
            .clickableNoRipple { if (enabled) onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.sp)
    }
}

/** Password prompt for backup export/restore (web showPasswordPrompt). */
@Composable
private fun BackupPasswordDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    minLength: Int = 1,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    val valid = password.length >= minLength
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = PomboColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                placeholder = { Text("Password", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (valid) Color.White else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple { if (valid) onSubmit(password) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        confirmLabel,
                        color = if (valid) Color.Black else Color.White.copy(alpha = 0.30f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Global notification switch — web index.html "Channel Invites" / push settings.
 * It only obtains the delivery token; which channels actually notify is chosen
 * per channel, exactly as on web (subscribeToChannel refuses without this).
 */
@Composable
private fun NotificationsSection(vm: AppViewModel) {
    val enabled by vm.pushEnabled.collectAsState()
    val dmPush by vm.dmPushEnabled.collectAsState()
    val invites by vm.inviteNotifications.collectAsState()
    LaunchedEffect(Unit) { vm.refreshDmInbox() }
    SettingsSection {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Push Notifications", color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Get notified about new messages. Turn on per channel afterwards.",
                    color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { vm.setPushEnabled(it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFF6851B),
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.60f)
                )
            )
        }
        // Web #dm-push-section: the "Direct Messages" sub-option, indented
        // under the global toggle with a left border, only while push is on.
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(start = 6.dp)) {
                Box(
                    Modifier.width(1.dp).heightIn(min = 40.dp)
                        .background(Color.White.copy(alpha = 0.10f))
                )
                Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Direct Messages",
                            color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Get notified for new DMs",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Switch(
                        checked = dmPush,
                        onCheckedChange = { vm.setDmPushEnabled(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFFF6851B),
                            checkedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.60f)
                        )
                    )
                }
            }
        }
        // Web: divider + "Channel Invites" toggle (#notifications-enabled).
        // Muting unsubscribes the inbox P3 partition, so invites stop arriving
        // at all — same behaviour as the web's always-open tab.
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(16.dp))
        ContentToggleRow(
            title = "Channel Invites",
            hint = "Show notifications when you receive channel invites",
            checked = invites,
            trackColor = Color(0xFFF6851B)
        ) { vm.setInviteNotifications(it) }
        // Web #notifications-status: "Muted" under the toggle while off.
        if (!invites) {
            Spacer(Modifier.height(8.dp))
            Text("Muted", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        }
    }
}

/**
 * Web settings-panel-linkpreviews ("Content"): what message links expand into
 * and whether Explore surfaces sensitive channels.
 */
@Composable
private fun ContentPanel(vm: AppViewModel) {
    val ytEnabled by vm.youtubeEmbeds.collectAsState()
    val nsfwEnabled by vm.nsfwEnabled.collectAsState()
    SettingsSection {
        ContentToggleRow(
            title = "YouTube Embeds",
            hint = "Show video player when YouTube links are shared",
            checked = ytEnabled,
            // The web paints this one toggle YouTube red, not accent orange.
            trackColor = Color(0xFFFF0000)
        ) { vm.setYoutubeEmbeds(it) }
        Spacer(Modifier.height(16.dp))
        ContentToggleRow(
            title = "Show Sensitive Content",
            hint = "Enable NSFW filter in channel exploration",
            checked = nsfwEnabled,
            trackColor = PomboColors.Accent
        ) { vm.setNsfwEnabled(it) }
    }
}

/**
 * Web settings-panel-repair, renamed "DM Inbox": diagnose/repair the inbox
 * streams and manage its storage nodes. Diagnosis is read-only; repair and
 * every storage change are on-chain and cost gas.
 */
@Composable
private fun DmInboxPanel(vm: AppViewModel) {
    val health by vm.inboxHealth.collectAsState()
    val storage by vm.inboxStorage.collectAsState()
    var showDmSetup by remember { mutableStateOf(false) }
    // Storage shows on open, not gated behind a Diagnose (like the channel
    // storage panel).
    LaunchedEffect(Unit) { vm.loadInboxStorage() }

    if (showDmSetup) CreateDmInboxDialog(
        vm,
        onDismiss = { showDmSetup = false },
        onCreate = { provider, custom, days -> vm.setupDmInbox(provider, custom, days) }
    )

    SettingsSection {
        Text("Check and repair your DM inbox", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))

        // ---- Unified status card (web #repair-status-card) ----
        if (health !is AppViewModel.InboxHealth.NoInbox) {
            val (accent, title, detail) = when (val h = health) {
                is AppViewModel.InboxHealth.Idle ->
                    Triple(Color.White.copy(alpha = 0.20f), "Not checked", "Run a diagnosis to check inbox health")
                is AppViewModel.InboxHealth.Loading ->
                    Triple(Color.White.copy(alpha = 0.40f), "Diagnosing...", "Checking streams, permissions and storage")
                is AppViewModel.InboxHealth.Healthy ->
                    Triple(Color(0xFF4ADE80), "Inbox healthy", h.detail)
                is AppViewModel.InboxHealth.Issues ->
                    Triple(Color(0xFFFACC15), "Issues found", h.summary)
                is AppViewModel.InboxHealth.Failed ->
                    Triple(Color(0xFFF87171), "Error", h.message)
                is AppViewModel.InboxHealth.Repairing ->
                    Triple(PomboColors.Accent, "Repairing...", h.step)
                is AppViewModel.InboxHealth.Repaired ->
                    Triple(Color(0xFF4ADE80), "Repaired", h.detail)
                else -> Triple(Color.White.copy(alpha = 0.20f), "Not checked", "")
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                    .border(1.dp, accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).background(accent.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    when (health) {
                        is AppViewModel.InboxHealth.Loading, is AppViewModel.InboxHealth.Repairing ->
                            androidx.compose.material3.CircularProgressIndicator(
                                color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)
                            )
                        is AppViewModel.InboxHealth.Healthy, is AppViewModel.InboxHealth.Repaired ->
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        is AppViewModel.InboxHealth.Issues ->
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        is AppViewModel.InboxHealth.Failed ->
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        else ->
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(detail, color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        val busyNow = health is AppViewModel.InboxHealth.Loading || health is AppViewModel.InboxHealth.Repairing
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (health is AppViewModel.InboxHealth.NoInbox) {
                // Web: the primary action morphs into "Create DM Inbox".
                Row(
                    Modifier.weight(1f)
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { showDmSetup = true }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create DM Inbox", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Row(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { if (!busyNow) vm.diagnoseInbox() }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.70f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Diagnose", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                }
                if (health is AppViewModel.InboxHealth.Issues) {
                    Row(
                        Modifier.weight(1f)
                            .background(PomboColors.Accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(8.dp))
                            .clickableNoRipple { if (!busyNow) vm.repairInbox() }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Repair", color = PomboColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ---- Inbox storage (web #inbox-storage-section) ----
        val info = storage
        if (info != null && health !is AppViewModel.InboxHealth.NoInbox) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Spacer(Modifier.height(16.dp))
            Text("Inbox Storage", color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Change the storage node that persists your DM inbox.",
                color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "RETENTION PERIOD", color = Color.White.copy(alpha = 0.30f),
                fontSize = 10.sp, letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            var retentionDraft by remember(info.storageDays) {
                mutableStateOf(info.storageDays?.toString() ?: "")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = retentionDraft,
                    onValueChange = { v -> retentionDraft = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.width(90.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("days", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                val days = retentionDraft.toIntOrNull()
                val valid = days != null && days in 1..3650 && days != info.storageDays
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = if (valid) 0.10f else 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { if (valid) vm.setInboxRetention(days!!) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Save",
                        color = Color.White.copy(alpha = if (valid) 0.80f else 0.30f), fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "STORAGE NODES", color = Color.White.copy(alpha = 0.30f),
                fontSize = 10.sp, letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            if (info.nodes.isEmpty()) {
                Text("No storage nodes", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
            } else {
                info.nodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            // Same identification the channel storage panel
                            // uses: "Pombo" vs "Custom" against pomboStorageNode.
                            Text(
                                if (node.equals(vm.pomboStorageNode, ignoreCase = true)) "Pombo" else "Custom",
                                color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp
                            )
                            Text(
                                node, color = Color.White.copy(alpha = 0.70f), fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Delete, contentDescription = "Remove storage node",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(18.dp).clickableNoRipple { vm.removeInboxStorageNode(node) }
                        )
                    }
                }
            }

            // Add node (web #inbox-storage-add-form)
            Spacer(Modifier.height(6.dp))
            var addingNode by remember { mutableStateOf(false) }
            if (!addingNode) {
                Row(
                    Modifier.clickableNoRipple { addingNode = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add storage node", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
                }
            } else {
                var provider by remember { mutableStateOf("streamr") }
                var customAddr by remember { mutableStateOf("") }
                var daysDraft by remember { mutableStateOf("180") }
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("streamr" to "Pombo", "custom" to "Custom storage node").forEach { (value, label) ->
                            Row(
                                Modifier.clickableNoRipple { provider = value },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = provider == value,
                                    onClick = { provider = value },
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                        selectedColor = PomboColors.Accent
                                    )
                                )
                                Text(label, color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp)
                            }
                        }
                    }
                    if (provider == "custom") {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = customAddr, onValueChange = { customAddr = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp, color = PomboColors.Text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            decorationBox = { inner ->
                                if (customAddr.isEmpty()) Text(
                                    "0x… custom storage node address",
                                    color = Color.White.copy(alpha = 0.20f), fontSize = 12.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = daysDraft,
                            onValueChange = { v -> daysDraft = v.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.width(90.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("retention", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            "Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                            modifier = Modifier.clickableNoRipple { addingNode = false }.padding(8.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        val addDays = daysDraft.toIntOrNull()
                        val addValid = addDays != null && addDays in 1..3650 &&
                            (provider == "streamr" || Regex("^0x[a-fA-F0-9]{40}$").matches(customAddr.trim()))
                        Box(
                            Modifier
                                .background(
                                    if (addValid) PomboColors.Accent else PomboColors.Accent.copy(alpha = 0.40f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple {
                                    if (addValid) {
                                        vm.addInboxStorageNode(provider, customAddr.trim().ifEmpty { null }, addDays!!)
                                        addingNode = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) { Text("Add", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }

            // Gas warning (web amber card)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Outlined.WarningAmber, contentDescription = null,
                    tint = Color(0xFFFBBF24).copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Storage changes require on-chain transactions and gas fees.",
                    color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ContentToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    trackColor: Color,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = trackColor,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.60f)
            )
        )
    }
}

/**
 * Web settings-panel-wallet: the POL balance card with a refresh action and
 * the "Fund with MetaMask" button. Additions here: a DATA token balance card
 * (not on the web) — and no DM inbox status, which was never part of the
 * web's wallet panel.
 */
@Composable
private fun WalletPanel(vm: AppViewModel) {
    val address by vm.address.collectAsState()
    val balanceWei by vm.balanceWei.collectAsState()
    val dataBalanceWei by vm.dataBalanceWei.collectAsState()
    val balancesFailed by vm.balancesFailed.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    // Web: SettingsUI refreshes the balance when the wallet panel is shown.
    LaunchedEffect(address) { if (address != null) vm.refreshGas() }
    SettingsSection {
        WalletBalanceCard(
            label = "POL Balance",
            value = when {
                balanceWei != null -> com.pombo.android.core.GasEstimator.formatBalancePOL(balanceWei)
                balancesFailed -> "Unavailable — tap refresh"
                else -> "Loading..."
            },
            onRefresh = { vm.refreshGas() }
        )
        Spacer(Modifier.height(16.dp))
        WalletBalanceCard(
            label = "DATA Balance",
            value = when {
                dataBalanceWei != null -> com.pombo.android.core.GasEstimator.formatBalanceDATA(dataBalanceWei)
                balancesFailed -> "Unavailable — tap refresh"
                else -> "Loading..."
            },
            onRefresh = { vm.refreshGas() }
        )
        Spacer(Modifier.height(16.dp))
        // Web fund-metamask-btn talks to the injected extension; on Android
        // the MetaMask APP deep link opens its send screen pre-filled with
        // this address on Polygon (chain 137) — the amount is chosen there.
        // Without MetaMask installed the link lands on their install page.
        Row(
            Modifier.fillMaxWidth()
                .background(PomboColors.Accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .clickableNoRipple {
                    address?.let { addr ->
                        // MetaMask's /send deep-link route demands a chain id
                        // it then can't match against the wallet's networks —
                        // "network not found" with @137 and @0x89, "missing
                        // chain_id" without. So: copy the address, open the
                        // MetaMask APP directly, and the user pastes into
                        // Send. Not installed → Play Store page.
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(addr))
                        vm.toast(
                            "Address copied — paste it in MetaMask's Send",
                            com.pombo.android.ui.ToastKind.SUCCESS, 5000L
                        )
                        val launch = context.packageManager.getLaunchIntentForPackage("io.metamask")
                        if (launch != null) {
                            context.startActivity(launch)
                        } else {
                            uriHandler.openUri("https://play.google.com/store/apps/details?id=io.metamask")
                        }
                    }
                }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AccountBalanceWallet, contentDescription = null,
                tint = PomboColors.Accent, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Fund with MetaMask", color = PomboColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Send POL from your MetaMask wallet",
            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** Web wallet panel balance card: value + refresh icon, network hint below. */
@Composable
private fun WalletBalanceCard(label: String, value: String, onRefresh: () -> Unit) {
    Text(
        label.uppercase(),
        color = Color.White.copy(alpha = 0.80f), fontSize = 11.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
    )
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.Refresh, contentDescription = "Refresh",
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(16.dp).clickableNoRipple(onRefresh)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("On Polygon network", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
    }
}

/**
 * Text field matching the RPC selector row's height — the stock
 * OutlinedTextField's 56dp minimum towers over the other list elements.
 * Commits when focus leaves (tap away or IME done), like the web's `change`
 * event; no save button.
 */
@Composable
private fun CompactSettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onCommit: () -> Unit
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { focusManager.clearFocus() }
        ),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { state ->
                // Guard against the initial not-focused event at composition:
                // only a real focus loss commits.
                if (state.isFocused) hadFocus = true
                else if (hadFocus) { hadFocus = false; onCommit() }
            }
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
            }
            inner()
        }
    )
}

@Composable
private fun ApiPanel(vm: AppViewModel) {
    // Channel discovery goes through The Graph; without a key of your own it
    // uses the shared one bundled with the app.
    val graphKey by vm.graphApiKey.collectAsState()
    var keyDraft by remember(graphKey) { mutableStateOf(graphKey) }
    SettingsSection {
        Text("The Graph API Key", color = PomboColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        // Commits when the field loses focus (tap away / IME done), like the
        // web's `change` event — setGraphApiKey shows the toast.
        CompactSettingsField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            placeholder = "Optional - uses default if empty"
        ) {
            if (keyDraft.trim() != graphKey) vm.setGraphApiKey(keyDraft)
        }
        Spacer(Modifier.height(6.dp))
        Text("Get yours at thegraph.com/studio", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        // Web #graph-api-status: yellow while on the shared default key.
        if (graphKey.isEmpty()) {
            Text("Using default key (rate limited)", color = Color(0xFFFACC15), fontSize = 12.sp)
        } else {
            Text("Using custom key", color = Color(0xFF4ADE80), fontSize = 12.sp)
        }

        // ---- Polygon RPC endpoint (web #rpc-preset-select) ----
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(16.dp))
        Text(
            "POLYGON RPC ENDPOINT", color = Color.White.copy(alpha = 0.80f),
            fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
        )
        Spacer(Modifier.height(8.dp))

        val rpcPreset by vm.rpcPreset.collectAsState()
        val rpcCustomSaved by vm.rpcCustomUrl.collectAsState()
        var rpcMenu by remember { mutableStateOf(false) }
        var customDraft by remember(rpcCustomSaved) { mutableStateOf(rpcCustomSaved) }

        Box {
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .clickableNoRipple { rpcMenu = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    com.pombo.android.core.RpcPresets.byKey(rpcPreset).label,
                    color = PomboColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(18.dp)
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = rpcMenu,
                onDismissRequest = { rpcMenu = false },
                modifier = Modifier.background(Color(0xFF16161B))
            ) {
                com.pombo.android.core.RpcPresets.ALL.forEach { preset ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                preset.label, fontSize = 13.sp,
                                color = if (preset.key == rpcPreset) PomboColors.Accent else PomboColors.Text
                            )
                        },
                        onClick = {
                            rpcMenu = false
                            vm.setRpcPreference(preset.key, customDraft)
                        }
                    )
                }
            }
        }

        if (rpcPreset == "custom") {
            Spacer(Modifier.height(10.dp))
            // Applies on focus loss like the key field — setRpcPreference
            // toasts and reconnects.
            CompactSettingsField(
                value = customDraft,
                onValueChange = { customDraft = it },
                placeholder = "https://your-rpc-endpoint.com"
            ) {
                val url = customDraft.trim()
                if (url.startsWith("https://") && url != rpcCustomSaved) {
                    vm.setRpcPreference("custom", url)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Enter a valid Polygon RPC endpoint URL",
                color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
            )
        }

        // Status dot + Test Connection (web #rpc-status / #test-rpc-btn).
        Spacer(Modifier.height(12.dp))
        val rpcTest by vm.rpcTest.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, statusText, textColor) = when (val t = rpcTest) {
                is AppViewModel.RpcTest.Idle ->
                    Triple(Color.White.copy(alpha = 0.20f), "Not tested", Color.White.copy(alpha = 0.40f))
                is AppViewModel.RpcTest.Testing ->
                    Triple(Color(0xFFFACC15), "Testing...", Color(0xFFFACC15))
                is AppViewModel.RpcTest.Done -> when {
                    t.working == 0 -> Triple(Color(0xFFEF4444), "All endpoints failed", Color(0xFFF87171))
                    t.working < t.total -> Triple(Color(0xFFFACC15), "${t.working}/${t.total} working", Color(0xFFFACC15))
                    else -> Triple(Color(0xFF22C55E), "Connected", Color(0xFF4ADE80))
                }
            }
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(statusText, color = textColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .clickableNoRipple { if (rpcTest !is AppViewModel.RpcTest.Testing) vm.testRpc() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Test Connection", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
            }
        }
    }
}

/**
 * Security — the web's #settings-panel-security: reveal the private key, and
 * delete the account. Both are two-step and both end in a hold-to-confirm,
 * because both are unrecoverable.
 *
 * The web's first step is "enter your keystore password". This app has no such
 * password (the key is sealed by the Android Keystore, bound to the device
 * lock), so the equivalent proof of ownership is the device credential.
 */
@Composable
private fun SecurityPanel(vm: AppViewModel) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var keyVisible by remember { mutableStateOf(false) }
    var deleteVerified by remember { mutableStateOf(false) }
    val noDeviceLock = remember { !com.pombo.android.ui.DeviceAuth.canAuthenticate(context) }

    // The unlocked private key renders on this panel — keep it out of
    // screenshots, recordings and the recents thumbnail (M-I1).
    com.pombo.android.ui.SecureFlag()

    // No scroller of its own — the settings pager scrolls every panel.
    Column(Modifier.fillMaxSize()) {
        if (noDeviceLock) {
            Text(
                "This device has no screen lock. Set one up to export your key or " +
                    "delete this account — without it there is no way to confirm it is you.",
                color = PomboColors.Danger.copy(alpha = 0.90f), fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PomboColors.Danger.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Danger.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            )
            Spacer(Modifier.height(16.dp))
        }

        DangerCard(title = "Private Key", hint = "Anyone with this key has full control of your account") {
            val key = revealedKey
            if (key == null) {
                DangerButton("Unlock Key", enabled = !noDeviceLock) {
                    activity?.let {
                        com.pombo.android.ui.DeviceAuth.authenticate(
                            it, "Unlock private key",
                            "Confirm it is you before the key is shown"
                        ) { ok -> if (ok) revealedKey = vm.exportPrivateKey() }
                    }
                }
            } else {
                Text(
                    if (keyVisible) key else "•".repeat(key.length.coerceAtMost(48)),
                    color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Danger.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { keyVisible = !keyVisible }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (keyVisible) "Hide" else "Show",
                            color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { revealedKey = null; keyVisible = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Lock", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp) }
                }
                Spacer(Modifier.height(10.dp))
                HoldToConfirmButton("Hold to Copy (2s)") {
                    // Sensitive-flagged (no preview, no clipboard sync) and
                    // auto-cleared after 60s — the key must not sit in
                    // clipboard history forever.
                    com.pombo.android.ui.SensitiveClipboard.copy(context, key)
                    vm.toast(
                        "Private key copied — clears from the clipboard in 60s",
                        com.pombo.android.ui.ToastKind.WARNING, 6000L
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        DangerCard(title = "Delete Account", hint = "Permanently delete this account and all its data") {
            if (!deleteVerified) {
                DangerButton("Verify", enabled = !noDeviceLock) {
                    activity?.let {
                        com.pombo.android.ui.DeviceAuth.authenticate(
                            it, "Delete account",
                            "Confirm it is you before this account is erased"
                        ) { ok -> deleteVerified = ok }
                    }
                }
            } else {
                Text(
                    "This deletes the key for this account from this device, along with its " +
                        "channels and contacts. Without a copy of the private key it cannot be " +
                        "recovered — not by us, not by anyone.",
                    color = Color(0xFFFCA5A5), fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PomboColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .border(1.dp, PomboColors.Danger.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                HoldToConfirmButton("Hold to Delete (2s)") {
                    deleteVerified = false
                    vm.deleteAccount()
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { deleteVerified = false }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Privacy — the web's blocked-peers list with unblock (SettingsUI renderBlockedPeersList). */
@Composable
private fun PrivacyPanel(vm: AppViewModel) {
    // Recomposition trigger: the store is plain state, not a flow.
    var version by remember { mutableStateOf(0) }
    val blocked = remember(version) { vm.blockedPeers.toList().sorted() }

    // No scroller of its own — the settings pager scrolls every panel.
    Column(Modifier.fillMaxSize()) {
        Text(
            "BLOCKED USERS", color = Color.White.copy(alpha = 0.70f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Messages from these accounts are ignored before they are decrypted.",
            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        if (blocked.isEmpty()) {
            Text("No blocked users.", color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp)
        } else {
            blocked.forEach { addr ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(addr, size = 34.dp, cornerRadiusFraction = 0.5)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        shortAddress(addr), color = PomboColors.Text, fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                            .clickableNoRipple { vm.unblockPeer(addr); version++ }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text("Unblock", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Red-tinted card the web uses for both destructive sections. */
@Composable
private fun DangerCard(title: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(PomboColors.Danger.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(PomboColors.Danger.copy(alpha = if (enabled) 0.10f else 0.03f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Danger.copy(alpha = if (enabled) 0.20f else 0.08f), RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Color(0xFFF87171) else Color(0xFFF87171).copy(alpha = 0.35f),
            fontSize = 14.sp
        )
    }
}

/**
 * Hold-to-confirm, matching the web's 2-second press-and-hold on both
 * destructive actions. A tap does nothing: the delay is the confirmation, so
 * neither copying a private key nor deleting an account can happen by accident.
 */
@Composable
private fun HoldToConfirmButton(label: String, onConfirm: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (holding) 2000 else 200,
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = "hold-progress"
    )
    // Fire when the bar actually reaches the end, so the visible progress and
    // the action can never disagree.
    LaunchedEffect(progress, holding) {
        if (holding && progress >= 1f) { holding = false; onConfirm() }
    }
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFDC2626).copy(alpha = 0.90f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        // Releasing early cancels: tryAwaitRelease returns on
                        // both release and cancellation, and either way the
                        // bar rewinds without firing.
                        tryAwaitRelease()
                        holding = false
                    }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(40.dp)
                .background(Color(0xFFF87171).copy(alpha = 0.40f))
        )
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AboutPanel() {
    // Web #settings-panel-about: one Disclaimer block, verbatim. The blurb we
    // had here was ours, not the web's, and this text is a legal notice — it
    // gets copied exactly rather than paraphrased.
    SettingsSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info, contentDescription = null,
                tint = Color.White.copy(alpha = 0.90f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Disclaimer",
                color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Pombo provides access to decentralized communication protocols. " +
                "You assume full legal responsibility for your actions within your " +
                "jurisdiction. We disclaim all liability and reserve the right to " +
                "restrict access to specific channels via this interface.",
            color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp, lineHeight = 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Justify
        )
        Spacer(Modifier.height(16.dp))
        Text("pombo.cc", color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp)
    }
}

/**
 * "Sync Devices" — the web puts this in the same pill dropdown as the settings
 * panels (#pill-sync-devices-btn), with an 18px #F6851B spinner while running.
 */
@Composable
private fun SyncDevicesRow(vm: AppViewModel) {
    val syncing by vm.sync.syncing.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple { if (!syncing) vm.syncNow() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (syncing) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color(0xFFF6851B),
                trackColor = Color(0xFFF6851B).copy(alpha = 0.16f),
                strokeWidth = 2.5.dp
            )
        } else {
            Icon(
                Icons.Outlined.Refresh, contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (syncing) "Syncing your data" else "Sync Devices",
            color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp
        )
    }
}

/** Web .pill-settings-dropdown — opens upward from the Settings pill item. */
@Composable
private fun SettingsDropdown(
    current: SettingsPanel,
    syncRow: (@Composable () -> Unit)? = null,
    onPick: (SettingsPanel) -> Unit
) {
    Column(
        Modifier
            .padding(bottom = 6.dp)
            .width(200.dp)
            .background(Color(0xFF16161B).copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(8.dp)
    ) {
        SettingsPanel.entries.forEachIndexed { i, panel ->
            // Web: `<hr class="border-white/[0.06] my-0.5">` between sections.
            val previous = SettingsPanel.entries.getOrNull(i - 1)
            if (previous != null && previous.section != panel.section) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                        .background(Color.White.copy(alpha = 0.06f))
                )
            }
            val active = panel == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickableNoRipple { onPick(panel) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    panel.icon, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    panel.label,
                    color = Color.White.copy(alpha = if (active) 0.90f else 0.60f),
                    fontSize = 13.sp
                )
            }
        }
        syncRow?.let {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                .background(Color.White.copy(alpha = 0.06f)))
            it()
        }
    }
}

@Composable
private fun SettingsSection(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PomboColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // The panel's name already sits in the header, so most cards pass no
        // title — repeating the word directly under it reads as an echo.
        if (title != null) {
            Text(title, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
private fun ProfileTab(vm: AppViewModel, onAddAccount: () -> Unit) {
    val ensAvatars by vm.ensAvatars.collectAsState()
    val address by vm.address.collectAsState()
    val username by vm.username.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val hasDmInbox by vm.hasDmInbox.collectAsState()
    val isGuest by vm.isGuest.collectAsState()
    LaunchedEffect(address) { vm.refreshDmInbox() }

    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Display precedence everywhere: ENS name → username → address.
        val ensNames by vm.ensNames.collectAsState()
        LaunchedEffect(address) { vm.ensureEns(address) }
        val myEns = address?.let { ensNames[it.lowercase()] }

        Spacer(Modifier.height(24.dp))
        val addr = address
        if (addr != null) Avatar(
            addr, size = 88.dp, cornerRadiusFraction = 0.3,
            ensAvatarUrl = ensAvatars[addr.lowercase()]
        )
        Spacer(Modifier.height(16.dp))
        // Same precedence as everywhere else (ENS → username → short address).
        // "No name" was a dead end for an account without one — a guest, above
        // all, which now has no username at all.
        Text(
            myEns ?: username ?: addr?.let { shortAddr(it) } ?: "No name",
            color = PomboColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        if (myEns != null && username != null) {
            Text(username!!, color = PomboColors.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
        }
        Text(address ?: "—", color = PomboColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))

        // Accounts (multi-wallet switching). Also shown to a guest with a single
        // stored account — otherwise "Browse as guest" would be a one-way door.
        if (accounts.size > 1 || (isGuest && accounts.isNotEmpty())) {
            Text("ACCOUNTS", color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.em, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            // Switching identity re-authenticates (M-I2 — the web asks for the
            // keystore password on every unlock/switch; the device credential
            // is this app's equivalent boundary). A device with no lock has
            // nothing to verify against, so it switches directly — same rule
            // DeviceAuth applies everywhere else.
            val switchActivity = androidx.compose.ui.platform.LocalContext.current
                as? androidx.fragment.app.FragmentActivity
            Column(
                Modifier.fillMaxWidth().background(PomboColors.Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
            ) {
                accounts.forEachIndexed { i, acc ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = PomboColors.Border)
                    val isCurrent = acc.address.equals(address, ignoreCase = true)
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple {
                            if (!isCurrent) {
                                val act = switchActivity
                                if (act != null && com.pombo.android.ui.DeviceAuth.canAuthenticate(act)) {
                                    com.pombo.android.ui.DeviceAuth.authenticate(
                                        act, "Switch account",
                                        "Confirm it is you before switching identity"
                                    ) { ok -> if (ok) vm.switchWallet(acc.address) }
                                } else {
                                    vm.switchWallet(acc.address)
                                }
                            }
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LaunchedEffect(acc.address) { vm.ensureEns(acc.address) }
                        Avatar(
                            acc.address, size = 34.dp, cornerRadiusFraction = 0.5,
                            ensAvatarUrl = ensAvatars[acc.address.lowercase()]
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ensNames[acc.address.lowercase()] ?: shortAddr(acc.address),
                            color = PomboColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        if (isCurrent) Text("current", color = PomboColors.Success, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(
            Modifier.fillMaxWidth().background(PomboColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
        ) {
            ProfileAction("Add account", onClick = onAddAccount)
            androidx.compose.material3.HorizontalDivider(color = PomboColors.Border)
            // Only an offer while there is nothing to offer — the inbox is a
            // one-time on-chain setup, so once it exists the row is noise (and
            // tapping it would just pay gas to recreate what is already there).
            var showDmSetup by remember { mutableStateOf(false) }
            if (!hasDmInbox) {
                ProfileAction("Set up DM inbox (enables receiving DMs)") { showDmSetup = true }
                androidx.compose.material3.HorizontalDivider(color = PomboColors.Border)
            }
            if (showDmSetup) CreateDmInboxDialog(
                vm,
                onDismiss = { showDmSetup = false },
                onCreate = { provider, custom, days -> vm.setupDmInbox(provider, custom, days) }
            )
            val syncing by vm.sync.syncing.collectAsState()
            ProfileAction(if (syncing) "Syncing your data" else "Sync devices") {
                if (!syncing) vm.syncNow()
            }
            androidx.compose.material3.HorizontalDivider(color = PomboColors.Border)
            // Leaves the account behind rather than erasing it: `disconnect()`
            // calls WalletStore.clear(), which drops the current account's key
            // for good. Destroying an account belongs in Settings → Security,
            // behind its device-auth gate and typed confirmation — not one tap
            // away in the profile menu.
            ProfileAction("Browse as guest") { vm.browseAsGuest() }
        }
    }
}

@Composable
private fun ProfileAction(label: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label + if (!enabled) "  (coming soon)" else "",
        color = when {
            danger -> PomboColors.Danger
            !enabled -> PomboColors.TextDim
            else -> PomboColors.Text
        },
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickableNoRipple { if (enabled) onClick() }.padding(16.dp)
    )
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun ExploreTab(vm: AppViewModel, onCreate: () -> Unit, onConnect: () -> Unit) {
    val items by vm.explore.collectAsState()
    val loading by vm.exploreLoading.collectAsState()
    val status by vm.status.collectAsState()
    // Preview sender labels resolve ENS at render (the name lands after the fetch).
    val ensNames by vm.ensNames.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    /** Web #explore-language-filter: "" is All Languages. */
    var language by remember { mutableStateOf("") }
    var privateOnly by remember { mutableStateOf(false) }
    // Access-type marker (N-D): "open" | "gated" | "paid" ("" = all).
    // Explore OPENS on Open — gate-backed storefronts are a tap away.
    var accessFilter by remember { mutableStateOf("open") }
    var categoriesExpanded by remember { mutableStateOf(false) }
    var passwordFor by remember { mutableStateOf<com.pombo.android.ExploreChannel?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadExplore() }

    val isGuest by vm.isGuest.collectAsState()
    val exploreImages by vm.channelImages.collectAsState()
    val nsfw by vm.nsfwEnabled.collectAsState()
    // Web rebuilds the template without the NSFW/Adult chips when the setting
    // turns off — an active selection of one of them resets with it.
    androidx.compose.runtime.LaunchedEffect(nsfw) {
        if (!nsfw && (category == "NSFW" || category == "Adult")) category = "All"
    }

    passwordFor?.let { target ->
        ChannelPasswordDialog(
            channelName = target.name,
            onDismiss = { passwordFor = null },
            onSubmit = { pwd -> passwordFor = null; vm.joinChannel(target.messageStreamId, pwd) }
        )
    }
    Column(Modifier.fillMaxSize()) {
        // Guests get the same orange "Create Account" call to action as in
        // Chats, in the same header slot; accounts get "create channel".
        PomboHeader(status) {
            if (isGuest) {
                Row(
                    Modifier.background(PomboColors.Accent, RoundedCornerShape(12.dp))
                        .clickableNoRipple(onConnect)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Create Account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                // Web #header-create-channel-btn: `px-4 py-1.5 rounded-xl
                // bg-[#F6851B]/15 border-[#F6851B]/30` with a WHITE plus — a
                // rounded rectangle, not the circle we had, and the glyph is
                // white rather than orange.
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onCreate)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, contentDescription = "Create channel", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        Text(
            "EXPLORE",
            color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.15.em, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        // Search + language filter share one row, as in the web template.
        // The input is `bg-white/5 border-white/10 pl-10 pr-4 py-2.5 rounded-xl
        // text-sm` — about 40px tall. An OutlinedTextField forces a 56dp
        // minimum, which is why this box towered over the web's.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Search, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.30f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(
                            "Search channels...",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp
                        )
                        inner()
                    }
                )
            }
            ExploreLanguageFilter(language) { language = it }
        }
        Spacer(Modifier.height(10.dp))

        // Access-type markers (N-D): Open / Gated / Paid — exclusive
        // toggles, none active shows every type. Gated vs Paid is the
        // on-chain gate MODE, resolved per card by loadExplore's lazy pass.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            listOf("open" to "Open", "gated" to "Gated", "paid" to "Paid").forEachIndexed { i, (value, label) ->
                if (i > 0) Text(
                    "|", color = Color.White.copy(alpha = 0.15f), fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                val active = accessFilter == value
                Text(
                    label,
                    color = if (active) Color.Black else Color.White.copy(alpha = 0.50f),
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (active) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickableNoRipple {
                            accessFilter = if (active) "" else value
                            // Password view and the markers are disjoint universes
                            if (accessFilter.isNotEmpty()) privateOnly = false
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // Category chips. Collapsed they scroll sideways in one rail; the
        // chevron expands them into rows so every filter is visible at once
        // (web: .explore-category-rail + #explore-toggle-categories-btn).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (categoriesExpanded) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) { ExploreChips(category, privateOnly, nsfw, { category = it; privateOnly = false }, { privateOnly = !privateOnly; if (privateOnly) accessFilter = "" }) }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) { ExploreChips(category, privateOnly, nsfw, { category = it; privateOnly = false }, { privateOnly = !privateOnly; if (privateOnly) accessFilter = "" }) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (categoriesExpanded) "Collapse filters" else "Show all filters",
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (categoriesExpanded) 180f else 0f)
                    .clickableNoRipple { categoriesExpanded = !categoriesExpanded }
            )
        }
        Spacer(Modifier.height(12.dp))

        val shown = items.filter { c ->
            val matchesQuery = query.isBlank() ||
                c.name.contains(query, true) || c.description.contains(query, true)
            val matchesCategory = category == "All" || c.category.equals(categoryValue(category), true)
            // Web `filterChannels` (N-D semantics): password channels live
            // behind the Private chip — their access is a secret shared
            // out-of-band; everything else, public AND gate-backed
            // (gated/paid), is a storefront and lists in the main view.
            val matchesType = if (privateOnly) c.type == "password" else c.type != "password"
            // Access markers: Open = public; Gated vs Paid split by the gate
            // MODE (unresolved counts as Gated until the cached read lands).
            val matchesAccess = when (accessFilter) {
                "open" -> c.type == "public"
                "gated" -> c.type == "gated" && c.gateMode != 3
                "paid" -> c.type == "gated" && c.gateMode == 3
                else -> true
            }
            // Web filterChannels: NSFW/Adult channels stay hidden unless that
            // very category is selected or "Show Sensitive Content" is on.
            val sensitive = c.category.equals("nsfw", true) || c.category.equals("adult", true)
            val nsfwOk = nsfw || category == "NSFW" || category == "Adult" || !sensitive
            // Web: the language filter only applies once a specific one is
            // picked; "All Languages" is the empty value.
            val matchesLanguage = language.isEmpty() || c.language.equals(language, true)
            matchesQuery && (privateOnly || matchesCategory) && matchesType && matchesAccess && nsfwOk && matchesLanguage
        }

        when {
            // Web: spinner + "Loading channels..." while the list is empty.
            loading && items.isEmpty() -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.40f), strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("Loading channels...", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
            }
            shown.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No channels found", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
            }
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Clearance for the floating pill the list scrolls under.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = 32.dp + PILL_NAV_HEIGHT + 12.dp
                )
            ) {
                items(shown, key = { it.messageStreamId }) { ch ->
                    // Web parity: an Explore tap previews the channel; joining
                    // only happens through the Join button in the chat header.
                    ExploreCard(
                        ch,
                        image = exploreImages[com.pombo.android.core.StreamConstants.deriveAdminId(ch.messageStreamId)],
                        ensNames = ensNames
                    ) {
                        // Protected channels need the password before anything
                        // can be decrypted, so ask up front (web JoinChannelUI).
                        // Gated routes by mode: TOKEN/NFT holders get a real
                        // preview (browse before committing); PAID and
                        // non-holders go through the join flow (entry screen).
                        when {
                            ch.type == "password" -> passwordFor = ch
                            ch.type == "gated" -> vm.openGatedExplore(ch)
                            else -> vm.previewChannel(ch)
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Full category set from the web ExploreUI (orderedCategoryChips). NSFW
 * categories stay behind the same opt-in the web uses.
 */
/**
 * Measured height of the floating pill: 38dp profile avatar + 4dp wrapper
 * padding top and bottom + 6dp inner vertical padding. Named so the content
 * reserve and the pill's own offset cannot drift apart — when they did, the
 * pill sat on top of the "New DM" button.
 */
private val PILL_NAV_HEIGHT = 58.dp

private val EXPLORE_CATEGORIES = listOf(
    "All", "General", "News", "Crypto", "Finance", "Politics", "Science",
    "Gaming", "Sports", "Health", "Tech & AI", "Entertainment", "Education", "Comedy"
)

/** Maps a chip label to the metadata value stored in the channel. */
private fun categoryValue(label: String): String = when (label) {
    "Tech & AI" -> "tech"
    else -> label.lowercase()
}

/**
 * Web #explore-language-filter — a `<select>` styled like the search input.
 * Same option list and the same empty value for "All Languages".
 */
@Composable
private fun ExploreLanguageFilter(selected: String, onPick: (String) -> Unit) {
    val options = remember {
        listOf(
            "" to "All Languages", "en" to "English", "pt" to "Português",
            "es" to "Español", "fr" to "Français", "de" to "Deutsch",
            "it" to "Italiano", "zh" to "中文", "ja" to "日本語",
            "ko" to "한국어", "ru" to "Русский", "ar" to "العربية",
            "other" to "Other"
        )
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .clickableNoRipple { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                options.firstOrNull { it.first == selected }?.second ?: "All Languages",
                color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown, contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp)
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Color(0xFF1E1E1E))
        ) {
            options.forEach { (value, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == selected) Color.White else Color.White.copy(alpha = 0.70f),
                            fontSize = 14.sp
                        )
                    },
                    onClick = { onPick(value); open = false }
                )
            }
        }
    }
}

/**
 * Explore cards run slightly tighter than the web's type scale — the sizes are
 * a straight port of the Tailwind classes, which were set for a desktop-first
 * card. One factor for every text in the card (and its line heights), so the
 * internal proportions stay exactly as designed and a future nudge is one line.
 */
private const val EXPLORE_TEXT_SCALE = 0.92f
private val Number.exp get() = (toFloat() * EXPLORE_TEXT_SCALE).sp

@Composable
private fun ExploreCard(
    ch: com.pombo.android.ExploreChannel,
    image: ByteArray?,
    /** Resolved ENS names (address -> name) for the preview's sender label. */
    ensNames: Map<String, String> = emptyMap(),
    onOpen: () -> Unit
) {
    // Web card is bg-white/[0.03]; on the phone's OLED black that read too
    // dark, so the card sits a touch lighter for contrast against the
    // background (user call, 2026-08-18).
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .clickableNoRipple(onOpen)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // Channel thumb: `rounded-full`, 56×56, avatar fallback at 0.5.
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(image) {
                        android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                )
            } else {
                Avatar(ch.messageStreamId, size = 56.dp, cornerRadiusFraction = 0.5)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Web readOnlyBadge: a megaphone BEFORE the name, white/60.
                    // The CSS is `w-3.5 md:w-5` (14px mobile, 20px desktop);
                    // 14 read too faint on the card, so this sits between them.
                    if (ch.readOnly) {
                        Icon(
                            Icons.Outlined.Campaign, contentDescription = "Read-only",
                            tint = Color.White.copy(alpha = 0.60f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        ch.name, color = Color.White.copy(alpha = 0.90f), fontSize = 16.exp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
                if (ch.description.isNotEmpty()) {
                    // `text-base text-white/40 mt-1 line-clamp-2`
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ch.description, color = Color.White.copy(alpha = 0.40f),
                        fontSize = 16.exp, lineHeight = 21.exp, maxLines = 2
                    )
                }
                if (ch.lastText.isNotEmpty()) {
                    // `.explore-preview-line mt-3 ml-3`: 0.875rem, white/50,
                    // 3 lines on mobile, and indented 12px past the description.
                    Spacer(Modifier.height(12.dp))
                    val ensLabel = ch.lastSenderAddress.takeIf { it.isNotEmpty() }
                        ?.let { ensNames[it.lowercase()] }
                    Text(
                        "${ensLabel ?: ch.lastSender}: ${ch.lastText}",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 14.exp,
                        lineHeight = 18.exp, maxLines = 3,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            // Web: a chevron on the right of every card, `w-4 h-4 text-white/15`.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.size(16.dp)
            )
        }
        // Tags (category / language) — `px-1.5 py-1 bg-white/5 text-white/50
        // text-[11px] rounded`, category hidden when it is the default.
        val tags = listOfNotNull(
            ch.category.takeIf { it.isNotEmpty() && !it.equals("general", true) }?.replaceFirstChar { it.uppercase() },
            ch.language.takeIf { it.isNotEmpty() }?.uppercase()
        )
        // Access stack (N-D) + tags share ONE zone: the stack (VERB / VALUE /
        // QUALIFIER) anchors the center, the badges sit on the bottom-right
        // corner at the same height — one row of card height instead of two,
        // and no orphaned lone badge. Subscribe = recurring (accent-tinted
        // verb); Hold = mere possession, "in your wallet" = "you pay nothing".
        if (ch.gateVerb != null || tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                ch.gateVerb?.let { verb ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            verb.uppercase(),
                            color = if (ch.gateMode == 3) PomboColors.Accent.copy(alpha = 0.70f)
                                else Color.White.copy(alpha = 0.40f),
                            fontSize = 10.exp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ch.gateValue ?: "",
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 15.exp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ch.gateQualifier ?: "",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 11.exp
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    Row(
                        Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { t ->
                            Text(
                                t,
                                color = Color.White.copy(alpha = 0.50f), fontSize = 11.exp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(vm: AppViewModel) {
    val ensAvatars by vm.ensAvatars.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val ensNames by vm.ensNames.collectAsState()
    val status by vm.status.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    /** Contact whose nickname is being edited (web showEditModal). */
    var editing by remember { mutableStateOf<com.pombo.android.data.Contact?>(null) }

    Column(Modifier.fillMaxSize()) {
        PomboHeader(status) {
            // Same treatment as the Explore header's create button (web
            // #header-create-channel-btn): elongated rounded rectangle, not a
            // circle, white plus on the orange wash.
            Box(
                Modifier
                    .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                    .clickableNoRipple { showAdd = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Add, contentDescription = "Add contact", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Text(
            "CONTACTS",
            color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.15.em, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {

        if (contacts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No contacts yet. Add one with their address.", color = PomboColors.TextDim, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts, key = { it.address }) { c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(PomboColors.Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Local nickname wins, then ENS, then the address.
                        LaunchedEffect(c.address) { vm.ensureEns(c.address) }
                        val ens = ensNames[c.address.lowercase()]
                        Avatar(
                            c.address, size = 42.dp, cornerRadiusFraction = 0.5,
                            ensAvatarUrl = ensAvatars[c.address.lowercase()]
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            // Web renderList: nickname (or short address) on top,
                            // then the FULL address in mono at 10px/white-30.
                            Text(
                                c.nickname ?: ens ?: shortAddr(c.address),
                                color = PomboColors.Text, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                c.address,
                                color = Color.White.copy(alpha = 0.30f), fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        // Web: a kebab opening the contact dropdown. "Send DM"
                        // is ours — on the web a contact is reached from the DM
                        // modal, but here this list IS the address book.
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            Icon(
                                Icons.Filled.MoreVert, contentDescription = "Contact options",
                                tint = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.size(20.dp).clickableNoRipple { menuOpen = true }
                            )
                            androidx.compose.material3.DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                modifier = Modifier.background(Color(0xFF111113))
                            ) {
                                ContactMenuItem("Edit Contact", Icons.Outlined.Edit) {
                                    menuOpen = false; editing = c
                                }
                                ContactMenuItem("Send DM", Icons.Outlined.MailOutline) {
                                    menuOpen = false; vm.startDm(c.address)
                                }
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                                        .background(Color.White.copy(alpha = 0.08f))
                                )
                                ContactMenuItem("Remove Contact", Icons.Outlined.Close, danger = true) {
                                    menuOpen = false; vm.removeContact(c.address)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showAdd) AddContactDialog(onDismiss = { showAdd = false }) { addr, name ->
        vm.addContact(addr, name); showAdd = false
    }
    editing?.let { target ->
        EditContactDialog(
            contact = target,
            onDismiss = { editing = null },
            // addContact's re-add semantics (web addTrustedContact): keeps
            // notes/addedAt, refreshes the nickname, renames the DM room.
            onSave = { newName -> vm.addContact(target.address, newName); editing = null }
        )
    }
}

/** Row of the contact kebab dropdown (web contact dropdown items). */
@Composable
private fun ContactMenuItem(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (danger) PomboColors.Danger.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (danger) PomboColors.Danger.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.80f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EditContactDialog(
    contact: com.pombo.android.data.Contact,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var name by remember { mutableStateOf(contact.nickname ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = { Text("Edit contact") },
        text = {
            Column {
                Text(
                    contact.address,
                    color = PomboColors.TextDim, fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 18) name = it },
                    placeholder = { Text("Local name (optional)", color = PomboColors.TextDim) },
                    colors = pomboFieldColors(), singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(name.trim().ifEmpty { null }) }) {
                Text("Save", color = PomboColors.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = PomboColors.TextDim)
            }
        }
    )
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        // Same frame as the Settings cards: a 1px white/8 hairline on a rounded
        // corner. The bare M3 surface is true black on true black, so the dialog
        // had no edge at all — nothing said where it ended.
        modifier = Modifier.border(1.dp, PomboColors.Border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = { Text("Add contact") },
        text = {
            Column {
                // Web placeholder: "0x... or ENS name" — addContact resolves
                // the name before saving, so the stored contact is always an
                // address and stays valid if the ENS record later changes.
                androidx.compose.material3.OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("0x… or ENS name") }, colors = pomboFieldColors(),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nickname (optional)") }, colors = pomboFieldColors(),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onAdd(address, name.ifEmpty { null }) }, enabled = address.isNotBlank()) {
                Text("Add", color = if (address.isNotBlank()) PomboColors.Accent else PomboColors.TextDim, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = PomboColors.TextDim) } }
    )
}

private fun shortAddr(a: String) = if (a.length > 10) a.take(6) + "…" + a.takeLast(4) else a

@Composable
private fun PlaceholderTab(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(title, color = PomboColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(subtitle, color = PomboColors.TextDim, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

/** Chips shared by the collapsed rail and the expanded grid. */
@Composable
private fun ExploreChips(
    category: String,
    privateOnly: Boolean,
    nsfwEnabled: Boolean,
    onCategory: (String) -> Unit,
    onTogglePrivate: () -> Unit
) {
    // Web getTemplate: the NSFW and Adult chips exist only while "Show
    // Sensitive Content" is on, appended after the regular categories.
    val cats = if (nsfwEnabled) EXPLORE_CATEGORIES + listOf("NSFW", "Adult") else EXPLORE_CATEGORIES
    cats.forEach { cat ->
        val active = cat == category && !privateOnly
        Text(
            cat,
            color = if (active) PomboColors.Background else Color.White.copy(alpha = 0.60f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(if (active) Color.White else Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .clickableNoRipple { onCategory(cat) }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
    // Private is a separate axis in the web too (#explore-private-chip):
    // it filters by access type, not by topic, so it toggles on its own.
    Row(
        Modifier
            .background(
                if (privateOnly) Color.White else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(6.dp)
            )
            .clickableNoRipple(onTogglePrivate)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Lock, contentDescription = null,
            tint = if (privateOnly) PomboColors.Background else Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Private",
            color = if (privateOnly) PomboColors.Background else Color.White.copy(alpha = 0.60f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
    }
}
