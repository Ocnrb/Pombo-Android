package com.pombo.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
// Context-menu icons, one per web menu entry.
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.pombo.android.AppViewModel
import com.pombo.android.NetStatus
import com.pombo.android.UiMessage
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.PomboAvatar
import com.pombo.android.ui.theme.PomboColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Composer emoji picker — the web's DEFAULT_EMOJIS (ReactionManager.js:10),
 * all 88, in the same order. We were offering 5 in a single row, which made
 * the picker useless for anything the five did not cover.
 */
private val COMPOSER_EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
    "🙂", "🙃", "😉", "😍", "🥰", "😘", "😋", "😛", "😜", "🤪",
    "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏", "😔", "😢", "😭",
    "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨",
    "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😬", "🙄", "😯",
    "😲", "🥱", "😴", "🤤", "😵", "🤐", "🥴", "🤢", "🤮", "🤧",
    "👍", "👎", "👏", "🙌", "🤝", "🙏", "✌️", "🤞", "🤟", "🤘",
    "👋", "💪", "❤️", "🔥", "⭐", "🎉", "🎊", "💯", "✅", "❌",
    "⚡", "🚀", "💀", "👻", "🤖", "👽", "💩", "🤡"
)

/**
 * Emoji-only detection, mirroring MessageRenderer.isEmojiOnly:
 * null = normal text, "few" = 1-3 emoji, "many" = 4+.
 * Both render without a bubble; "few" is bigger (2rem vs 1.6rem).
 */
private val EMOJI_REGEX =
    Regex("[\\p{So}\\p{Cn}🌀-🗿😀-🙏🚀-🛿☀-➿]")

private fun emojiOnlyKind(text: String): String? {
    if (text.isBlank()) return null
    val stripped = text.replace(Regex("\\s"), "")
    if (stripped.isEmpty()) return null
    val emojis = EMOJI_REGEX.findAll(stripped).count()
    if (emojis == 0) return null
    // Variation selectors and ZWJ are part of emoji sequences, not content.
    val rest = stripped.replace(EMOJI_REGEX, "").replace(Regex("[️‍]"), "")
    if (rest.isNotEmpty()) return null
    return if (emojis <= 3) "few" else "many"
}

/** Reaction palette — same set and order as the web #reaction-picker. */
private val REACTION_EMOJIS = listOf(
    "👍", "👎",
    "❤️", "🔥", "💯", "🚀", "🎉", "🍻", "🤩",
    "😂", "😅",
    "👏", "🙏", "🫡",
    "👀", "🤔", "🤨",
    "😱", "🤯",
    "😢", "😭", "✅", "❌",
    "💀", "💩"
)

@Composable
fun PomboApp(vm: AppViewModel) {
    val hasWallet by vm.hasWallet.collectAsState()
    val current by vm.current.collectAsState()
    val mnemonic by vm.newMnemonic.collectAsState()
    val error by vm.lastError.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(PomboColors.Background)
            .systemBarsPadding()
    ) {
        // Web flow: the app is usable as guest; account creation happens in a modal.
        val ytEmbeds by vm.youtubeEmbeds.collectAsState()
        // Collected here so every file bubble recomposes off one subscription
        // to the transfer state, rather than each opening its own.
        val transfers by vm.fileProgress.collectAsState()
        val uploads by vm.uploadStats.collectAsState()
        val fileTransfers = remember(transfers, uploads) {
            object : com.pombo.android.ui.FileTransfers {
                override fun progressFor(fileId: String) = transfers[fileId]
                override fun uploadsFor(fileId: String) = uploads[fileId]
                override fun isSeeding(fileId: String) = vm.isSeedingFile(fileId)
                override fun onDownload(messageId: String) = vm.downloadFile(messageId)
                override fun onSave(fileId: String, fileName: String) = vm.saveFile(fileId, fileName)
            }
        }
        val storageUploads by vm.storageUploads.collectAsState()
        val storageDownloads by vm.storageDownloads.collectAsState()
        val storageTransfers = remember(storageUploads, storageDownloads) {
            object : com.pombo.android.ui.StorageTransfers {
                override fun uploadFor(transferId: String) = storageUploads[transferId]
                override fun downloadFor(transferId: String) = storageDownloads[transferId]
                override fun completedFor(transferId: String) = vm.storageFileReady(transferId)
                override fun onDownload(messageId: String) = vm.downloadStorageFile(messageId)
                override fun onSave(transferId: String, fileName: String) = vm.saveStorageFile(transferId, fileName)
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.pombo.android.ui.LocalYouTubeEmbedsEnabled provides ytEmbeds,
            com.pombo.android.ui.LocalFileTransfers provides fileTransfers,
            com.pombo.android.ui.LocalStorageTransfers provides storageTransfers
        ) {
            when {
                mnemonic != null -> MnemonicScreen(mnemonic!!, onDone = vm::dismissMnemonic)
                current != null -> ChatScreen(vm)
                else -> MainShell(vm)
            }
        }

        // Errors surface as toasts, like the web — the banner stays only as a
        // fallback for anything that still sets lastError directly.
        val toasts by vm.toasts.collectAsState()
        LaunchedEffect(error) {
            error?.let {
                vm.toast(it, com.pombo.android.ui.ToastKind.ERROR, 5000L)
                vm.clearError()
            }
        }
        // Invites arriving over DM stack with the toasts, in the same slot and
        // the same 380dp column the web uses.
        val invites by vm.pendingInvites.collectAsState()
        // Toast expiry hides the card but keeps the invite pending — it stays
        // reachable from the bell in the Chats header until answered.
        var expiredInvites by remember { mutableStateOf(setOf<String>()) }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 29.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            com.pombo.android.ui.ToastHost(toasts = toasts, onDismiss = vm::dismissToast)
            invites.filter { it.inviteId !in expiredInvites }.forEach { invite ->
                androidx.compose.runtime.key(invite.inviteId) {
                    com.pombo.android.ui.InviteToastCard(
                        invite = invite,
                        onAccept = { vm.acceptInvite(invite) },
                        onDismiss = { vm.dismissInvite(invite.inviteId) },
                        onExpire = { expiredInvites = expiredInvites + invite.inviteId }
                    )
                }
            }
        }

        val incoming by vm.incomingInvite.collectAsState()
        incoming?.let { inv ->
            com.pombo.android.ui.IncomingInviteDialog(
                name = inv.name ?: inv.streamId.substringAfter('/'),
                type = inv.type,
                streamId = inv.streamId,
                onDecline = vm::dismissIncomingInvite,
                onAccept = { vm.acceptIncomingInvite(inv) }
            )
        }

        // Gate entry screen (N-D): a gated channel refused the join — show the
        // on-chain requirement and the pay()/join() actions instead of a toast.
        val gateEntry by vm.gateEntry.collectAsState()
        gateEntry?.let { entry -> GateEntryDialog(vm, entry) }
    }
}

/** N-D gate entry (web #gate-entry-modal): requirement, standing, actions. */
@Composable
internal fun GateEntryDialog(vm: AppViewModel, entry: AppViewModel.GateEntry) {
    val info = entry.info
    fun fmt(raw: String, dec: Int?): String = try {
        java.math.BigDecimal(raw).movePointLeft(dec ?: 0).stripTrailingZeros().toPlainString()
    } catch (e: Exception) { raw }

    val holds = try {
        when (info.mode) {
            GateModes.TOKEN -> java.math.BigInteger(info.balance) >= java.math.BigInteger(info.minBalance)
            GateModes.NFT -> java.math.BigInteger(info.balance).signum() > 0
            else -> false
        }
    } catch (e: Exception) { false }
    val paidMsLeft = info.paidUntil * 1000L - System.currentTimeMillis()
    val subscribed = info.mode == GateModes.PAID && paidMsLeft > 0
    val days = info.durationSeconds / 86_400.0
    val daysLabel = if (days == days.toLong().toDouble()) days.toLong().toString() else "%.1f".format(days)

    // WPOL-priced gates read POL everywhere the user sees a cost — gatePay
    // auto-wraps, plain POL is literally what they spend. By token ADDRESS:
    // any ERC-20 can call itself "WPOL".
    val paySymbol = if (info.token.lowercase() == com.pombo.android.ChannelManager.WRAPPED_NATIVE)
        "POL" else info.tokenSymbol
    // Access stack — the Explore cards' anatomy (verb / value / qualifier)
    val stack: Triple<String, String, String>? = when (info.mode) {
        GateModes.TOKEN -> Triple("Hold", "${fmt(info.minBalance, info.tokenDecimals)} ${info.tokenSymbol}", "in your wallet")
        GateModes.NFT -> Triple("Hold", "${info.tokenSymbol} NFT", "in your wallet")
        GateModes.PAID -> Triple(
            "Subscribe", "${fmt(info.price, info.tokenDecimals)} $paySymbol",
            "per $daysLabel " + if (daysLabel == "1") "day" else "days"
        )
        else -> null
    }
    // One status line, state-coloured: green = access granted, red = blocked
    val okTone = Color(0xFF34D399).copy(alpha = 0.90f)
    val badTone = Color(0xFFF87171).copy(alpha = 0.80f)
    val dimTone = Color.White.copy(alpha = 0.50f)
    val status: Pair<String, Color>? = when (info.mode) {
        GateModes.TOKEN -> {
            val bal = "Balance: ${fmt(info.balance, info.tokenDecimals)} ${info.tokenSymbol}"
            if (holds) "$bal · access granted" to okTone else bal to badTone
        }
        GateModes.NFT ->
            if (holds) "You hold ${info.balance} · access granted" to okTone
            else "You hold none" to badTone
        GateModes.PAID ->
            if (subscribed) {
                val until = java.text.SimpleDateFormat("dd/MM/yy, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(info.paidUntil * 1000L))
                "Active until $until · ${com.pombo.android.core.GateFormat.formatRemaining(paidMsLeft)} left" to okTone
            } else if (info.paidUntil > 0) "Subscription expired" to badTone
            else "No active subscription" to dimTone
        else -> null
    }
    val notes = buildList {
        if (entry.renewal && info.mode == GateModes.PAID) add("Renewing extends from the current end.")
    }

    androidx.activity.compose.BackHandler(onBack = vm::dismissGateEntry)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.80f)).clickableNoRipple { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (entry.renewal) entry.channelName?.let { "Renew $it" } ?: "Renew Subscription"
                else entry.channelName?.let { "Join $it" } ?: "Join Gated Channel",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))
            if (stack != null) {
                val (verb, value, qualifier) = stack
                Text(
                    verb.uppercase(),
                    color = if (info.mode == GateModes.PAID) Color(0xFFF6851B).copy(alpha = 0.70f)
                    else Color.White.copy(alpha = 0.40f),
                    fontSize = 10.sp, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(qualifier, color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
            } else {
                Text(
                    "This is a closed channel — members are added by the owner. " +
                        "Ask the owner to add your address, then check again.",
                    color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            status?.let { (text, tone) ->
                Spacer(Modifier.height(16.dp))
                Text(text, color = tone, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }

            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    notes.joinToString(" "),
                    color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, lineHeight = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            if ((info.mode == GateModes.TOKEN || info.mode == GateModes.NFT) && holds) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Register membership on-chain (1 tx)",
                    color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.clickableNoRipple { vm.gateEntryJoin() }
                )
            }

            Spacer(Modifier.height(24.dp))
            val primary: Pair<String, () -> Unit>? = when {
                // Renewal: pay is always the action — the contract extends
                // from the current end, so paying early never loses days
                entry.renewal && info.mode == GateModes.PAID ->
                    (if (subscribed) "Renew — ${fmt(info.price, info.tokenDecimals)} $paySymbol"
                    else "Pay ${fmt(info.price, info.tokenDecimals)} $paySymbol") to { vm.gateEntryPay(); Unit }
                info.mode == GateModes.PAID && !subscribed ->
                    "Pay ${fmt(info.price, info.tokenDecimals)} $paySymbol" to { vm.gateEntryPay(); Unit }
                subscribed || holds -> "Enter Channel" to { vm.gateEntryEnter(); Unit }
                else -> null
            }
            primary?.let { (label, action) ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .clickableNoRipple(action)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(vm::dismissGateEntry)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }

                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { vm.gateEntryRecheck() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Check Again", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(12.dp)
            .fillMaxWidth()
            .background(PomboColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .border(1.dp, PomboColors.Danger, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = PomboColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            "OK",
            color = PomboColors.Accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 8.dp)
                .clickableNoRipple(onDismiss)
        )
    }
}

/**
 * "Connect Account" modal — exact web spec (index.html #modal-create-wallet):
 * overlay black/80, panel #111113 340dp r16 border white/6%, orange primary card
 * and white import card, discreet "restore" link.
 */
@Composable
fun ConnectAccountDialog(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Connect Account", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("✕", color = Color.White.copy(alpha = 0.30f), fontSize = 15.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .background(PomboColors.Accent, RoundedCornerShape(12.dp))
                    .clickableNoRipple(onCreate)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Create New Account", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Generate a new private key", color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .clickableNoRipple(onImport)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(Color(0xFF09090B), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.AttachFile, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Import Private Key", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Use existing account", color = Color.Black.copy(alpha = 0.90f), fontSize = 13.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "↻ restore", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp,
                    modifier = Modifier.clickableNoRipple(onRestore).padding(4.dp)
                )
            }
        }
    }
}

/**
 * New-account wizard — port of the web's showNewAccountSetupModal
 * (walletFlows.js): Step 1 picks an avatar from a grid of six freshly
 * generated wallets (the avatar is deterministic from the address, so
 * choosing the picture chooses the account), with a shuffle for six more;
 * Step 2 sets the display name and shows the address and private key with
 * the save-your-key warning. The web's password + confirm steps have no
 * Android counterpart — the key lives in the Keystore, not behind a
 * password — so the wizard ends at step 2.
 */
@Composable
fun CreateAccountWizard(vm: AppViewModel, onDismiss: () -> Unit) {
    var wallets by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var showPk by remember { mutableStateOf(false) }
    var shuffleTick by remember { mutableStateOf(0) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(shuffleTick) {
        wallets = emptyList()
        wallets = runCatching { vm.generateWalletCandidates(6) }.getOrElse {
            vm.toast("Could not generate accounts: ${it.message}", com.pombo.android.ui.ToastKind.ERROR)
            onDismiss()
            return@LaunchedEffect
        }
        selected = 0
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // The private key is shown on the backup step — no screenshots, no
        // recording, no recents thumbnail for the whole wizard (M-I1).
        com.pombo.android.ui.SecureFlag()
        Box {
        Column(
            Modifier
                .width(360.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            if (step == 0) {
                // ---- Step 1: Choose Your Avatar ----
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Choose Your Avatar", color = Color.White.copy(alpha = 0.90f),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.Refresh, contentDescription = "Show more avatars",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(22.dp).clickableNoRipple { shuffleTick++ }
                    )
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Column(Modifier.padding(20.dp)) {
                    if (wallets.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = Color.White.copy(alpha = 0.40f), strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    } else {
                        wallets.chunked(3).forEachIndexed { rowIdx, row ->
                            if (rowIdx > 0) Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEachIndexed { colIdx, (address, _) ->
                                    val index = rowIdx * 3 + colIdx
                                    val active = index == selected
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f)
                                            .background(
                                                Color.White.copy(alpha = if (active) 0.08f else 0.03f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .border(
                                                2.dp,
                                                Color.White.copy(alpha = if (active) 0.40f else 0.06f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickableNoRipple { selected = index }
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Avatar(address, size = 72.dp, cornerRadiusFraction = 0.5)
                                        if (active) {
                                            Box(
                                                Modifier.align(Alignment.TopEnd)
                                                    .size(20.dp)
                                                    .background(Color.White, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check, contentDescription = null,
                                                    tint = Color.Black, modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Your avatar is a unique visual identifier tied to your account.",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WizardButton("Cancel", primary = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                    WizardButton(
                        "Continue", primary = true, enabled = wallets.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { step = 1 }
                }
            } else {
                // ---- Step 2: Set Up Account ----
                val (address, privateKey) = wallets[selected]
                Row(
                    Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(address, size = 38.dp, cornerRadiusFraction = 0.5)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Set Up Account", color = Color.White.copy(alpha = 0.90f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Save your key before you continue", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    Text("DISPLAY NAME", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 18) name = it },
                        placeholder = { Text("Your name (optional)", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                        colors = pomboFieldColors(), singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("ADDRESS", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            address, color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("PRIVATE KEY", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showPk) privateKey else "••••••••••••••••••••••••••••••••••••",
                            color = Color.White.copy(alpha = if (showPk) 0.60f else 0.30f),
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showPk) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPk) "Hide key" else "Reveal key",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(18.dp).clickableNoRipple { showPk = !showPk }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                com.pombo.android.ui.SensitiveClipboard.copy(context, privateKey)
                                vm.toast(
                                    "Private key copied — clears from the clipboard in 60s",
                                    com.pombo.android.ui.ToastKind.SUCCESS
                                )
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White.copy(alpha = 0.70f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Private Key", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber, contentDescription = null,
                            tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Save your private key!", color = Color(0xFFFBBF24), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Store it safely offline. This is the only way to recover your account.",
                                color = Color(0xFFFBBF24).copy(alpha = 0.60f), fontSize = 12.sp, lineHeight = 16.sp
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WizardButton("Back", primary = false, modifier = Modifier.weight(1f)) { step = 0 }
                    WizardButton("Create Account", primary = true, modifier = Modifier.weight(1f)) {
                        vm.createAccountFromSetup(privateKey, address, name)
                        onDismiss()
                    }
                }
            }
        }
        // Toast mirror: "Private key copied" fired from inside this Dialog
        // window landed in the main window's host, underneath it.
        val wizardToasts by vm.toasts.collectAsState()
        com.pombo.android.ui.ToastHost(
            toasts = wizardToasts,
            onDismiss = vm::dismissToast,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        )
        }
    }
}

/** Web wizard footer buttons: dim bordered secondary, orange primary. */
@Composable
private fun WizardButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                when {
                    primary && enabled -> PomboColors.Accent
                    primary -> PomboColors.Accent.copy(alpha = 0.40f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp)
            )
            .clickableNoRipple { if (enabled) onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary) Color.White else Color.White.copy(alpha = 0.70f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}

/**
 * "Import Private Key" modal — web walletFlows.js showImportPrivateKeyModal.
 *
 * The web's second step sets a password that encrypts the keystore in
 * localStorage. Android stores the key in EncryptedSharedPreferences, backed by
 * the hardware keystore, so that step has no equivalent here and is omitted.
 */
@Composable
fun ImportKeyDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    // Accepts a 64-hex private key (0x optional) or a recovery phrase.
    val valid = key.trim().let {
        Regex("^(0x)?[a-fA-F0-9]{64}$").matches(it) || it.split(Regex("\\s+")).size >= 12
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(Color(0xFF1A1F2E), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.VpnKey, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Import Private Key", color = Color.White.copy(alpha = 0.90f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("Restore your account", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
                }
                Text("✕", color = Color.White.copy(alpha = 0.40f), fontSize = 15.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(4.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(Modifier.padding(20.dp)) {
                Text("PRIVATE KEY", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    placeholder = {
                        Text("64 hex characters (with or without 0x)",
                            color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Icon(
                            if (reveal) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle visibility",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(20.dp).clickableNoRipple { reveal = !reveal }
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp, color = PomboColors.Text,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                if (key.isNotBlank() && !valid) {
                    Spacer(Modifier.height(8.dp))
                    Text("Invalid format - must be 64 hex characters",
                        color = Color(0xFFF87171), fontSize = 12.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onDismiss)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (valid) Color.White else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple { if (valid) onImport(key.trim()) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Import",
                        color = if (valid) Color.Black else Color.White.copy(alpha = 0.30f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ==================== Onboarding ====================

@Composable
fun OnboardingScreen(vm: AppViewModel, canCancel: Boolean = false) {
    val busy by vm.busy.collectAsState()
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(if (canCancel) "Add account" else "Pombo", color = PomboColors.Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            "Decentralized P2P communication",
            color = PomboColors.TextDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        if (busy) {
            CircularProgressIndicator(color = PomboColors.Accent)
        } else if (!showImport) {
            PrimaryButton("Create new wallet", onClick = vm::createWallet)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showImport = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import existing wallet", color = PomboColors.Text) }
            if (canCancel) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.cancelAddAccount() },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel", color = PomboColors.TextDim) }
            }
        } else {
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("Private key or recovery phrase") },
                colors = pomboFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Import", enabled = importText.isNotBlank()) { vm.importWallet(importText) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showImport = false },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back", color = PomboColors.TextDim) }
        }
    }
}

@Composable
fun MnemonicScreen(mnemonic: String, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Save your recovery phrase", color = PomboColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "It's the only way to recover your account. Write it down somewhere safe — it's never sent anywhere.",
            color = PomboColors.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            mnemonic,
            color = PomboColors.Accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(PomboColors.SurfaceHigh, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
                .padding(20.dp)
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("I've saved it", onClick = onDone)
    }
}

/** Web new-channel modal: Open / Protected / Closed / Gated / Paid, same copy.
 *  The last three share `id = "gated"` — one PomboGate clone per channel; the
 *  tab only picks the MODE (Closed = NONE allowlist, Gated = TOKEN/NFT
 *  holding, Paid = subscription), mirroring the web's handleCreate. */
enum class ChannelKind(val id: String, val label: String, val blurb: String) {
    OPEN(
        "public", "Open",
        "Open channel that anyone can discover and join. Messages are visible to all participants."
    ),
    PROTECTED(
        "password", "Protected",
        "Password protected channel. Only users with the correct password can join and read messages."
    ),
    CLOSED(
        "gated", "Closed",
        "Private channel with on-chain verified membership. Each member is authorized by account address."
    ),
    GATED(
        "gated", "Gated",
        "Access follows an on-chain asset: anyone holding the token or NFT can join, read and write. " +
            "Selling the asset cuts new access; messages already published stay."
    ),
    PAID(
        "gated", "Paid",
        "Subscription channel: members pay a fixed price in an ERC-20 token for a period of access. " +
            "Payments go directly to your wallet; renewing extends from the current end."
    )
}

/** PomboGate.Mode — ABI order, never reorder. */
object GateModes {
    const val NONE = 0; const val TOKEN = 1; const val NFT = 2; const val PAID = 3
}

/**
 * Quick-pick tokens (Polygon PoS mainnet), mirroring web CONFIG.gate
 * .tokenPresets. POL diverges by context: 0x…1010 is the native coin's
 * system contract — balanceOf mirrors the native balance so balance GATES
 * work, but it has no usable transferFrom, so the PAID side prices in WPOL
 * (gatePay auto-wraps the payer's shortfall from plain POL).
 */
val GATE_TOKEN_PRESETS = listOf(
    Triple("pol", "POL", "0x0000000000000000000000000000000000001010"),
    Triple("usdc", "USDC", "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359"),
    Triple("data", "DATA", "0x3a9a81d576d83ff21f26f325066054540720fc34")
)
val PAY_TOKEN_PRESETS = listOf(
    Triple("pol", "POL", "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270"),
    Triple("usdc", "USDC", "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359"),
    Triple("data", "DATA", "0x3a9a81d576d83ff21f26f325066054540720fc34")
)

val CHANNEL_LANGUAGES = listOf(
    "en" to "English", "pt" to "Português", "es" to "Español", "fr" to "Français",
    "de" to "Deutsch", "it" to "Italiano", "zh" to "中文", "ja" to "日本語",
    "ko" to "한국어", "ru" to "Русский", "ar" to "العربية", "other" to "Other"
)

val CHANNEL_CATEGORIES = listOf(
    "politics" to "Politics", "news" to "News", "tech" to "Tech & AI",
    "crypto" to "Crypto & Web3", "finance" to "Business & Finance", "science" to "Science",
    "gaming" to "Gaming", "entertainment" to "Entertainment", "sports" to "Sports",
    "education" to "Education", "comedy" to "Comedy & Memes", "general" to "General",
    // index.html:727-728 — the web offers these two; omitting them meant an
    // Android user could not classify a channel the web can.
    "nsfw" to "NSFW 18+", "adult" to "Adult 18+"
)

/** Everything the web's create form collects. */
data class NewChannel(
    val name: String,
    val type: String,
    val password: String?,
    val exposure: String,
    val readOnly: Boolean,
    val description: String,
    val language: String,
    val category: String,
    val classification: String,
    val members: List<String>,
    val storageProvider: String,
    val customStorageAddress: String?,
    val storageDays: Int,
    // N-D gate params (type == "gated"); raw units as decimal strings.
    val gateMode: Int = GateModes.NONE,
    val gateToken: String? = null,
    val gateMinBalance: String? = null,
    val gatePrice: String? = null,
    val gateDurationSeconds: Long? = null
)

/** Quick-pick token chips (N-D): presets + Custom. */
@Composable
private fun TokenPresetRow(
    presets: List<Triple<String, String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (presets.map { it.first to it.second } + ("custom" to "Custom")).forEach { (value, label) ->
            val active = selected == value
            Box(
                Modifier.weight(1f)
                    .background(
                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = if (active) 0.10f else 0.05f), RoundedCornerShape(8.dp))
                    .clickableNoRipple { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Create Channel — the web's #new-channel-modal adapted to the phone: the
 * desktop type sidebar becomes a row of tabs, everything else keeps the same
 * fields, copy and conditional logic (visible-only fields, member list for
 * Closed channels, classification).
 */
@Composable
internal fun CreateChannelDialog(vm: AppViewModel, onDismiss: () -> Unit, onCreate: (NewChannel) -> Unit) {
    var kind by remember { mutableStateOf(ChannelKind.OPEN) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var category by remember { mutableStateOf("general") }
    var classification by remember { mutableStateOf("personal") }
    var membersText by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(false) }
    var storageProvider by remember { mutableStateOf("streamr") }
    var customStorage by remember { mutableStateOf("") }
    var storageDays by remember { mutableStateOf(180f) }
    var showPassword by remember { mutableStateOf(false) }

    // N-D gate fields (Gated/Paid tabs)
    var gateAsset by remember { mutableStateOf("token") }      // token | nft
    var gatePreset by remember { mutableStateOf("pol") }       // pol | usdc | data | custom
    var gateTokenCustom by remember { mutableStateOf("") }
    var gateMinBalanceText by remember { mutableStateOf("") }
    var paidPreset by remember { mutableStateOf("usdc") }
    var paidTokenCustom by remember { mutableStateOf("") }
    var paidPriceText by remember { mutableStateOf("") }
    var paidDurationDays by remember { mutableStateOf("30") }

    // One line per address, exactly like the web (ChannelModalsUI.js:567).
    // Splitting on any whitespace and silently dropping non-matching entries
    // meant a typo'd address just vanished with no feedback; here the invalid
    // ones are counted and surfaced instead.
    val memberLines = remember(membersText) {
        membersText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val evmRegex = remember { Regex("^0x[a-fA-F0-9]{40}$") }
    val members = remember(memberLines) { memberLines.filter { evmRegex.matches(it) } }
    val invalidMembers = memberLines.size - members.size

    val customStorageValid = storageProvider != "custom" || evmRegex.matches(customStorage.trim())

    // Estimated on-chain cost, refreshed once when the dialog opens (web:
    // ChannelModalsUI.updateGasEstimates on modal show).
    val gasCosts by vm.gasCosts.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGas() }

    // Validation and the balance pre-flight both run on submit, like the web's
    // handleCreateChannel — the button is never disabled there.
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Pair<String, com.pombo.android.ui.ToastKind>?>(null) }
    var lowBalance by remember { mutableStateOf<AppViewModel.CostCheck.Low?>(null) }
    var confirmingPassword by remember { mutableStateOf(false) }

    fun buildSpec() = NewChannel(
        name = name.trim(),
        type = kind.id,
        password = password.ifBlank { null },
        // Closed channels are always hidden — the toggle is not even shown.
        exposure = if (visible && kind != ChannelKind.CLOSED) "visible" else "hidden",
        readOnly = readOnly,
        description = description.trim(),
        language = language,
        category = category,
        classification = classification,
        members = members,
        storageProvider = storageProvider,
        customStorageAddress = customStorage.trim().ifBlank { null },
        storageDays = storageDays.toInt()
    )

    // Low-balance confirm fires AFTER async gate resolution — remember the
    // resolved spec so "Create anyway" does not rebuild it without the params.
    var pendingSpec by remember { mutableStateOf<NewChannel?>(null) }

    /**
     * Gated/Paid tabs (N-D): resolve preset/custom token, probe it on-chain
     * (an address without a working balanceOf would mint a gate that fails
     * checkAccess for everyone, forever) and convert human amounts with the
     * token's REAL decimals. Returns null after setting `notice`.
     */
    suspend fun resolveGateSpec(): NewChannel? {
        if (kind != ChannelKind.GATED && kind != ChannelKind.PAID) return buildSpec()
        val warn = com.pombo.android.ui.ToastKind.WARNING
        val isPaid = kind == ChannelKind.PAID
        val presets = if (isPaid) PAY_TOKEN_PRESETS else GATE_TOKEN_PRESETS
        val presetKey = if (isPaid) paidPreset else gatePreset
        val custom = (if (isPaid) paidTokenCustom else gateTokenCustom).trim()
        // NFT gates are always a custom collection address (presets are ERC-20)
        val useCustom = presetKey == "custom" || (!isPaid && gateAsset == "nft")
        val token = if (useCustom) custom.lowercase()
            else presets.first { it.first == presetKey }.third
        if (!evmRegex.matches(token)) {
            notice = "Enter a valid token contract address (0x…)" to warn
            return null
        }
        val meta = try {
            vm.gateTokenBalance(token)
            vm.gateTokenMeta(token)
        } catch (e: Exception) {
            notice = "That address does not look like a token contract on Polygon" to warn
            return null
        }
        fun parseUnits(amount: String, dec: Int): java.math.BigInteger? = try {
            java.math.BigDecimal(amount.trim()).movePointRight(dec)
                .toBigIntegerExact().takeIf { it.signum() > 0 }
        } catch (e: Exception) { null }
        return when {
            isPaid -> {
                val dec = meta.second
                if (dec == null) {
                    notice = "The payment token must be an ERC-20 contract" to warn
                    return null
                }
                val price = parseUnits(paidPriceText, dec)
                if (price == null) {
                    notice = "Enter a price greater than zero" to warn
                    return null
                }
                val days = paidDurationDays.trim().toIntOrNull()?.takeIf { it >= 1 }
                if (days == null) {
                    notice = "Enter a subscription period of at least 1 day" to warn
                    return null
                }
                buildSpec().copy(
                    gateMode = GateModes.PAID, gateToken = token,
                    gatePrice = price.toString(), gateDurationSeconds = days.toLong() * 86_400L)
            }
            gateAsset == "nft" -> buildSpec().copy(gateMode = GateModes.NFT, gateToken = token)
            else -> {
                val minBal = parseUnits(gateMinBalanceText, meta.second ?: 0)
                if (minBal == null) {
                    notice = "Enter a minimum balance greater than zero" to warn
                    return null
                }
                buildSpec().copy(
                    gateMode = GateModes.TOKEN, gateToken = token,
                    gateMinBalance = minBal.toString())
            }
        }
    }

    /** Balance pre-flight, then create. */
    fun runPreflight() {
        scope.launch {
            val spec = resolveGateSpec() ?: return@launch
            pendingSpec = spec
            when (val check = vm.checkSpendCost(kind.id)) {
                is AppViewModel.CostCheck.Blocked ->
                    notice = check.message to com.pombo.android.ui.ToastKind.ERROR
                is AppViewModel.CostCheck.Low -> lowBalance = check
                AppViewModel.CostCheck.Ok -> onCreate(spec)
            }
        }
    }

    fun submit() {
        val warn = com.pombo.android.ui.ToastKind.WARNING
        // Same order and copy as the web.
        if (storageProvider == "custom" && !customStorageValid) {
            notice = "Enter a valid EVM address (0x followed by 40 hex characters)." to warn
            return
        }
        if (name.isBlank()) { notice = "Please enter a channel name" to warn; return }
        if (kind == ChannelKind.PROTECTED && password.isBlank()) {
            notice = "Please enter a password" to warn
            return
        }
        // A typo in a channel password is unrecoverable — it is the encryption
        // key, not a credential that can be reset — so re-type it first.
        if (kind == ChannelKind.PROTECTED) { confirmingPassword = true; return }
        runPreflight()
    }

    // NOT an androidx Dialog: a Dialog is its own window, and on a real phone
    // that window is laid out beyond the display bounds, so its footer fell off
    // the bottom of the screen. It also hid the app's toasts and double-padded
    // the status bar. Rendering the panel in the activity's own window makes
    // the insets the same ones the rest of the app uses.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(PomboColors.Background)
            // Swallow taps so nothing behind the panel reacts.
            .clickableNoRipple { }
    ) {
        // safeDrawing = system bars + cutout + IME, in one place. On a phone
        // with gesture navigation and no gesture bar the bottom inset is 0, so
        // the footer ends up flush against the screen edge — top it up to a
        // minimum clearance without double-padding devices that do inset.
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(bottom = (12.dp - bottomInset).coerceAtLeast(0.dp))
        ) {
            // Header — fixed 52dp height with the title pushed to the right,
            // same as Channel Details, so it hugs the status bar instead of
            // sitting in a padded band.
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(20.dp).clickableNoRipple(onDismiss)
                )
                Text("Create Channel", color = Color.White.copy(alpha = 0.90f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(20.dp)
            ) {
                // Type tabs (web sidebar). Five entries since N-D — a scroll
                // row of chips instead of equal weights, or "Protected" clips.
                SectionLabel("Type")
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChannelKind.entries.forEach { k ->
                        val active = k == kind
                        Box(
                            Modifier
                                .background(
                                    if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple { kind = k }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                k.label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.60f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Type blurb
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(kind.blurb, color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, lineHeight = 20.sp)
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel("Name")
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("Channel name", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )

                if (kind == ChannelKind.PROTECTED) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Password")
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        placeholder = { Text("Channel password", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                        singleLine = true, shape = RoundedCornerShape(12.dp),
                        // Web has a reveal toggle (#channel-password-toggle):
                        // this password can never be recovered, so letting the
                        // user check it before committing it on-chain matters.
                        visualTransformation = if (showPassword)
                            androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            Icon(
                                if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.40f),
                                modifier = Modifier.size(18.dp)
                                    .clickableNoRipple { showPassword = !showPassword }
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                        colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Everyone needs this password to read the channel.",
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (kind == ChannelKind.GATED) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Asset Type")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("token" to "Token", "nft" to "NFT").forEach { (value, label) ->
                            val active = gateAsset == value
                            Box(
                                Modifier.weight(1f)
                                    .background(
                                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickableNoRipple { gateAsset = value }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label, color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    if (gateAsset == "token") {
                        SectionLabel("Token")
                        TokenPresetRow(GATE_TOKEN_PRESETS, gatePreset) { gatePreset = it }
                        if (gatePreset == "custom") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = gateTokenCustom, onValueChange = { gateTokenCustom = it },
                                placeholder = { Text("0x…", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp) },
                                singleLine = true, shape = RoundedCornerShape(12.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp, color = PomboColors.Text,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            when (gatePreset) {
                                "pol" -> "Gates on the member's native POL balance."
                                "custom" -> "ERC-20 contract on Polygon."
                                else -> "${GATE_TOKEN_PRESETS.first { it.first == gatePreset }.second} on Polygon."
                            },
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Minimum Balance")
                        OutlinedTextField(
                            value = gateMinBalanceText, onValueChange = { gateMinBalanceText = it },
                            placeholder = { Text("e.g. 100", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Balance a member must hold to read and write.",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        SectionLabel("Collection Contract")
                        OutlinedTextField(
                            value = gateTokenCustom, onValueChange = { gateTokenCustom = it },
                            placeholder = { Text("0x…", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp, color = PomboColors.Text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "ERC-721 collection on Polygon — holding any token grants access.",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                if (kind == ChannelKind.PAID) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Payment Token")
                    TokenPresetRow(PAY_TOKEN_PRESETS, paidPreset) { paidPreset = it }
                    if (paidPreset == "custom") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = paidTokenCustom, onValueChange = { paidTokenCustom = it },
                            placeholder = { Text("0x… (e.g. USDC)", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp, color = PomboColors.Text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        when (paidPreset) {
                            "pol" -> "Priced in Wrapped POL (WPOL) — plain POL is wrapped automatically at payment."
                            "custom" -> "ERC-20 token subscribers pay with, on Polygon."
                            else -> "Subscribers pay in ${PAY_TOKEN_PRESETS.first { it.first == paidPreset }.second}."
                        },
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Price")
                            OutlinedTextField(
                                value = paidPriceText, onValueChange = { paidPriceText = it },
                                placeholder = { Text("e.g. 5", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                                singleLine = true, shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Period (days)")
                            OutlinedTextField(
                                value = paidDurationDays, onValueChange = { paidDurationDays = it },
                                singleLine = true, shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (kind == ChannelKind.CLOSED) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Members")
                    OutlinedTextField(
                        value = membersText, onValueChange = { membersText = it },
                        placeholder = {
                            Text("0x… addresses, one per line", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                        },
                        shape = RoundedCornerShape(12.dp), maxLines = 4,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp, color = PomboColors.Text,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        buildString {
                            append("${members.size} valid address${if (members.size == 1) "" else "es"} — each is granted access on-chain.")
                            if (invalidMembers > 0) append("  $invalidMembers line${if (invalidMembers == 1) "" else "s"} will be ignored.")
                        },
                        color = if (invalidMembers > 0) PomboColors.Danger.copy(alpha = 0.80f)
                            else Color.White.copy(alpha = 0.30f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Classification")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("personal" to "Personal", "community" to "Community").forEach { (value, label) ->
                            val active = classification == value
                            Box(
                                Modifier.weight(1f)
                                    .background(
                                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickableNoRipple { classification = value }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Text(
                        "For organizing your channels locally.",
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                Spacer(Modifier.height(16.dp))

                // Closed channels are never discoverable, so the web hides this
                // section outright rather than showing a toggle that cannot be
                // honoured (ChannelModalsUI.js:184-190).
                if (kind != ChannelKind.CLOSED) {
                    ToggleRow(
                        label = "VISIBLE",
                        hint = "Channel appears in Explore",
                        checked = visible,
                        onToggle = { visible = !visible }
                    )

                    if (visible) {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Description")
                        OutlinedTextField(
                            value = description, onValueChange = { if (it.length <= 200) description = it },
                            placeholder = { Text("What's this channel about?", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                            shape = RoundedCornerShape(12.dp), maxLines = 3,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Language")
                        ChipPicker(CHANNEL_LANGUAGES, language) { language = it }
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Category")
                        ChipPicker(CHANNEL_CATEGORIES, category) { category = it }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Read-only: grants public subscribe WITHOUT publish. Missing
                // this meant every Android-created channel granted public
                // publish, with no way to make a broadcast channel.
                ToggleRow(
                    label = "READ-ONLY",
                    hint = "Only you can post; everyone else can read",
                    checked = readOnly,
                    onToggle = { readOnly = !readOnly }
                )

                // ---- Message storage (web #storage-section) ----
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                Spacer(Modifier.height(16.dp))
                SectionLabel("Message Storage")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("streamr", "Pombo", Icons.Outlined.Public),
                        Triple("custom", "Custom", Icons.Outlined.Code)
                    ).forEach { (value, label, icon) ->
                        val active = storageProvider == value
                        Row(
                            Modifier.weight(1f)
                                .background(
                                    if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp, Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple { storageProvider = value }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (storageProvider == "streamr") {
                    // Web #streamr-address-display: a 10px label over a bordered
                    // read-only box holding the FULL address, wrapped.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE CLUSTER", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        com.pombo.android.ChannelManager.STORAGE_NODE,
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                } else {
                    // Same box as the Pombo address above (web #custom-storage-
                    // address: `px-3 py-2 text-xs font-mono`). An OutlinedTextField
                    // forces a 56dp minimum, which made this field tower over it.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE CLUSTER", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    val badAddress = customStorage.isNotBlank() && !customStorageValid
                    androidx.compose.foundation.text.BasicTextField(
                        value = customStorage, onValueChange = { customStorage = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp, color = PomboColors.Text,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (badAddress) PomboColors.Danger.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (customStorage.isEmpty()) Text(
                                "0x...", color = Color.White.copy(alpha = 0.20f), fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            inner()
                        }
                    )
                    if (customStorage.isNotBlank() && !customStorageValid) {
                        Text(
                            "Enter a valid EVM address (0x followed by 40 hex characters).",
                            color = PomboColors.Danger.copy(alpha = 0.80f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                // Retention slider — web #storage-days-input, range 1..365.
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MESSAGE RETENTION", color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        retentionLabel(storageDays.toInt()),
                        color = PomboColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                androidx.compose.material3.Slider(
                    value = storageDays,
                    onValueChange = { storageDays = it },
                    valueRange = 1f..365f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = PomboColors.Accent,
                        activeTrackColor = PomboColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // Web puts scale ticks under the slider, not a hint sentence.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1 day", "6 months", "1 year").forEach {
                        Text(it, color = Color.White.copy(alpha = 0.60f), fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // Footer — web: `flex items-center justify-between px-6 py-4
            // border-t border-white/5`, with the estimate on the left. Its
            // Cancel button is `hidden md:block`, i.e. desktop-only: on the
            // phone the back arrow in the header is the way out.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Est. cost:", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        gasCosts?.formattedFor(kind.id) ?: "calculating...",
                        color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
                // Web `bg-[#F6851B]/15 border-[#F6851B]/40 rounded-lg px-5
                // py-2.5` — never disabled, submit() does the validating.
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { submit() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Create Channel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Validation and pre-flight feedback live INSIDE the dialog window —
        // the app's toast host is in the main window and would be covered.
        DialogToast(notice) { notice = null }
        if (confirmingPassword) ConfirmPasswordDialog(
            expected = password,
            onCancel = { confirmingPassword = false },
            onConfirmed = { confirmingPassword = false; runPreflight() }
        )
        lowBalance?.let { low ->
            ConfirmPopup(
                title = "Low POL balance",
                message = "Your balance (${low.balance}) is close to the estimated cost " +
                    "(${low.estimate}). If gas spikes, the transaction may fail. Continue anyway?",
                confirmLabel = "Create anyway",
                onConfirm = { lowBalance = null; onCreate(pendingSpec ?: buildSpec()) },
                onCancel = { lowBalance = null }
            )
        }
    }
}

/**
 * "Create DM Inbox" modal — web index.html #dm-inbox-setup-modal: one-time
 * setup explanation, storage provider (Pombo cluster / custom node), the
 * 1..365-day retention slider and the estimated cost in the footer. The
 * balance pre-flight itself runs in AppViewModel.setupDmInbox, like the
 * channel dialog's does before creation.
 */
@Composable
fun CreateDmInboxDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onCreate: (storageProvider: String, customStorageAddress: String?, storageDays: Int) -> Unit
) {
    var storageProvider by remember { mutableStateOf("streamr") }
    var customStorage by remember { mutableStateOf("") }
    var storageDays by remember { mutableStateOf(180f) }
    val customStorageValid = Regex("^0x[a-fA-F0-9]{40}$").matches(customStorage.trim())
    val gasCosts by vm.gasCosts.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGas() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.MailOutline, contentDescription = null,
                        tint = PomboColors.Accent, modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Create DM Inbox", color = Color.White.copy(alpha = 0.90f),
                    fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                Text(
                    "✕", color = Color.White.copy(alpha = 0.40f), fontSize = 15.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(4.dp)
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline, contentDescription = null,
                        tint = PomboColors.Accent, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.90f), fontWeight = FontWeight.SemiBold)) {
                                    append("One-time setup")
                                }
                                append(" — Creating your DM inbox requires a small gas fee. Once created, there are no additional costs.")
                            },
                            color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Anyone with your Pombo/Ethereum address can send you direct messages. " +
                                "You can also message any address that has a DM inbox.",
                            color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 19.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                Spacer(Modifier.height(16.dp))

                // ---- Message storage (same controls as the channel dialog) ----
                Text(
                    "MESSAGE STORAGE", color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("streamr", "Pombo", Icons.Outlined.Public),
                        Triple("custom", "Custom", Icons.Outlined.Code)
                    ).forEach { (value, label, icon) ->
                        val active = storageProvider == value
                        Row(
                            Modifier.weight(1f)
                                .background(
                                    if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp, Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple { storageProvider = value }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (storageProvider == "streamr") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE NODE", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        com.pombo.android.ChannelManager.STORAGE_NODE,
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE NODE", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    val badAddress = customStorage.isNotBlank() && !customStorageValid
                    androidx.compose.foundation.text.BasicTextField(
                        value = customStorage, onValueChange = { customStorage = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp, color = PomboColors.Text,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (badAddress) PomboColors.Danger.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (customStorage.isEmpty()) Text(
                                "0x...", color = Color.White.copy(alpha = 0.20f), fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            inner()
                        }
                    )
                    if (badAddress) {
                        Text(
                            "Enter a valid EVM address (0x followed by 40 hex characters).",
                            color = PomboColors.Danger.copy(alpha = 0.80f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MESSAGE RETENTION", color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        retentionLabel(storageDays.toInt()),
                        color = PomboColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                androidx.compose.material3.Slider(
                    value = storageDays,
                    onValueChange = { storageDays = it },
                    valueRange = 1f..365f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = PomboColors.Accent,
                        activeTrackColor = PomboColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1 day", "6 months", "1 year").forEach {
                        Text(it, color = Color.White.copy(alpha = 0.60f), fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Footer — estimate left, action right (web bg-[#0a0a0a] strip).
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("Est. cost:", color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        gasCosts?.formattedFor("dmInbox") ?: "calculating...",
                        color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .clickableNoRipple {
                            if (storageProvider == "custom" && !customStorageValid) return@clickableNoRipple
                            onCreate(
                                storageProvider,
                                customStorage.trim().ifEmpty { null },
                                storageDays.toInt()
                            )
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Create Inbox", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }
}

/** Web ChannelModalsUI.updateStorageDaysDisplay — days collapse into months. */
private fun retentionLabel(days: Int): String = when {
    days == 1 -> "1 day"
    days < 30 -> "$days days"
    days < 365 -> (days / 30.0).roundToInt().let { if (it == 1) "1 month" else "$it months" }
    else -> "1 year"
}

/**
 * Toast rendered inside a Dialog's own window, styled like the app's ToastHost
 * (#16161B panel, 29dp from the top). Self-dismisses after the 6s the web gives
 * its balance errors; tapping closes it sooner.
 */
@Composable
private fun DialogToast(notice: Pair<String, com.pombo.android.ui.ToastKind>?, onDismiss: () -> Unit) {
    if (notice == null) return
    val (message, kind) = notice
    LaunchedEffect(notice) {
        kotlinx.coroutines.delay(6000)
        onDismiss()
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .padding(top = 29.dp)
                .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .clickableNoRipple(onDismiss)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (kind == com.pombo.android.ui.ToastKind.WARNING) Icons.Outlined.WarningAmber
                else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = kind.color, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(message, color = Color.White.copy(alpha = 0.90f), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * Re-type gate for a Protected channel's password. The password IS the
 * encryption key: nobody — not even the owner — can recover the history if it
 * was mistyped, so the only safe moment to catch it is before creation.
 */
@Composable
private fun ConfirmPasswordDialog(expected: String, onCancel: () -> Unit, onConfirmed: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock, contentDescription = null,
                    tint = PomboColors.Accent, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Confirm password", color = Color.White.copy(alpha = 0.90f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Type the channel password again.",
                color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = typed,
                    onValueChange = { typed = it; mismatch = false },
                    singleLine = true,
                    visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                    modifier = Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            if (mismatch) PomboColors.Danger.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (typed.isEmpty()) Text(
                            "Channel password", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp
                        )
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (reveal) "Hide password" else "Show password",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(20.dp).clickableNoRipple { reveal = !reveal }
                )
            }
            if (mismatch) {
                Spacer(Modifier.height(8.dp))
                Text("Passwords do not match", color = PomboColors.Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple(onCancel)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { if (typed == expected) onConfirmed() else mismatch = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Confirm", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** Web NotificationUI.showConfirmToast — a warning that needs a decision. */
@Composable
private fun ConfirmPopup(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WarningAmber, contentDescription = null,
                    tint = com.pombo.android.ui.ToastKind.WARNING.color, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White.copy(alpha = 0.90f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple(onCancel)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.90f), RoundedCornerShape(8.dp))
                        .clickableNoRipple(onConfirm)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text(confirmLabel, color = Color(0xFF0A0A0A), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** Label + hint on the left, web-style pill switch on the right. */
@Composable
private fun ToggleRow(label: String, hint: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label, color = Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
            )
            Text(hint, color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
        }
        Box(
            Modifier
                .width(36.dp).height(20.dp)
                .background(
                    if (checked) PomboColors.Accent else Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(10.dp)
                )
                .clickableNoRipple(onToggle)
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(16.dp)
                    .background(
                        if (checked) Color.White else Color.White.copy(alpha = 0.30f),
                        CircleShape
                    )
            )
        }
    }
}

/** Horizontal chip picker used for language and category. */
@Composable
private fun ChipPicker(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                color = if (active) PomboColors.Background else Color.White.copy(alpha = 0.60f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .clickableNoRipple { onPick(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
internal fun JoinChannelDialog(onDismiss: () -> Unit, onJoin: (String, String?) -> Unit) {
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = { Text("Join a channel") },
        text = {
            Column {
                Text("Paste the channel stream ID (e.g. 0x…/name-1)", color = PomboColors.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = id, onValueChange = { id = it },
                    label = { Text("Stream ID") }, colors = pomboFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (if any)") },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(id, password.ifEmpty { null }) }, enabled = id.isNotBlank()) {
                Text("Join", color = if (id.isNotBlank()) PomboColors.Accent else PomboColors.TextDim, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PomboColors.TextDim) } }
    )
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) PomboColors.Background else PomboColors.Text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(if (selected) PomboColors.Accent else PomboColors.SurfaceHigh, RoundedCornerShape(50))
            .border(1.dp, if (selected) PomboColors.Accent else PomboColors.Border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

// ==================== Chat ====================

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun ChatScreen(vm: AppViewModel) {
    val channel by vm.current.collectAsState()
    val messages by vm.messages.collectAsState()
    val reactions by vm.reactions.collectAsState()
    val online by vm.onlineCount.collectAsState()
    val onlineUsers by vm.onlineUsers.collectAsState()
    val netStatus by vm.status.collectAsState()
    val presenceReady by vm.presenceReady.collectAsState()
    val typing by vm.typingFrom.collectAsState()
    var onlineOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }

    // The back gesture is navigation, not "leave the app". Everything here is
    // Compose state rather than a back stack, so each screen has to say what
    // back means for it — otherwise the system default (finish the activity)
    // wins and a swipe from the chat minimises Pombo instead of returning to
    // the list. Same action as the header's arrow.
    androidx.activity.compose.BackHandler(enabled = !showInfo) { vm.closeChannel() }
    var replyTarget by remember { mutableStateOf<UiMessage?>(null) }
    val listState = rememberLazyListState()
    val ch = channel ?: return

    val isPreview by vm.isPreview.collectAsState()
    val hasMoreHistory by vm.hasMoreHistory.collectAsState()
    val loadingHistory by vm.loadingHistory.collectAsState()
    val loadingInitial by vm.initialLoad.collectAsState()
    val waitingForKeys by vm.waitingForKeys.collectAsState()
    val paidStatus by vm.paidStatus.collectAsState()

    // NATIVE reverse-layout chat (deliberate divergence from the web's
    // top-down DOM): the LazyColumn runs with reverseLayout, so index 0 is the
    // NEWEST message, drawn at the bottom. Opening a channel lands on the
    // newest with no positioning dance, and loading older history APPENDS
    // items past the far end of the list — the reading position cannot move,
    // by construction: no index math, no re-anchoring, no drift when the seam
    // groups merge. (Item-index anchoring was tried twice and lost both times:
    // the prepend and the re-measure collapse into one snapshot emission, and
    // same-sender pages merge into the seam group, growing it in place.)
    LaunchedEffect(ch.messageStreamId) { listState.scrollToItem(0) }
    // Follow the conversation when a NEW message lands. Keying on the last id
    // (not the count) keeps history appends from yanking the view down.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // Infinite scroll: the web uses an IntersectionObserver on a top sentinel;
    // in the reversed list the sentinel is the LAST item, so "near the top"
    // means its index range is entering the viewport.
    val nearTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 1 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        }
    }
    // An empty render auto-loads too (web `_autoLoadIfContentShort`): the initial
    // resend stays optimistic about `hasMoreHistory`, so only the bounded
    // windowed paginate can prove a channel is actually empty. Without this a
    // brand-new channel sits on "Loading messages..." forever.
    val isDm = channel?.type == "dm"
    val searchOlder by vm.dmSearchOlder.collectAsState()
    // Web `_autoLoadIfContentShort`: keep fetching only while the content
    // cannot scroll at all (scrollHeight <= clientHeight there).
    val contentShort by remember {
        derivedStateOf { !listState.canScrollForward && !listState.canScrollBackward }
    }
    LaunchedEffect(nearTop, contentShort, hasMoreHistory, loadingHistory, loadingInitial, messages.isEmpty(), searchOlder) {
        // The reversed list is born at index 0 = the NEWEST message, with the
        // sentinel a full page away — `nearTop` cannot fire during opening, so
        // the chain-load that once parked whole channels at their oldest
        // message is structurally impossible; no landing hand-shake needed.
        if (hasMoreHistory && !loadingHistory && !loadingInitial &&
            (messages.isEmpty() || contentShort || nearTop)
        ) {
            // A DM whose last window was empty waits for an explicit tap; the
            // inbox is shared across peers, so empty weeks are normal and
            // auto-scanning them would be a chain of slow resends.
            if (isDm) {
                if (!searchOlder) vm.loadMoreDmHistory()
            } else {
                vm.loadMoreHistory()
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val pins by vm.pins.collectAsState()
        val hidden by vm.hiddenIds.collectAsState()
        // Moderation is gated on the real on-chain permission, not on who
        // created the channel — see ChannelManager.canModerate. Channel Details
        // reads the same flag, so both surfaces agree on what this account can do.
        val canModerate by vm.canModerate.collectAsState()
        val myAddr = vm.address.collectAsState().value

        if (showInfo) ChannelSettingsSheet(vm, ch, canModerate) { showInfo = false }

        // Messages (hidden ones are dropped for non-owners; greyed for the owner).
        // While the initial resend is running the list stays empty: edits,
        // deletes (P0 or P1) and moderation (-3/P0) all land during that window,
        // and painting first would flash messages that are about to disappear.
        // No admin bypass: the web hides a moderated message for everyone,
        // the admin included (ChatAreaUI filters on hiddenIds with no owner
        // check). Exempting the owner made the person who hid the message the
        // only one who still saw it.
        // Banned senders are filtered too (web ChatAreaUI.js:543): banning was
        // only revoking on-chain publish, so everything the member had already
        // posted stayed on screen — the moderation action looked like it had
        // done nothing to the existing conversation.
        val banned by vm.bannedMembers.collectAsState()
        // Both of these are remembered on their real inputs. The filter used to
        // run unmemoized, allocating a new list on every recomposition — which
        // meant the `remember(visible)` key below never matched and the whole
        // grouping pass re-ran every frame, on the main thread, for the entire
        // conversation. That was a large part of why scrolling felt heavier
        // here than in the PWA.
        val visible = remember(messages, hidden, banned, loadingInitial) {
            if (loadingInitial) emptyList()
            else messages.filter { it.id !in hidden && it.sender.lowercase() !in banned }
        }
        val groups = remember(visible) { buildMessageGroups(visible) }
        // Only one message shows its action triggers at a time (web: .message-active).
        var activeId by remember { mutableStateOf<String?>(null) }
        // Scrolling dismisses them — the web hides the triggers as soon as the
        // list moves, and leaving them stuck to a message that has scrolled
        // away is worse than not showing them at all.
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) activeId = null
        }
        val contacts by vm.contacts.collectAsState()

        // Jump-to-message, shared by the pinned banner and reply quotes
        // (web ChatAreaUI.scrollToMessage): scroll the group into view and
        // pulse it for 1.5s so the eye can find it after the jump.
        var highlightId by remember { mutableStateOf<String?>(null) }
        val jumpScope = rememberCoroutineScope()
        val scrollToMessage: (String) -> Unit = { targetId ->
            val gi = groups.indexOfFirst { g -> g.items.any { it.id == targetId } }
            if (gi >= 0) {
                jumpScope.launch {
                    // Reversed list: the newest group is item 0 and each group
                    // occupies two items (group, separator). Landing on the
                    // group item aligns its bottom edge with the viewport
                    // bottom, which brings the group into view.
                    listState.animateScrollToItem((groups.size - 1 - gi) * 2)
                    highlightId = targetId
                    kotlinx.coroutines.delay(1500)
                    highlightId = null
                }
            }
        }

        // Dismissals are per-channel and session-scoped, and are dropped when
        // the pin itself disappears so that unpin-then-repin surfaces the
        // banner again (web _reconcileDismissals).
        var dismissedPins by remember(ch.messageStreamId) { mutableStateOf(emptySet<String>()) }
        val livePinIds = pins.map { it.targetId }.toSet()
        LaunchedEffect(livePinIds) { dismissedPins = dismissedPins intersect livePinIds }
        // NEWEST first: `pins` arrives in pin order (oldest first), so the banner
        // used to sit on the stalest pin forever. Reversed, it opens on the most
        // recent one and each dismissal walks back one pin through history.
        val shownPins = pins.filter { it.targetId !in dismissedPins }.asReversed()

        // Channel header
        Row(
            // `#chat-header { height: 3.5rem; padding: 0 0.5rem }` — a fixed
            // 56px, not a padding-derived height.
            Modifier.fillMaxWidth().height(56.dp)
                .background(PomboColors.Surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::closeChannel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PomboColors.Text)
            }
            // Channel image (admin stream) wins over the generated avatar.
            val chImage by vm.channelImage.collectAsState()
            if (chImage != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(chImage) {
                        android.graphics.BitmapFactory.decodeByteArray(chImage, 0, chImage!!.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                )
            } else if (ch.type == "dm" && ch.peerAddress != null) {
                // Same rule as the chat list: a DM header shows the peer.
                Avatar(
                    ch.peerAddress, size = 36.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = vm.ensAvatars.collectAsState().value[ch.peerAddress.lowercase()]
                )
            } else {
                Avatar(ch.messageStreamId, size = 36.dp, cornerRadiusFraction = 0.5)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // `#current-channel-name { font-size: 1rem }` on mobile. Tapping
                // the name opens Channel Details (web opens it from the kebab, but
                // the name is the natural mobile target).
                Text(
                    ch.name, color = PomboColors.Text, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.clickableNoRipple { showInfo = true }
                )
                // Web subtitle: an icon-only channel-type glyph, then "· N Online"
                // (the type label text lives in Channel Details, not here).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dim = Color.White.copy(alpha = 0.30f)
                    ChannelTypeIcon(ch.type, ch.readOnly, dim, size = 13.dp)
                    // This row must never be empty, or the header grows once
                    // presence lands and everything above it jumps. The web
                    // never had the problem: its count element is always in the
                    // DOM and just gets `textContent = users.length`. So the
                    // count shows even at zero, and while the node is still
                    // connecting the same slot reads "Connecting…".
                    // Two independent things can be pending: the node itself,
                    // and this channel's subscriptions. Opening a channel while
                    // already connected is the common case, so the per-channel
                    // flag is the one that actually covers the gap.
                    val connecting = netStatus != NetStatus.CONNECTED || !presenceReady
                    if (ch.type == "dm") {
                        // A DM has one counterpart: show the peer's live status
                        // next to the envelope (their presence heartbeat within
                        // the online timeout = Online, else Offline).
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (connecting) "Connecting…" else if (online > 0) "Online" else "Offline",
                            color = dim, fontSize = 13.sp
                        )
                    } else {
                        Spacer(Modifier.width(6.dp))
                        Text("·", color = dim, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Box {
                            Text(
                                if (connecting) "Connecting…" else "$online Online",
                                color = dim, fontSize = 13.sp,
                                modifier = Modifier.clickableNoRipple {
                                    if (!connecting) onlineOpen = !onlineOpen
                                }
                            )
                            // Web: dropdown anchored under the count (#online-users-list):
                            // #1e1e1e, white/10 border, r-xl, w-56, max-h-48, scroll.
                            OnlineUsersDropdown(
                                expanded = onlineOpen,
                                users = onlineUsers,
                                me = myAddr?.lowercase(),
                                vm = vm,
                                onDismiss = { onlineOpen = false }
                            )
                        }
                    }
                }
            }
            // Preview mode: "+ Join" adds the channel to My Channels
            // (web index.html #join-channel-btn — accent 15% fill, 30% border).
            if (isPreview) {
                Row(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .clickable { vm.joinPreview() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PomboColors.Text, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Join", color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(4.dp))
            }
            // Invite icon (web #invite-users-btn) — lives in the chat header,
            // not in Channel Details. Shown for every non-DM channel and hidden
            // in preview, exactly like the web (ChannelViewUI.js:124-129).
            var inviteOpen by remember { mutableStateOf(false) }
            if (ch.type != "dm" && !isPreview) {
                IconButton(onClick = { inviteOpen = true }) {
                    Icon(
                        Icons.Outlined.PersonAddAlt,
                        contentDescription = "Invite users",
                        tint = Color.White.copy(alpha = 0.30f)
                    )
                }
            }
            if (inviteOpen) {
                val inviteClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val link = remember(ch.messageStreamId) { vm.inviteLink(ch) }
                val inviteToasts by vm.toasts.collectAsState()
                com.pombo.android.ui.InviteToChannelDialog(
                    channelName = ch.name,
                    channelType = ch.type,
                    inviteLink = link,
                    onCopy = {
                        inviteClipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                        vm.toast("Link copied to clipboard!", com.pombo.android.ui.ToastKind.SUCCESS)
                    },
                    onSend = { addr -> vm.sendChannelInvite(addr, ch) { inviteOpen = false } },
                    onDismiss = { inviteOpen = false },
                    toasts = inviteToasts,
                    onDismissToast = vm::dismissToast
                )
            }
            // Channel options (web: mobile kebab on the right)
            var chMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { chMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Channel options", tint = Color.White.copy(alpha = 0.30f))
                }
                // Same visual language as the message context menu: #16161b,
                // white-10% border, radius 12, compact rows with icons. The
                // Material default was oversized and icon-less.
                DropdownMenu(
                    expanded = chMenu,
                    onDismissRequest = { chMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .widthIn(min = 180.dp)
                ) {
                    val headerClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    // The panel this opens is titled "Channel Details" in the
                    // web (index.html:1414) and here; the entry said "Channel
                    // settings", so the label and its destination disagreed.
                    com.pombo.android.ui.ContextMenuItem(
                        "Channel Details", Icons.Outlined.Settings
                    ) { chMenu = false; showInfo = true }
                    // Web DropdownManager copy-stream-id: the id was only
                    // reachable by opening Channel Details and selecting it.
                    com.pombo.android.ui.ContextMenuItem(
                        "Copy Channel ID", Icons.Outlined.ContentCopy
                    ) {
                        chMenu = false
                        headerClipboard.setText(androidx.compose.ui.text.AnnotatedString(ch.messageStreamId))
                        vm.toast("Channel ID copied", com.pombo.android.ui.ToastKind.SUCCESS)
                    }
                    // Web shows this entry only when the channel has pins.
                    if (shownPins.isNotEmpty()) com.pombo.android.ui.ContextMenuItem(
                        "Pinned", Icons.Filled.PushPin,
                        iconTint = Color(0xFFFBBF24)
                    ) {
                        chMenu = false
                        dismissedPins = emptySet()   // re-show, then jump to it
                        scrollToMessage(pins.last().targetId)   // the newest pin, as in the banner
                    }
                    // Nothing to leave while previewing — the channel isn't saved.
                    if (!isPreview) {
                        com.pombo.android.ui.ContextMenuDivider()
                        com.pombo.android.ui.ContextMenuItem(
                            "Leave channel", Icons.AutoMirrored.Filled.Logout,
                            iconTint = Color(0xFFF87171), labelColor = Color(0xFFF87171)
                        ) { chMenu = false; vm.removeChannel(ch.messageStreamId) }
                    }
                }
            }
        }
        // Web `#chat-header { border-b border-white/[0.04] }` — a faint 1px rule
        // between the header and the message body.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.04f)))

        // Paid-subscription strip (web .subscription-banner): static above the
        // messages — it states the channel's access state, so it must not
        // float over content or scroll away. Amber warning is dismissible per
        // viewing session; the expired strip is not.
        var subWarnDismissed by remember(ch.messageStreamId) { mutableStateOf(false) }
        paidStatus?.let { ps ->
            val msLeft = ps.paidUntil * 1000L - System.currentTimeMillis()
            val expired = msLeft <= 0 && !ps.accessNow
            val warning = msLeft in 1 until com.pombo.android.core.GateFormat.WARNING_MS
            if (expired || (warning && !subWarnDismissed)) {
                val tint = if (expired) Color(0xFFF87171) else Color(0xFFFBBF24)
                Row(
                    Modifier.fillMaxWidth()
                        .background(tint.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expired) "Subscription expired — new messages stay locked until you renew"
                        else "Subscription ends in ${com.pombo.android.core.GateFormat.formatRemaining(msLeft)}",
                        color = tint, fontSize = 13.sp, lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .clickableNoRipple { vm.renewSubscription() }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Renew", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (!expired) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Close, contentDescription = "Dismiss subscription warning",
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp).clickableNoRipple { subWarnDismissed = true }
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(tint.copy(alpha = 0.15f)))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Web renderMessages: while any loading signal is still live the
            // area shows a centred spinner; only once everything is quiescent
            // and still empty does it say "No messages yet".
            if (visible.isEmpty()) {
                val terminalEmpty = !loadingInitial && !loadingHistory && !hasMoreHistory
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // A lapsed subscription and "admin offline" are identical
                    // at the key layer (refusals are silent) — the chain-read
                    // paid status decides which empty state this is.
                    val subExpired = paidStatus?.let {
                        it.paidUntil * 1000L <= System.currentTimeMillis() && !it.accessNow
                    } == true
                    if (terminalEmpty && waitingForKeys && subExpired) {
                        Text(
                            "Your subscription has expired",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Messages stay locked until you renew — renewing extends from the current end",
                            color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .background(PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                                .border(1.dp, PomboColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickableNoRipple { vm.renewSubscription() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Renew subscription", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (terminalEmpty && waitingForKeys) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.30f),
                            strokeWidth = 2.dp, modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Waiting for channel keys…",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Another member needs to be online to share them",
                            color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp
                        )
                    } else if (terminalEmpty) {
                        Text(
                            "No messages yet. Start the conversation!",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White.copy(alpha = 0.40f),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Loading messages...", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                    }
                }
            }
            LazyColumn(
                state = listState,
                // Chat-native orientation: item 0 (the newest group) sits at
                // the bottom and older items stack upward, so history loads
                // append past the far end and never move the viewport. Items
                // are emitted newest-first below to match.
                reverseLayout = true,
                // A reversed LazyColumn defaults to Arrangement.Bottom, which
                // is why a nearly empty room glued its one message to the
                // composer with the screen blank above it. The arrangement only
                // has any effect while the content is SHORTER than the
                // viewport, so asking for Top costs nothing once there is
                // enough history to scroll — and the reverse order is
                // untouched: the block still reads oldest-to-newest downward,
                // it is just parked at the top like the web's top-down list.
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .fillMaxSize()
                    // A tap anywhere that a bubble does not consume dismisses
                    // the triggers. Without this they stayed up until another
                    // message was tapped, so tapping empty space did nothing.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { activeId = null })
                    },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp,
                    // Headroom only while the banner is actually showing.
                    top = if (shownPins.isNotEmpty()) 72.dp else 12.dp,
                    bottom = 12.dp
                )
            ) {
                // Emission is NEWEST-FIRST: with reverseLayout, index 0 draws
                // at the bottom, so each group is followed by the separator
                // that visually sits ABOVE it, and the history sentinel is the
                // LAST item — the visual result is identical to the old
                // top-down list.
                //
                // The separator/spacer is its OWN list item, so a group's item
                // bounds are exactly the group. They used to share one item,
                // which meant the sticky avatar's "don't go above the top"
                // clamp was measured against the item — leading space included
                // — and the avatar could ride up past the bubble it belongs to.
                for (gi in groups.indices.reversed()) {
                    val group = groups[gi]
                    val groupKey = group.items.first().id
                    item(key = groupKey) {
                    MessageGroup(
                        group = group,
                        reactions = reactions,
                        myAddress = myAddr,
                        canModerate = canModerate,
                        channelCreator = ch.createdBy,
                        listState = listState,
                        // Two items per group counted from the newest end
                        // (group, then its separator above).
                        itemIndex = (groups.size - 1 - gi) * 2,
                        hidden = hidden,
                        pins = pins,
                        activeId = activeId,
                        onActivate = { id -> activeId = if (activeId == id) null else id },
                        isContact = { addr -> contacts.any { it.address.equals(addr, ignoreCase = true) } },
                        onReact = { id, emoji, add -> vm.toggleReaction(id, emoji, add) },
                        onReply = { m -> replyTarget = m; activeId = null },
                        onEdit = { id, text -> vm.editMessage(id, text) },
                        onDelete = { id -> vm.deleteMessage(id) },
                        onPin = { id, pin -> vm.pinMessage(id, pin) },
                        onHide = { id, hide -> vm.hideMessage(id, hide) },
                        onBan = { addr -> vm.banMember(addr) },
                        onAddContact = { addr -> vm.addContact(addr, null) },
                        onSendDm = { addr -> vm.startDm(addr) },
                        onRemoveContact = { addr -> vm.removeContact(addr) },
                        // Blocking is DM-scoped: it leaves the conversation, so
                        // it only appears where the conversation is the peer.
                        onBlock = if (ch.type == "dm") ({ vm.blockPeer(ch.messageStreamId) }) else null,
                        onJumpTo = scrollToMessage,
                        highlightId = highlightId
                    )
                    }
                    item(key = "sep-$groupKey") {
                        // Date separator chip when the day changes (web: "Today" pill)
                        val prevGroup = groups.getOrNull(gi - 1)
                        val prevTs = prevGroup?.items?.last()?.timestamp
                        if (prevTs == null || !sameDay(prevTs, group.items.first().timestamp)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    dayLabel(group.items.first().timestamp),
                                    color = Color.White.copy(alpha = 0.40f),
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .padding(vertical = 6.dp)
                                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            // Web spacing scale: ping-pong 14dp between sides,
                            // same-side-different-sender 20dp.
                            Spacer(Modifier.height(if (prevGroup.mine == group.mine) 20.dp else 14.dp))
                        }
                    }
                }
                // History sentinel — the last item, drawn at the very top:
                // spinner while a page loads, otherwise the end-of-history
                // marker once the stream is exhausted. NOT emitted while the
                // list is empty: it would be the only item, and when the first
                // page lands the LazyColumn follows its key to the far end,
                // dragging the viewport away from the newest message. (The
                // searchOlder tap target must survive an empty DM window.)
                if (groups.isNotEmpty() || searchOlder) item(key = "history-top") {
                    when {
                        // The initial-load spinner is drawn over the whole area,
                        // so the sentinel stays empty during that phase.
                        loadingInitial -> Unit
                        // DM window came back empty — the web offers this rather
                        // than scanning older weeks on its own.
                        searchOlder -> Row(
                            Modifier.fillMaxWidth()
                                .clickableNoRipple { vm.loadMoreDmHistory() }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Search, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.30f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "No messages found in this time range — Search older",
                                color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp
                            )
                        }
                        loadingHistory -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White.copy(alpha = 0.30f),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Loading older messages...",
                                color = Color.White.copy(alpha = 0.30f),
                                fontSize = 14.sp
                            )
                        }
                        !hasMoreHistory && visible.isNotEmpty() -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "— beginning of conversation —",
                                color = Color.White.copy(alpha = 0.12f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Pinned banner floats over the messages (web .pinned-banner: glass
            // white 6%, border 8%, radius 16, inset 10/12).
            if (shownPins.isNotEmpty()) {
                val first = shownPins.first()
                // Prefer the ENS name over the raw address, like everywhere else.
                val ensNames by vm.ensNames.collectAsState()
                LaunchedEffect(first.sender) { vm.ensureEns(first.sender) }
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        // Tapping the banner jumps to the pinned message — the
                        // banner was inert, so a pin could be seen but not found.
                        .clickableNoRipple { scrollToMessage(first.targetId) }
                        // The web gets its frost from backdrop-filter: blur(20px).
                        // Compose has no backdrop blur, so a near-opaque dark base
                        // carries the same "floating glass" read without letting
                        // the scrolled text behind show through as noise.
                        .background(Color(0xFF0E0E10).copy(alpha = 0.88f), RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.PushPin, contentDescription = null,
                        tint = PomboColors.Accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    // `.pinned-banner-body { line-height: 1.25 }` — 13px text on
                    // a 16px line. Compose's default line height (~1.45) made the
                    // banner noticeably taller than the web's, and the extra
                    // height ate the room the text could have used.
                    Column(Modifier.weight(1f)) {
                        if (first.sender.isNotEmpty()) {
                            Text(
                                ensNames[first.sender.lowercase()] ?: shortAddress(first.sender),
                                color = PomboColors.Accent,
                                fontSize = 13.sp, lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(1.dp))   // .pinned-banner-text margin-top
                        }
                        Text(
                            first.text.ifEmpty { "Pinned message" },
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp, lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    if (shownPins.size > 1) {
                        Text(
                            "+${shownPins.size - 1}",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // Dismiss reveals the pin before this one, or hides the banner
                    // once they run out. It does NOT unpin — unpinning is a
                    // moderation action for everyone, this is only "stop showing
                    // me this one".
                    Icon(
                        Icons.Filled.Close, contentDescription = "Dismiss pinned message",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickableNoRipple { dismissedPins = dismissedPins + first.targetId }
                    )
                }
            }
        }

        // Typing indicator. Kept mounted so the wave can animate in and out
        // instead of the row popping the composer up and down.
        val typingEns by vm.ensNames.collectAsState()
        // Rebuilt on every recomposition, not frozen when the signal arrived:
        // the ENS name usually lands a moment after the first "typing".
        val typingLabel = typingSentence(typing, typingEns)
        // Held past the clear, or the line would blank out mid-fade-out.
        var lastTypingLabel by remember { mutableStateOf("") }
        LaunchedEffect(typingLabel) { typingLabel?.let { lastTypingLabel = it } }
        AnimatedVisibility(
            visible = typing.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
        ) {
            TypingIndicator(lastTypingLabel)
        }

        // Reply bar above the composer (web #reply-bar)
        replyTarget?.let { target ->
            Row(
                Modifier.fillMaxWidth().background(PomboColors.Surface)
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(2.dp).height(30.dp).background(Color.White.copy(alpha = 0.15f)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        target.ensName ?: target.senderName ?: shortAddress(target.sender),
                        color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (target.isImage) "[Media]" else target.text.take(100),
                        color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, maxLines = 1
                    )
                }
                IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel reply", tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp))
                }
            }
        }

        // A read-only channel grants public subscribe but not publish, so a
        // send here fails at the network layer with nothing shown to the user.
        // The web disables the field and swaps the placeholder
        // (PreviewModeUI.js:390-392); we only ever rendered a label.
        // Expired subscription cuts the composer too: the transport would
        // still accept the publish (sticky membership), but honest receivers
        // drop it at ingest — writing into that void is a trap, not a feature.
        val subExpired = paidStatus?.let {
            it.paidUntil * 1000L <= System.currentTimeMillis() && !it.accessNow
        } == true
        val canPost = (!ch.readOnly || ch.createdBy?.equals(myAddr, ignoreCase = true) == true) &&
            !subExpired
        ChatComposer(
            canPost = canPost,
            disabledPlaceholder = if (subExpired) "Subscription expired — renew to write"
                else "This channel is read-only",
            onTyping = { vm.notifyTyping() },
            onPickImage = { vm.sendImage(it) },
            onPickVideo = { vm.sendVideo(it) },
            onPickFile = { vm.sendFile(it) },
            onPickStorageFile = { vm.sendStorageFile(it) },
            onSend = { text ->
                vm.sendMessage(text, replyTarget?.let {
                    com.pombo.android.ReplyRef(it.id, it.sender, it.senderName ?: it.ensName, it.text)
                })
                replyTarget = null
            }
        )
    }
}

/**
 * "Bob and 0x1234 are typing".
 *
 * Two names are worth spelling out; past that the line would grow without
 * bound and start eating the composer, so the tail collapses into a count.
 * Whoever has no ENS name and no nickname shows as an address prefix — long
 * enough to tell two strangers apart, short enough not to dominate the line.
 */
private fun typingSentence(
    peers: List<com.pombo.android.ChannelManager.TypingPeer>,
    ensNames: Map<String, String>
): String? {
    if (peers.isEmpty()) return null
    val names = peers.map { p ->
        ensNames[p.address.lowercase()] ?: p.nickname ?: p.address.take(6)
    }
    return when (names.size) {
        1 -> "${names[0]} is typing"
        2 -> "${names[0]} and ${names[1]} are typing"
        else -> "${names[0]} and ${names.size - 1} others are typing"
    }
}

/**
 * The typing line: a three-dot wave and one dim sentence, nothing else — no
 * chip, no border. It sits directly above the composer, where any surface of
 * its own would read as a second input bar.
 *
 * One infinite driver phase-shifted per dot rather than three independent
 * animations: that is what makes it read as a travelling wave instead of three
 * blinking dots, and it stays in phase forever. Each dot lifts, brightens and
 * grows on the same curve, and the positive half of a sine leaves a natural
 * rest between passes.
 */
@Composable
private fun TypingIndicator(name: String) {
    val wave = rememberInfiniteTransition(label = "typing")
    val phase by wave.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "typingPhase"
    )
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { i ->
                // Each dot runs the same cycle, a fifth of a period behind the
                // one before it.
                val local = (phase - i * 0.16f + 1f) % 1f
                val bump = kotlin.math.sin(local * 2 * Math.PI).toFloat().coerceAtLeast(0f)
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer {
                            translationY = -bump * 4.dp.toPx()
                            val s = 0.82f + 0.30f * bump
                            scaleX = s; scaleY = s
                            alpha = 0.40f + 0.60f * bump
                        }
                        .background(PomboColors.Accent, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            name, color = PomboColors.TextDim, fontSize = 13.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * The input bar, split out of ChatScreen with the draft state owned here: a
 * keystroke used to invalidate the whole ChatScreen scope (header, pins, the
 * list call site and every lambda in it re-executed per key press), which on a
 * phone read as typing lag. Now a key press recomposes only this Row.
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun ChatComposer(
    canPost: Boolean,
    disabledPlaceholder: String = "This channel is read-only",
    onTyping: () -> Unit,
    onPickImage: (android.net.Uri) -> Unit,
    onPickVideo: (android.net.Uri) -> Unit,
    onPickFile: (android.net.Uri) -> Unit,
    onPickStorageFile: (android.net.Uri) -> Unit,
    onSend: (String) -> Unit
) {
    // State-based field, NOT the legacy value/onValueChange one: only the
    // TextFieldState pipeline advertises accepted MIME types to the IME via
    // contentReceiver — with the old field, Gboard greyed its GIFs out with
    // "can't insert this content here".
    val input = rememberTextFieldState()
    LaunchedEffect(Unit) {
        var first = true
        androidx.compose.runtime.snapshotFlow { input.text.toString() }.collect {
            if (first) first = false else onTyping()
        }
    }
    // Image opens a tray of ours (ImagePickerSheet) instead of a launcher: the
    // system photo picker is a closed Activity and cannot carry the camera
    // tile, so that grid has to be drawn here. Video and File keep the system
    // pickers — nothing about them wants a camera.
    var imageTrayOpen by remember { mutableStateOf(false) }
    if (imageTrayOpen) {
        com.pombo.android.ui.ImagePickerSheet(
            onPick = onPickImage,
            onDismiss = { imageTrayOpen = false }
        )
    }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickVideo(uri) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPickFile(uri) }
    // Storage Cluster (Persistent File Sharing): one document picker for both the
    // File and Video "Storage Cluster" entries — the storage path is type-agnostic.
    val storageFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPickStorageFile(uri) }
    // Web #message-input-container, but on the app's pure-black surface: a 1px
    // hairline on TOP (border-t border-white/[0.04]) mirroring the header's
    // bottom rule, and p-3 pb-4.
    Column(Modifier.fillMaxWidth().background(PomboColors.Surface)) {
    androidx.compose.material3.HorizontalDivider(
        color = Color.White.copy(alpha = 0.04f), thickness = 1.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Web input bar: attach (paperclip) + emoji, then the text field.
        // DMs get it too — the transport is the same, only the sealing
        // differs (ECDH envelope instead of the channel password).
        // Web attach menu parity (index.html attach-menu): File and Video are
        // parents that expand a transport submenu — Mesh live, Persistent
        // greyed out with "(soon)" — while Image picks directly. A hover
        // flyout does not exist on touch, so the submenu replaces the menu
        // body in place, with a back row.
        var attachOpen by remember { mutableStateOf(false) }
        var attachSubmenu by remember { mutableStateOf<String?>(null) }
        Box {
            IconButton(
                onClick = { attachSubmenu = null; attachOpen = true },
                enabled = canPost,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = attachOpen,
                onDismissRequest = { attachOpen = false; attachSubmenu = null },
                modifier = Modifier.background(Color(0xFF16161B))
            ) {
                when (attachSubmenu) {
                    null -> {
                        DropdownMenuItem(
                            text = { Text("File  ›", color = Color.White) },
                            onClick = { attachSubmenu = "file" }
                        )
                        DropdownMenuItem(
                            text = { Text("Video  ›", color = Color.White) },
                            onClick = { attachSubmenu = "video" }
                        )
                        DropdownMenuItem(
                            text = { Text("Image", color = Color.White) },
                            onClick = { attachOpen = false; imageTrayOpen = true }
                        )
                    }
                    "file" -> {
                        DropdownMenuItem(
                            text = { Text("‹  File", color = Color.White.copy(alpha = 0.55f)) },
                            onClick = { attachSubmenu = null }
                        )
                        DropdownMenuItem(
                            text = { Text("P2P", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                filePicker.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Storage", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                storageFilePicker.launch(arrayOf("*/*"))
                            }
                        )
                    }
                    "video" -> {
                        DropdownMenuItem(
                            text = { Text("‹  Video", color = Color.White.copy(alpha = 0.55f)) },
                            onClick = { attachSubmenu = null }
                        )
                        DropdownMenuItem(
                            text = { Text("P2P", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                videoPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly
                                ))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Storage", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                storageFilePicker.launch(arrayOf("video/*"))
                            }
                        )
                    }
                }
            }
        }
        var emojiOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { emojiOpen = true }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.SentimentSatisfied, contentDescription = "Emoji", tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = emojiOpen,
                onDismissRequest = { emojiOpen = false },
                modifier = Modifier.background(Color(0xFF16161B))
            ) {
                // A FlowRow, NOT a LazyVerticalGrid: DropdownMenu already
                // wraps its content in a vertical scroller, so nesting a
                // second vertical scroller inside it measures against an
                // infinite height and hangs the main thread (this ANR'd).
                // 88 items render eagerly for nothing anyway.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .width(304.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    COMPOSER_EMOJIS.forEach { e ->
                        Text(
                            e, fontSize = 22.sp,
                            // The menu stays open on pick, so several
                            // emoji can be added in a row.
                            modifier = Modifier
                                .clickableNoRipple { input.edit { append(e) } }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
        val fieldInteraction = remember { MutableInteractionSource() }
        val fieldFocused by fieldInteraction.collectIsFocusedAsState()
        androidx.compose.foundation.text.BasicTextField(
            state = input,
            enabled = canPost,
            interactionSource = fieldInteraction,
            lineLimits = TextFieldLineLimits.MultiLine(1, 4),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = PomboColors.Text, fontSize = 16.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
            // Rebuilt OutlinedTextField look (pomboFieldColors + 24dp shape).
            decorator = { inner ->
                // Web #message-input: bg-white/[0.05], rounded-2xl, border-white/[0.08].
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .border(
                            1.dp,
                            // Focus is a faint GREY lift, not an orange rule —
                            // the web uses focus:ring-1 ring-white/10.
                            if (fieldFocused) Color.White.copy(alpha = 0.16f) else PomboColors.Border,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.text.isEmpty()) {
                        Text(
                            if (canPost) "Message…" else disabledPlaceholder,
                            color = PomboColors.TextDim, fontSize = 16.sp
                        )
                    }
                    inner()
                }
            },
            // Gboard GIFs/stickers (IME commitContent) and image paste land
            // here instead of being silently dropped (web InputUI.js handles
            // paste the same way). Anything that is not an image passes
            // through to the normal text pipeline.
            modifier = Modifier.weight(1f).let { base ->
                if (!canPost) base
                else base.contentReceiver { transferable ->
                    if (!transferable.hasMediaType(
                            androidx.compose.foundation.content.MediaType.Image
                        )
                    ) return@contentReceiver transferable
                    transferable.consume { item ->
                        val uri = item.uri
                        if (uri != null) { onPickImage(uri); true } else false
                    }
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        // Web #send-message-btn: no button chrome at all, just the outline
        // paper-plane in the accent amber, sized to stand level with the input
        // field (16sp line + 10dp padding top/bottom). One single appearance —
        // there is no filled/empty state, the glyph never changes.
        // A Box, NOT an IconButton: Material3 clips IconButton's content to a
        // circle, which sliced the plane's nose and tail corners off once the
        // glyph grew to fill the box.
        Box(
            Modifier
                .size(40.dp)
                // Stays clickable while the channel is writable and simply no-ops
                // on an empty box — nothing about the button reflects whether
                // there is text.
                .clickableNoRipple {
                    if (canPost && input.text.isNotBlank()) {
                        onSend(input.text.toString())
                        input.clearText()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // tint = Unspecified so the vector keeps its own stroke colour
            // instead of being flattened by Icon's tint.
            Icon(
                SendPlaneIcon,
                contentDescription = "Send",
                tint = Color.Unspecified,
                modifier = Modifier.size(36.dp)
            )
        }
    }
    }
}

/**
 * The web's send glyph (index.html #send-message-btn): the Heroicons outline
 * paper-plane, stroke 1.8 with round caps — Material's Send is a solid plane and
 * read as a different icon next to the web.
 *
 * Outline only, one appearance — the button has no filled/empty state.
 *
 * The viewport is cropped to the drawing's real bounds instead of the source
 * 24x24 box. The plane only spans x 3.269..21.485 / y 3.126..20.876, so a 24-box
 * wastes a quarter of the icon on empty margin and the glyph can never grow to
 * the height of the input field next to it.
 *
 * PLANE_VIEW is the drawing's width (18.216) plus 0.8 of stroke room on each
 * side, kept square so Modifier.size() does not distort it.
 */
private const val PLANE_VIEW = 19.816f

private val SendPlaneIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "SendPlane",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = PLANE_VIEW, viewportHeight = PLANE_VIEW
    ).apply {
        // Shift the path into the cropped viewport; the y offset also re-centres
        // the (slightly shorter) glyph inside the squared-off box.
        addGroup(translationX = -2.469f, translationY = -2.093f)
        addPath(
            // Same geometry as the web's path, but with the arc flags written
            // out ("0 0 1" instead of the compressed "0 01"): Compose's
            // PathParser mis-reads the packed form and the plane came out
            // deformed.
            pathData = androidx.compose.ui.graphics.vector.PathParser()
                .parsePathString(
                    "M6 12 L3.269 3.126 A59.768 59.768 0 0 1 21.485 12 " +
                        "A59.77 59.77 0 0 1 3.27 20.876 L5.999 12 Z M6 12 H13.5"
                ).toNodes(),
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
            // The web's 1.8 restated in the cropped viewport (1.8 * 19.816/24),
            // so the rule keeps its weight relative to the drawing. Left at the
            // raw 1.8 it reads far too heavy; thinner and the outline looks
            // washed out rather than orange.
            strokeLineWidth = 1.486f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        clearGroup()
    }.build()
}

/**
 * Channel-type glyph for the chat-header subtitle (web #current-channel-info,
 * icon-only). native = Ethereum diamond, password = lock, public = globe, dm =
 * envelope; a read-only channel adds a megaphone, like the web's roIcon.
 */
@Composable
private fun ChannelTypeIcon(type: String, readOnly: Boolean, tint: Color, size: Dp = 13.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (type) {
            "native", "gated" -> EthereumIcon(tint, Modifier.size(size))
            "password" -> Icon(Icons.Outlined.Lock, null, tint = tint, modifier = Modifier.size(size))
            "dm" -> Icon(Icons.Outlined.MailOutline, null, tint = tint, modifier = Modifier.size(size))
            else -> Icon(Icons.Outlined.Public, null, tint = tint, modifier = Modifier.size(size))
        }
        if (readOnly && type != "dm") {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Campaign, null, tint = tint, modifier = Modifier.size(size))
        }
    }
}

/**
 * Who is online — a dropdown anchored under the "N Online" count in the chat
 * header (web #online-users-list): #1e1e1e, white/10 border, r-xl, 224dp wide,
 * capped height with scroll, no title. Each row: avatar, ENS name (green check)
 * / nickname / short address, "(you)" for self.
 */
@Composable
private fun OnlineUsersDropdown(
    expanded: Boolean,
    users: List<com.pombo.android.ChannelManager.OnlineUser>,
    me: String?,
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val ensAvatars by vm.ensAvatars.collectAsState()
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .width(224.dp).heightIn(max = 240.dp)
    ) {
        if (users.isEmpty()) {
            Text(
                "No one online", color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        users.forEach { u ->
            LaunchedEffect(u.address) { vm.ensureEns(u.address) }
            val ens = vm.ensNameFor(u.address)
            val isMe = u.address.equals(me, ignoreCase = true)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.pombo.android.ui.Avatar(
                    address = u.address, size = 32.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = ensAvatars[u.address.lowercase()]
                )
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (ens != null) {
                        Text("✓", color = Color(0xFF4ADE80), fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        ens ?: u.nickname ?: (u.address.take(6) + "…" + u.address.takeLast(4)),
                        color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp,
                        fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        Text("(you)", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Channel Details — the web's mobile presentation of #channel-settings-modal:
 * a full-screen sheet that slides in from the right, with NO sidebar/tabs.
 * Info and Storage flow inline, then a nav list for Members / Moderation /
 * Delete (`#channel-mobile-unified`).
 */
@Composable
private fun ChannelSettingsSheet(vm: AppViewModel, channel: Channel, canModerate: Boolean, onDismiss: () -> Unit) {
    var sub by remember { mutableStateOf<ChannelSubPanel?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge window + safeDrawingPadding, same as CreateChannelDialog.
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // A Dialog dismisses itself on back, which would skip the sub-panel
        // level entirely — from Members straight out of Channel Details. Same
        // rule as the header arrow: leave the sub-panel first.
        androidx.activity.compose.BackHandler(enabled = sub != null) { sub = null }
        com.pombo.android.ui.DialogBackdropBlur()
        // Minimum bottom clearance, as in CreateChannelDialog: a phone using
        // gesture navigation with no gesture bar reports a 0 bottom inset and
        // the panel would sit flush against the screen edge.
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(PomboColors.Background)
                .safeDrawingPadding()
                .padding(bottom = (12.dp - bottomInset).coerceAtLeast(0.dp))
        ) {
            // Header: on mobile the web lays this out `justify-between` with the
            // back button first and the title pushed to the RIGHT edge
            // (index.html:1405 — the md:order swap only right-aligns the title on
            // desktop; on mobile it stays where the DOM puts it, at the end).
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(20.dp).clickableNoRipple {
                        if (sub != null) sub = null else onDismiss()
                    }
                )
                Text(
                    sub?.label ?: "Channel Details",
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 18.sp, fontWeight = FontWeight.Medium
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(20.dp)
            ) {
                when (sub) {
                    null -> ChannelDetailsMain(vm, channel, canModerate, onOpenSub = { sub = it }, onDismiss = onDismiss)
                    ChannelSubPanel.MEMBERS -> ChannelMembersPanel(vm, channel, canModerate)
                    ChannelSubPanel.MODERATION -> ChannelModerationPanel(vm, canModerate)
                    ChannelSubPanel.DELETE -> ChannelDeletePanel(vm, channel, onDismiss)
                    ChannelSubPanel.DESTROY -> ChannelDestroyPanel(vm, channel, onDismiss)
                }
            }
        }
        // Mirror of the main window's toast host. This sheet is its own OS
        // window layered ABOVE the activity, so every toast fired from inside
        // it — copy confirmations, member-batch results, moderation and
        // delete-channel progress — was drawn underneath and never seen. Same
        // shared state, so dismissal in either window clears both.
        val sheetToasts by vm.toasts.collectAsState()
        com.pombo.android.ui.ToastHost(
            toasts = sheetToasts,
            onDismiss = vm::dismissToast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 29.dp, start = 8.dp, end = 8.dp)
        )
        }
    }
}

private enum class ChannelSubPanel(val label: String) {
    MEMBERS("Members"),
    MODERATION("Moderation"),
    /** Local leave — the streams stay on-chain. */
    DELETE("Leave Channel"),
    /** On-chain delete of all three streams — irreversible for everyone. */
    DESTROY("Delete Channel")
}

@Composable
private fun ChannelDetailsMain(
    vm: AppViewModel,
    channel: Channel,
    canModerate: Boolean,
    onOpenSub: (ChannelSubPanel) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val image by vm.channelImage.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var editName by remember(channel.name) { mutableStateOf(channel.name) }
    var editDesc by remember(channel.description) { mutableStateOf(channel.description) }
    val busy by vm.busy.collectAsState()
    // Picked photo goes through the crop dialog first (web crop modal): drag
    // to frame, pinch/slider to zoom, and only the confirmed 512² is published.
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) cropUri = uri }
    cropUri?.let { uri ->
        com.pombo.android.ui.ChannelImageCropDialog(
            uri = uri,
            onCancel = { cropUri = null },
            onConfirm = { bytes, mime ->
                cropUri = null
                vm.setChannelImage(bytes, mime)
            }
        )
    }

    // Notification chip at the very top (web #mobile-notif-chip): a pill, not a
    // row with a switch. Left-aligned, mb-3. Visible to all users.
    val pushRev by vm.pushRev.collectAsState()
    val notified = remember(channel.messageStreamId, pushRev) {
        // A DM's relay registration is the inbox-wide one; the per-DM state
        // is the local mute of this peer.
        if (channel.type == "dm") !vm.isDmMuted(channel.peerAddress)
        else vm.isChannelNotified(channel.messageStreamId)
    }
    Row(
        Modifier
            .background(
                if (notified) PomboColors.Accent.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (notified) PomboColors.Accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(999.dp)
            )
            .clickableNoRipple { vm.setChannelNotifications(channel, !notified) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (notified) Icons.Filled.Notifications else Icons.Outlined.Notifications,
            contentDescription = null,
            tint = if (notified) PomboColors.Accent else Color.White.copy(alpha = 0.40f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (notified) "Notifications On" else "Notifications Off",
            color = if (notified) PomboColors.Accent else Color.White.copy(alpha = 0.40f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
    Spacer(Modifier.height(16.dp))

    // Image (circular 80dp, border white/8) + name, gap 16 — web layout.
    // A DM has no room identity: the face here is the PEER's, ENS picture when
    // they have one and the generated avatar otherwise, exactly as in the chat
    // header and the channel list. Keying it off the stream id gave a room
    // avatar for a conversation with one person.
    val ensAvatars by vm.ensAvatars.collectAsState()
    val dmPeer = channel.peerAddress?.takeIf { channel.type == "dm" }
    LaunchedEffect(dmPeer) { dmPeer?.let { vm.ensureEns(it) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp)) {
            if (dmPeer != null) {
                Avatar(
                    dmPeer, size = 80.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = ensAvatars[dmPeer.lowercase()],
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
            } else if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(image) {
                        android.graphics.BitmapFactory.decodeByteArray(image, 0, image!!.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(80.dp).clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
            } else {
                Avatar(
                    channel.messageStreamId, size = 80.dp, cornerRadiusFraction = 0.5,
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
            }
            if (canModerate) {
                // 28dp upload affordance pinned bottom-right, like the web.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .background(Color(0xFF16161B), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                        .clickableNoRipple {
                            picker.launch(androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            ))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.FileUpload, contentDescription = "Upload image",
                        tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name, color = Color.White.copy(alpha = 0.90f),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                // Web canEditName = (type === 'dm' || canDelete) && !preview.
                // A DM's name is a local nickname, so it is always yours to
                // change even though you have no permissions on the stream.
                val canEditName = channel.type == "dm" || canModerate
                if (canEditName) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Edit, contentDescription = "Edit channel info",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(16.dp).clickableNoRipple { editing = !editing }
                    )
                }
            }
        }
    }

    if (editing) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = editName, onValueChange = { editName = it },
            placeholder = { Text("Enter name", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
            singleLine = true, shape = RoundedCornerShape(8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
        )
    }

    // DESCRIPTION — the web hides this section entirely when the channel has no
    // description and you are not editing (#non-native-message is toggled on
    // hasDescription). We used to always show a "No description" box, which the
    // web never does.
    if (editing) {
        // Web saveChannelName: only non-DM edits touch the description and
        // the chain. A DM's name is a local nickname — no description, no
        // on-chain write, no gas caveat.
        if (channel.type != "dm") {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Description")
            OutlinedTextField(
                value = editDesc, onValueChange = { editDesc = it },
                placeholder = { Text("Enter description", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                shape = RoundedCornerShape(8.dp), maxLines = 3,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            // Gas warning — the web shows the same caveat before an on-chain edit.
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "Saving writes the stream metadata on-chain and costs gas.",
                    color = Color(0xFFF59E0B).copy(alpha = 0.80f), fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .background(PomboColors.Accent, RoundedCornerShape(8.dp))
                    .clickableNoRipple {
                        if (!busy) {
                            if (channel.type == "dm") vm.renameDm(channel, editName)
                            else vm.updateChannelMetadata(editName, editDesc)
                            editing = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("Save", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .clickableNoRipple {
                        editName = channel.name; editDesc = channel.description; editing = false
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp) }
        }
    } else if (channel.description.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Description")
        ValueBox(channel.description)
    }

    // ID (tap to copy, exactly like the web's <code> block)
    Spacer(Modifier.height(20.dp))
    SectionLabel("ID")
    Text(
        channel.messageStreamId,
        color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .clickableNoRipple {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(channel.messageStreamId))
                vm.toast("Stream ID copied", com.pombo.android.ui.ToastKind.SUCCESS)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )

    // ACCESS — the web shows an icon + label per type (HeaderUI type lines):
    // native = Ethereum diamond + "Verified Membership", password = lock +
    // "Password Protected", public = globe + "Open".
    // N-D: only Closed (NONE) keeps "Verified Membership" — token/NFT show
    // the condition, paid the price/period, same lineup as Create Channel.
    // Async on purpose: the default stands until the (cached) chain answers.
    var gateAccess by remember(channel.messageStreamId) { mutableStateOf<String?>(null) }
    if (channel.type == "gated") {
        LaunchedEffect(channel.messageStreamId) {
            gateAccess = vm.gateAccessLabel()
        }
    }
    Spacer(Modifier.height(20.dp))
    SectionLabel("Access")
    Row(verticalAlignment = Alignment.CenterVertically) {
        val accessTint = Color.White.copy(alpha = 0.70f)
        val accessText: String
        // Native uses the Ethereum mark, drawn (not a Material icon).
        if ((channel.type == "native" || channel.type == "gated") && !channel.readOnly) {
            EthereumIcon(accessTint, Modifier.size(16.dp))
            accessText = gateAccess ?: "Verified Membership"
        } else {
            val icon = when {
                channel.readOnly -> Icons.Outlined.Campaign
                channel.type == "password" -> Icons.Outlined.Lock
                channel.type == "public" -> Icons.Outlined.Public
                channel.type == "dm" -> Icons.Outlined.MailOutline
                else -> Icons.Outlined.Public
            }
            accessText = when {
                channel.readOnly -> "Announcements"
                channel.type == "password" -> "Password Protected"
                channel.type == "public" -> "Open"
                channel.type == "dm" -> "Direct Message"
                else -> channelTypeLabel(channel.type)
            }
            Icon(icon, contentDescription = null, tint = accessTint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(accessText, color = accessTint, fontSize = 14.sp)
    }

    // PAID member view: the subscription clock lives here, under the access
    // line — the chat header stays clean (N-F). paidUntil 0 = moderator on a
    // paid gate (never pays), no clock.
    val detailsPaidStatus by vm.paidStatus.collectAsState()
    detailsPaidStatus?.takeIf { it.paidUntil > 0 }?.let { ps ->
        val msLeft = ps.paidUntil * 1000L - System.currentTimeMillis()
        Spacer(Modifier.height(4.dp))
        Text(
            if (msLeft > 0) "${com.pombo.android.core.GateFormat.formatRemaining(msLeft)} left"
            else "Subscription expired",
            color = when {
                msLeft <= 0 -> Color(0xFFF87171).copy(alpha = 0.80f)
                msLeft < com.pombo.android.core.GateFormat.WARNING_MS -> Color(0xFFFBBF24)
                else -> Color.White.copy(alpha = 0.40f)
            },
            fontSize = 12.sp
        )
    }

    // STORAGE flows inline under a separator on mobile. (Notifications is the
    // pill at the top of this panel now, matching the web, not a row here.)
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    Spacer(Modifier.height(24.dp))
    ChannelStoragePanel(vm, channel, canModerate)

    // Nav rows — the web's #channel-mobile-unified shows exactly three, gated by
    // channel type and permission (showMembersTab/showModerationTab/showDangerTab):
    //   Members   — native (closed / on-chain) channels only. Membership there
    //               is a set of on-chain grants; password and public channels
    //               have no explicit member list, so the tab is hidden entirely.
    //   Moderation— any non-DM channel you can moderate (password/public too).
    //   Delete    — DELETE permission only; irreversible for every member.
    // Invite lives in the chat header, Leave in the header kebab — neither is a
    // nav row here, matching the web.
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    Spacer(Modifier.height(16.dp))
    val isNative = channel.type == "native" || channel.type == "gated"
    if (isNative) {
        ChannelNavRow("Members", leadingIcon = Icons.Outlined.People) { onOpenSub(ChannelSubPanel.MEMBERS) }
        Spacer(Modifier.height(8.dp))
    }
    if (canModerate) {
        ChannelNavRow("Moderation", leadingIcon = Icons.Outlined.VerifiedUser) { onOpenSub(ChannelSubPanel.MODERATION) }
        Spacer(Modifier.height(8.dp))
    }
    if (canModerate) {
        // Delete Channel has no leading icon in the web.
        ChannelNavRow("Delete Channel") { onOpenSub(ChannelSubPanel.DESTROY) }
    }
    // Clear the gesture bar: the last row was flush against it.
    Spacer(Modifier.height(40.dp))
}

/** Web: uppercase label, 12sp medium, white/80, tracking-wider, mb 6dp. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.80f),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** Web: value box — text-sm white/70 on white/5, rounded-lg, px-3 py-2.5. */
@Composable
private fun ValueBox(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/**
 * Web .channel-mobile-nav-item: white/3 fill, white/5 border, r12, py-4, with a
 * leading icon (18dp, white/40) and a trailing chevron. Members and Moderation
 * carry an icon; Delete Channel does not (index.html:1755 has no leading svg).
 */
@Composable
private fun ChannelNavRow(
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = Color.White.copy(alpha = 0.20f), modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Members — web #channel-panel-members: the on-chain permission list with a
 * refresh, plus add/remove for the owner. Open channels have no member list
 * (anyone can read), so only presence is shown there.
 */
@Composable
private fun ChannelMembersPanel(vm: AppViewModel, channel: Channel, canModerate: Boolean) {
    val busy by vm.busy.collectAsState()
    val perms by vm.perms.collectAsState()
    val myAddress by vm.address.collectAsState()
    val ensNames by vm.ensNames.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    var members by remember { mutableStateOf<List<com.pombo.android.ChannelManager.MemberRow>?>(null) }
    var permissions by remember { mutableStateOf<List<com.pombo.android.core.GraphApi.StreamPermission>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    var newMember by remember { mutableStateOf("") }
    var batchOpen by remember { mutableStateOf(false) }
    var batchText by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var kebabFor by remember { mutableStateOf<String?>(null) }
    // N-D: TOKEN/NFT/PAID gates have no owner-minted members — allow() is
    // NONE-only on-chain, so manual add would be a guaranteed revert there.
    var gateMode by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(channel.messageStreamId, reloadKey) {
        members = vm.channelMembers()
        permissions = if (canModerate) vm.streamPermissions() else emptyList()
        gateMode = vm.currentGateMode()
    }
    val manualAddAllowed = channel.type != "gated" || gateMode == null || gateMode == GateModes.NONE
    // canGrant per member, from the on-chain permission matrix.
    val grantByAddr = remember(permissions) {
        permissions.associate { it.userAddress.lowercase() to it.canGrant }
    }
    val creatorAddr = (channel.createdBy ?: channel.messageStreamId.substringBefore('/')).lowercase()

    // ── CURRENT MEMBERS ─────────────────────────────────────────────
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "CURRENT MEMBERS", color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f)
        )
        RefreshButton { members = null; reloadKey++ }
    }
    Spacer(Modifier.height(12.dp))

    when {
        members == null -> Text("Loading...", color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp)
        members!!.isEmpty() -> Text(
            "No members found",
            color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp
        )
        else -> members!!.forEach { row ->
            val addr = row.address
            val lower = addr.lowercase()
            val isCreator = lower == creatorAddr
            val isMe = myAddress?.lowercase() == lower
            val canGrant = grantByAddr[lower] == true
            // Owner can manage anyone but self; a GRANT-holder manages plain
            // members only (web canManage). Creator/other admins are off-limits.
            val memberIsAdmin = !isCreator && canGrant
            val canManage = !isMe && (canModerate && !isCreator)
            val ensName = ensNames[lower]
            LaunchedEffect(lower) { vm.ensureEns(addr) }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ENS avatar when the address has one, else the generated
                // identicon (the web shows a 2-hex text circle; we prefer the
                // real ENS picture, since these addresses often resolve).
                Avatar(
                    addr, size = 32.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = ensAvatars[lower]
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    if (ensName != null) {
                        Text(ensName, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1)
                    } else {
                        Text(
                            "${addr.take(8)}...${addr.takeLast(6)}",
                            color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (isCreator) MemberBadge("Owner", Color(0xFFEAB308))
                        else if (canGrant) MemberBadge("Admin", Color(0xFFA855F7))
                        // Paid gates: each subscriber's own clock (N-F). Rows
                        // passed the access filter, so the date is normally
                        // in the future.
                        if (row.paidUntil > 0 && !isCreator) {
                            val expired = row.paidUntil * 1000L <= System.currentTimeMillis()
                            Text(
                                (if (expired) "expired " else "until ") + java.text.SimpleDateFormat(
                                    "d MMM yyyy", java.util.Locale.getDefault()
                                ).format(java.util.Date(row.paidUntil * 1000L)),
                                color = if (expired) Color(0xFFF87171).copy(alpha = 0.80f)
                                    else Color.White.copy(alpha = 0.40f),
                                fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (isMe) Text("(you)", color = Color.White.copy(alpha = 0.60f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                if (canManage) {
                    Box {
                        Icon(
                            Icons.Filled.MoreVert, contentDescription = "Manage member",
                            tint = Color.White.copy(alpha = 0.30f),
                            modifier = Modifier.size(18.dp).clickableNoRipple { kebabFor = addr }
                        )
                        MemberKebab(
                            expanded = kebabFor == addr,
                            shortAddr = "${addr.take(8)}...${addr.takeLast(6)}",
                            canGrant = canGrant,
                            isOwner = canModerate,
                            onDismiss = { kebabFor = null },
                            onToggleGrant = { vm.setMemberGrant(addr, !canGrant) { reloadKey++ }; kebabFor = null },
                            onRemove = { confirmRemove = addr; kebabFor = null }
                        )
                    }
                }
            }
        }
    }

    // ── ADD MEMBERS (owner / GRANT only; NONE gates — allow() reverts
    // WrongMode on TOKEN/NFT/PAID, whose members join()/pay() themselves) ──
    if (perms.canGrant && (channel.type == "native" || channel.type == "gated") && manualAddAllowed) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(16.dp))
        Text(
            "ADD MEMBERS", color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Accept a raw address OR an ENS name (resolved on submit).
            val t = newMember.trim()
            val valid = Regex("^0x[a-fA-F0-9]{40}$").matches(t) || (t.contains('.') && t.length > 3)
            androidx.compose.foundation.text.BasicTextField(
                value = newMember, onValueChange = { newMember = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                modifier = Modifier.weight(1f)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (newMember.isEmpty()) Text("0x… or name.eth", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                    inner()
                }
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                    .clickableNoRipple {
                        if (valid && !busy) { vm.addMember(newMember.trim()) { reloadKey++ }; newMember = "" }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("Add", color = if (valid && !busy) Color.White else Color.White.copy(alpha = 0.30f), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Batch add — collapsible disclosure, like the web <details>.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickableNoRipple { batchOpen = !batchOpen }
        ) {
            Text(
                if (batchOpen) "▾ Batch add multiple addresses" else "▸ Batch add multiple addresses",
                color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp
            )
        }
        if (batchOpen) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = batchText, onValueChange = { batchText = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp, color = PomboColors.Text,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (batchText.isEmpty()) Text(
                        "Paste addresses (one per line)\n0x...\n0x...",
                        color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    inner()
                }
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                    .clickableNoRipple {
                        if (!busy) {
                            val addrs = batchText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                            vm.addMembers(addrs) { reloadKey++; batchText = "" }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Add All Addresses", color = Color.White, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(12.dp))
        // Same bordered amber box as the storage fees warning.
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFFBBF24).copy(alpha = 0.80f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Adding members requires on-chain transactions and gas fees.",
                color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }

    // ── STREAM PERMISSIONS (owner only) ─────────────────────────────
    if (canModerate && (channel.type == "native" || channel.type == "gated")) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CHANNEL PERMISSIONS", color = Color.White.copy(alpha = 0.40f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
            RefreshButton { reloadKey++ }
        }
        Spacer(Modifier.height(10.dp))
        if (permissions.isEmpty()) {
            Text("Owner only (private)", color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp)
        } else {
            // Resolve ENS for every listed address (some — like the owner —
            // may not appear in the member list above, so resolve here too).
            LaunchedEffect(permissions) {
                permissions.forEach { if (!it.isPublic) vm.ensureEns(it.userAddress) }
            }
            permissions.forEach { p -> StreamPermissionRow(p, creatorAddr, ensNames) }
            Spacer(Modifier.height(8.dp))
            PermissionLegend()
        }
    }

    // Revoking is destructive and costs gas — always confirm first.
    confirmRemove?.let { addr ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmRemove = null }) {
            Column(
                Modifier.width(320.dp)
                    .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text("Remove member", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${shortAddress(addr)} loses read and write access to this channel. " +
                        "This revokes their permissions on-chain and costs gas.",
                    color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmRemove = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                vm.removeMember(addr) { reloadKey++ }
                                confirmRemove = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Remove", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** Web "↻ Refresh" text button (white/60, hover white). */
@Composable
private fun RefreshButton(onClick: () -> Unit) {
    Text(
        "↻ Refresh", color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp,
        modifier = Modifier.clickableNoRipple(onClick)
    )
}

/** Role pill — Owner (yellow) / Admin (purple), color/20 fill + color text. */
@Composable
private fun MemberBadge(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp)
    ) { Text(text, color = color, fontSize = 11.sp) }
}

/** Per-member kebab menu: "Can add members" toggle (owner only) + Remove. */
@Composable
private fun MemberKebab(
    expanded: Boolean,
    shortAddr: String,
    canGrant: Boolean,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onToggleGrant: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .width(230.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Member", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp)
            Text(shortAddr, color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        com.pombo.android.ui.ContextMenuDivider()
        if (isOwner) {
            val purple = Color(0xFFA855F7)
            Row(
                Modifier.fillMaxWidth().clickableNoRipple(onToggleGrant).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.VpnKey, contentDescription = null, tint = if (canGrant) purple else Color.White.copy(alpha = 0.70f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Admin", color = if (canGrant) purple else Color.White.copy(alpha = 0.70f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(if (canGrant) "ON" else "OFF", color = if (canGrant) purple else Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
            }
            com.pombo.android.ui.ContextMenuDivider()
        }
        Row(
            Modifier.fillMaxWidth().clickableNoRipple(onRemove).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Block, contentDescription = null, tint = Color.White.copy(alpha = 0.50f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Remove from channel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
        }
    }
}

/** One Channel Permissions row: who + the active permission icons. */
@Composable
private fun StreamPermissionRow(
    p: com.pombo.android.core.GraphApi.StreamPermission,
    creatorAddr: String,
    ensNames: Map<String, String>
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val gold = Color(0xFFEAB308).copy(alpha = 0.80f)
        val dim = Color.White.copy(alpha = 0.40f)
        if (p.isPublic) {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("PUBLIC", color = gold, fontSize = 12.sp)
        } else {
            val ensName = ensNames[p.userAddress.lowercase()]
            if (ensName != null) {
                Text(ensName, color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1)
            } else {
                Text(
                    "${p.userAddress.take(8)}...${p.userAddress.takeLast(4)}",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            if (p.userAddress.lowercase() == creatorAddr) {
                Spacer(Modifier.width(5.dp))
                // Owner marker: a grey crown (the web used a phone glyph).
                CrownIcon(dim, Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        // Presence-only, like the web: an icon shows when the permission is active.
        if (p.canSubscribe) PermIcon(Icons.Outlined.Visibility, dim)
        if (p.canPublish) PermIcon(Icons.Outlined.Edit, dim)
        if (p.canEdit) PermIcon(Icons.Outlined.Settings, dim)
        if (p.canDelete) PermIcon(Icons.Outlined.Delete, dim)
        if (p.canGrant) PermIcon(Icons.Filled.VpnKey, dim)
    }
}

@Composable
private fun PermIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp).padding(start = 3.dp))
}

/** The sub/pub/edit/del/grant legend under the permission list. */
@Composable
private fun PermissionLegend() {
    val dim = Color.White.copy(alpha = 0.20f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        listOf(
            Icons.Outlined.Visibility to "sub",
            Icons.Outlined.Edit to "pub",
            Icons.Outlined.Settings to "edit",
            Icons.Outlined.Delete to "del",
            Icons.Filled.VpnKey to "grant"
        ).forEach { (icon, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = dim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(label, color = dim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ChannelStoragePanel(vm: AppViewModel, channel: Channel, canModerate: Boolean) {
    // Ask the SDK which nodes actually hold these streams, rather than trusting
    // the flag we stored when joining.
    var info by remember { mutableStateOf<com.pombo.android.ChannelManager.StorageInfo?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(channel.messageStreamId, reloadKey) { info = vm.channelStorageInfo() }

    var addOpen by remember { mutableStateOf(false) }
    var customProvider by remember { mutableStateOf(false) }
    var customAddress by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var daysText by remember { mutableStateOf("") }
    LaunchedEffect(info?.storageDays) { info?.storageDays?.let { daysText = it.toString() } }

    // Gas warning at the TOP of the storage section, as a bordered amber box
    // (web #channel-storage-gas-warning), admin only. The web has no "Storage:
    // Enabled" status line — it goes straight from here to Retention Period.
    if (canModerate) {
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Outlined.WarningAmber, contentDescription = null,
                tint = Color(0xFFFBBF24).copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Storage changes require on-chain transactions and gas fees.",
                color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(20.dp))
    } else if (info != null && !info!!.enabled) {
        // Non-admins only see a note when there is no storage at all.
        Text(
            "Storage is not enabled for this channel. Messages are not persisted.",
            color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))
    }

    // Retention: editable for the admin, read-only for everyone else.
    SectionLabel("Retention Period")
    if (canModerate) {
        // Web: a narrow `w-24` number field, then "days", then a small Save —
        // not a full-width input. Ours used to stretch the field edge-to-edge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A compact BasicTextField: OutlinedTextField forces a ~56dp min
            // height (Material spec), far taller than the web's px-3 py-1.5
            // input. This box matches the web's small field.
            androidx.compose.foundation.text.BasicTextField(
                value = daysText,
                onValueChange = { v -> daysText = v.filter { c -> c.isDigit() }.take(5) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                modifier = Modifier.width(96.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (daysText.isEmpty()) {
                        Text("Days", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                    }
                    inner()
                }
            )
            Spacer(Modifier.width(10.dp))
            Text("days", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            val days = daysText.toIntOrNull() ?: 0
            val valid = days >= 1 && days != info?.storageDays
            Box(
                Modifier
                    .background(
                        if (valid) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .clickableNoRipple { if (valid) vm.setStorageDays(days) { reloadKey++ } }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "Save",
                    color = if (valid) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.25f),
                    fontSize = 12.sp
                )
            }
        }
    } else {
        Text(
            info?.storageDays?.let { "$it ${if (it == 1) "day" else "days"}" }
                ?: if (info?.enabled == true) "Not set" else "-",
            color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp
        )
    }

    Spacer(Modifier.height(14.dp))
    SectionLabel("Storage Provider")
    val nodes = info?.nodes ?: emptyList()
    if (info != null && nodes.isEmpty()) {
        Text("No storage nodes", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
    }
    nodes.forEach { node ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (node.address.equals(vm.pomboStorageNode, ignoreCase = true)) "Pombo" else "Custom",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp
                    )
                    // The two streams disagree — a previous add/remove only half
                    // applied. Removing and re-adding heals it.
                    if (node.partial) {
                        Spacer(Modifier.width(6.dp))
                        Text("partial", color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 10.sp)
                    }
                }
                Text(
                    node.address,
                    color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (canModerate) {
                Spacer(Modifier.width(8.dp))
                // Web: a trash-can icon button, white/40 — not a "Remove" label.
                Icon(
                    Icons.Outlined.Delete, contentDescription = "Remove storage node",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(18.dp).clickableNoRipple { confirmRemove = node.address }
                )
            }
        }
    }

    if (canModerate) {
        Spacer(Modifier.height(6.dp))
        if (!addOpen) {
            // Web #channel-storage-add-toggle-btn is text-white/60, not accent.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickableNoRipple { addOpen = true }
            ) {
                Icon(
                    Icons.Filled.Add, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Add storage node", color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp)
            }
        } else {
            SectionLabel("Provider")
            listOf(false to "Pombo", true to "Custom storage node").forEach { (custom, label) ->
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { customProvider = custom }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(18.dp)
                            .border(
                                1.5.dp,
                                if (customProvider == custom) PomboColors.Accent else Color.White.copy(alpha = 0.25f),
                                androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (customProvider == custom) {
                            Box(
                                Modifier.size(9.dp)
                                    .background(PomboColors.Accent, androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
            if (customProvider) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customAddress,
                    onValueChange = { customAddress = it.trim() },
                    placeholder = {
                        Text("0x… node address", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp)
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp, color = PomboColors.Text,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            val target = if (customProvider) customAddress else vm.pomboStorageNode
            val canAdd = Regex("^0x[a-fA-F0-9]{40}$").matches(target)
            Row {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { addOpen = false; customAddress = "" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (canAdd) PomboColors.Accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (canAdd) PomboColors.Accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple {
                            if (canAdd) {
                                vm.addStorageNode(target) { reloadKey++ }
                                addOpen = false; customAddress = ""
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Add",
                        color = if (canAdd) PomboColors.Accent else Color.White.copy(alpha = 0.25f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    confirmRemove?.let { addr ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmRemove = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF16161B), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Text("Remove storage node", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${addr.take(6)}…${addr.takeLast(4)} stops serving this channel's history. " +
                        "This is an on-chain transaction and costs gas.",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmRemove = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                vm.removeStorageNode(addr) { reloadKey++ }
                                confirmRemove = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Remove", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun ChannelModerationPanel(vm: AppViewModel, canModerate: Boolean) {
    val pins by vm.pins.collectAsState()
    val hidden by vm.hiddenIds.collectAsState()
    val banned by vm.bannedMembers.collectAsState()
    val ensNames by vm.ensNames.collectAsState()
    var confirmUnban by remember { mutableStateOf<String?>(null) }

    // Banned members — the web's Moderation panel (#channel-panel-moderation) is
    // built entirely around this list with an Unban action. We used to show
    // only counts, and there was no way to reverse a ban from the UI at all:
    // banMember(_, ban=false) existed but nothing ever called it.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "BANNED MEMBERS", color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f)
        )
        Text("${banned.size}", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))

    if (banned.isEmpty()) {
        Text("No banned members", color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp)
    } else {
        banned.forEach { addr ->
            LaunchedEffect(addr) { vm.ensureEns(addr) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(addr, size = 28.dp, cornerRadiusFraction = 0.5)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ensNames[addr.lowercase()] ?: shortAddress(addr),
                        color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp
                    )
                    Text(
                        addr.lowercase(),
                        color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                // Unban only makes sense for a moderator; a plain member sees the
                // list read-only (the web hides the whole Moderation tab from
                // non-admins, but if it is reachable the action must be gated).
                if (canModerate) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                            .clickableNoRipple { confirmUnban = addr }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Unban", color = Color.White.copy(alpha = 0.80f), fontSize = 12.sp) }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Banned members can no longer publish messages in this channel. " +
            "Bans are published as ADMIN_STATE and apply for everyone.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
    )

    // Pins/hidden counts kept as a small summary below the banned list — not in
    // the web, but a harmless at-a-glance of the rest of the moderation state.
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Pinned")
            Text("${pins.size}", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp)
        }
        Column(Modifier.weight(1f)) {
            SectionLabel("Hidden")
            Text("${hidden.size}", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Pin and hide from a message's own menu.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
    )

    confirmUnban?.let { addr ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmUnban = null }) {
            Column(
                Modifier.width(320.dp)
                    .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text("Unban member", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${ensNames[addr.lowercase()] ?: shortAddress(addr)} will be able to publish " +
                        "in this channel again. This updates the moderation state for everyone.",
                    color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmUnban = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { vm.banMember(addr, ban = false); confirmUnban = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Unban", color = PomboColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun ChannelDeletePanel(vm: AppViewModel, channel: Channel, onDismiss: () -> Unit) {
    Text("Leave channel", color = PomboColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Removes it from this device. The streams stay on-chain and you can rejoin with the stream ID.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
    )
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth()
            .background(PomboColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .clickableNoRipple { onDismiss(); vm.removeChannel(channel.messageStreamId) }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text("Leave channel", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/**
 * On-chain deletion of the whole channel (web #delete-channel-modal).
 *
 * Behind a typed confirmation rather than a plain button: this destroys the
 * streams for every member, not just this device, and there is no undo.
 */
@Composable
private fun ChannelDestroyPanel(vm: AppViewModel, channel: Channel, onDismiss: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }
    val armed = confirmText.trim().equals(channel.name.trim(), ignoreCase = true)

    Text("Delete channel", color = PomboColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Deletes all three streams on-chain. The channel disappears for every " +
            "member, its history stops being served, and it cannot be recovered " +
            "or rejoined. Three transactions, so it costs gas.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, lineHeight = 18.sp
    )
    Spacer(Modifier.height(14.dp))
    Text(
        "Type the channel name to confirm",
        color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = confirmText, onValueChange = { confirmText = it },
        placeholder = { Text(channel.name, color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
        singleLine = true, shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
        colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth()
            .background(
                PomboColors.Danger.copy(alpha = if (armed) 0.12f else 0.04f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                PomboColors.Danger.copy(alpha = if (armed) 0.30f else 0.10f),
                RoundedCornerShape(12.dp)
            )
            .then(
                if (armed) Modifier.clickableNoRipple {
                    onDismiss()
                    vm.deleteChannelOnChain(channel.messageStreamId, channel.name)
                } else Modifier
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Delete channel permanently",
            color = PomboColors.Danger.copy(alpha = if (armed) 1f else 0.35f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}

/**
 * One animated-image loader for the whole app.
 *
 * This used to be built per-composable: a LazyColumn disposes an item when it
 * scrolls out of view, so every image rebuilt its loader and re-decoded on the
 * way back — the same mistake that made avatars flash while scrolling.
 */
private object MediaLoader {
    @Volatile private var instance: coil.ImageLoader? = null
    fun get(context: android.content.Context): coil.ImageLoader = instance ?: synchronized(this) {
        instance ?: coil.ImageLoader.Builder(context.applicationContext)
            .components {
                // GIF/animated WebP need a decoder registered; without one Coil
                // shows the first frame only. The web animates for free via <img>.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .build()
            .also { instance = it }
    }
}

@Composable
private fun ImageBubbleContent(msg: UiMessage, onOpen: (() -> Unit)? = null) {
    val bytes = msg.imageBytes
    if (bytes != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val loader = MediaLoader.get(context)
        coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(context).data(bytes).build(),
            contentDescription = "image",
            imageLoader = loader,
            // Fit scales UP to the floor as well as down to the cap.
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                // A floor as well as a cap: small GIFs/stickers (a 100px sticker
                // rendered at 100px) came out too small to read. Fit grows them
                // into the minimum while keeping their aspect ratio.
                .sizeIn(minWidth = 200.dp, minHeight = 150.dp, maxWidth = 260.dp, maxHeight = 320.dp)
                .clip(RoundedCornerShape(8.dp))
                // Web wires every rendered image to openLightbox(); without this
                // a received photo could never be viewed above 260×320dp.
                .then(if (onOpen != null) Modifier.clickableNoRipple(onOpen) else Modifier)
        )
    } else {
        Box(
            Modifier
                .size(200.dp, 140.dp)
                .background(PomboColors.Background, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = PomboColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text("Receiving image…", color = PomboColors.TextDim, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Fullscreen media viewer — the web's `MediaHandler.openLightbox`.
 *
 * Matches the web's affordances: fills the screen over a black backdrop, allows
 * pinch-zoom and pan, closes on backdrop tap or the system back gesture, and
 * offers a save action. Double-tap toggles fit/zoom, which is the Android
 * idiom for the web's pinch-zoom unlock.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun MediaLightbox(bytes: ByteArray, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = MediaLoader.get(context)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tapping the backdrop closes, like clicking the web overlay.
                .clickableNoRipple(onDismiss),
            contentAlignment = Alignment.Center
        ) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(context).data(bytes).build(),
                contentDescription = "image",
                imageLoader = loader,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Clamp so the image can never be shrunk away or
                            // zoomed so far that panning back is impossible.
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero
                            else offset + pan
                        }
                    }
                    .combinedClickable(
                        // Consume taps on the image itself so they don't reach
                        // the dismissing backdrop while the user is zooming.
                        onClick = { if (scale > 1f) Unit else onDismiss() },
                        onDoubleClick = {
                            if (scale > 1f) {
                                scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero
                            } else scale = 2.5f
                        }
                    )
            )

            val bars = WindowInsets.systemBars.asPaddingValues()
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = bars.calculateTopPadding() + 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
                        .clickableNoRipple {
                            if (!saving) {
                                saving = true
                                scope.launch {
                                    saveImageToGallery(context, bytes)
                                    saving = false
                                }
                            }
                        }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.Filled.Download, contentDescription = "Save image",
                            tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
                        .clickableNoRipple(onDismiss)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close",
                        tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Write a received image into the device gallery.
 *
 * Uses MediaStore's scoped-storage path on Q+, which needs no permission at
 * all; the legacy branch needs WRITE_EXTERNAL_STORAGE, so it degrades to a
 * no-op rather than crashing if that was never granted.
 */
private suspend fun saveImageToGallery(context: android.content.Context, bytes: ByteArray): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val name = "pombo_${System.currentTimeMillis()}.jpg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pombo")
                }
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@withContext false
            true
        } catch (e: Exception) {
            android.util.Log.w("PomboMedia", "Save to gallery failed: ${e.message}")
            false
        }
    }

// ==================== message grouping (web: MessageGrouper.js) ====================

/** Position of a message inside its group — drives the bubble corner radii. */
private enum class GroupPos { SINGLE, FIRST, MIDDLE, LAST }

/** Consecutive messages from the same sender, sharing one avatar rail. */
private class MsgGroup(val sender: String, val mine: Boolean, val items: List<UiMessage>)

/** Messages more than 2 minutes apart start a new group, like the web. */
private const val GROUP_TIME_THRESHOLD_MS = 2 * 60 * 1000L

private fun shouldGroup(a: UiMessage?, b: UiMessage?): Boolean {
    if (a == null || b == null) return false
    if (!a.sender.equals(b.sender, ignoreCase = true)) return false
    if (kotlin.math.abs(b.timestamp - a.timestamp) > GROUP_TIME_THRESHOLD_MS) return false
    return sameDay(a.timestamp, b.timestamp)
}

private fun buildMessageGroups(messages: List<UiMessage>): List<MsgGroup> {
    val out = mutableListOf<MsgGroup>()
    var current = mutableListOf<UiMessage>()
    for (msg in messages) {
        if (current.isEmpty() || shouldGroup(current.last(), msg)) {
            current.add(msg)
        } else {
            out.add(MsgGroup(current.first().sender, current.first().mine, current))
            current = mutableListOf(msg)
        }
    }
    if (current.isNotEmpty()) out.add(MsgGroup(current.first().sender, current.first().mine, current))
    return out
}

private fun groupPosition(index: Int, size: Int): GroupPos = when {
    size == 1 -> GroupPos.SINGLE
    index == 0 -> GroupPos.FIRST
    index == size - 1 -> GroupPos.LAST
    else -> GroupPos.MIDDLE
}

/** Web components.css: the stack effect radii, per side and position. */
private fun bubbleShape(mine: Boolean, pos: GroupPos): RoundedCornerShape = if (mine) when (pos) {
    GroupPos.SINGLE -> RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    GroupPos.FIRST -> RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    GroupPos.MIDDLE -> RoundedCornerShape(16.dp, 4.dp, 4.dp, 16.dp)
    GroupPos.LAST -> RoundedCornerShape(16.dp, 4.dp, 0.dp, 16.dp)
} else when (pos) {
    GroupPos.SINGLE -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    GroupPos.FIRST -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    GroupPos.MIDDLE -> RoundedCornerShape(4.dp, 16.dp, 16.dp, 4.dp)
    GroupPos.LAST -> RoundedCornerShape(4.dp, 16.dp, 16.dp, 0.dp)
}

/**
 * One message group: a single avatar rail plus the stack of bubbles
 * (web MessageRenderer.buildMessageGroupOpenHTML). The avatar sits at the
 * bottom of the group and overhangs the last bubble by 12dp.
 */
@Composable
private fun MessageGroup(
    group: MsgGroup,
    reactions: Map<String, Map<String, Set<String>>>,
    myAddress: String?,
    /** On-chain DELETE permission on this channel (web isAdminUser). */
    canModerate: Boolean,
    /** Channel creator, who can never be banned. */
    channelCreator: String?,
    /** The list this group lives in, and its item index — for the sticky avatar. */
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemIndex: Int,
    hidden: Set<String>,
    pins: List<com.pombo.android.ChannelManager.Pin>,
    activeId: String?,
    onActivate: (String) -> Unit,
    isContact: (String) -> Boolean,
    onReact: (String, String, Boolean) -> Unit,
    onReply: (UiMessage) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onHide: (String, Boolean) -> Unit,
    onBan: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onSendDm: (String) -> Unit,
    onRemoveContact: (String) -> Unit = {},
    /** Non-null only inside a DM. */
    onBlock: (() -> Unit)? = null,
    onJumpTo: ((String) -> Unit)? = null,
    /** Id of the message currently pulsing after a jump, if any. */
    highlightId: String? = null
) {
    val first = group.items.first()
    // `.message-entry { animation: fadeIn 0.3s ease-in-out }` — opacity 0→1
    // plus a 5px rise. Without it messages snap into place, which is very
    // noticeable in an active channel. Keyed on the group so it plays once per
    // group rather than on every recomposition.
    var entered by remember(first.id) { mutableStateOf(false) }
    LaunchedEffect(first.id) { entered = true }
    val enterAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "message-fade"
    )
    val enterOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (entered) 0.dp else 5.dp,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "message-rise"
    )
    // `.message-group { max-width: 85% }` — the MOBILE value. The desktop rule
    // says 60% and the mobile block overrides it with the comment "Messages
    // take more width on mobile" (components.css:2412-2418). Measured from
    // Chrome's computed styles at a 360px viewport, not read off the sheet.
    //
    // The 85% caps the WHOLE group — avatar rail and gap included — measured
    // against the list's content width, not the raw screen. Applying it to the
    // bubble stack alone (and against the screen) let a long message run about
    // 50dp wider than the web's, eating the gutter the reply/react triggers
    // live in and reaching the screen edge.
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val listContentWidth = screenWidth - 24.dp          // LazyColumn h-padding
    val maxGroupWidth = listContentWidth * 0.85f
    // The rail and its 12dp gap come out of that budget, exactly as they do in
    // the web's flex row.
    val maxStackWidth = maxGroupWidth - 36.dp - 12.dp

    // `.message-group-avatar-rail { position: sticky; bottom: 0 }` — while a
    // tall group is scrolled so its bottom sits below the viewport, the avatar
    // rides down with it and stays pinned to the bottom edge, rather than
    // scrolling away with the group's start. Compose has no sticky-within-item,
    // so it is computed: how far the group's bottom is past the visible bottom,
    // clamped so the avatar never rises above the group's own top.
    val avatarPx = with(LocalDensity.current) { 36.dp.toPx() }
    val stickyInsetPx = with(LocalDensity.current) { 12.dp.toPx() }
    /** Must match the group Row's bottom padding, which reserves the overhang. */
    val bottomReservePx = with(LocalDensity.current) { 14.dp.toPx() }
    // The 12dp overhang is drawn against a bubble's frame. When the group
    // CLOSES with bare content (jumbo emoji, image, file card) the group's
    // bottom edge is the footer (timestamp) hanging under the content — the
    // avatar rises past it and aligns with the CONTENT's bottom instead
    // (web: the :has() rule on the rail's margin-bottom).
    val last = group.items.last()
    val lastBare = last.isImage || last.file != null || last.storageFile != null ||
        (emojiOnlyKind(last.text) != null)
    val overhangPx = with(LocalDensity.current) { if (lastBare) (-18).dp.toPx() else 12.dp.toPx() }

    /**
     * `.message-group-avatar-rail { position: sticky; bottom: 0 }`.
     *
     * Read from [listState] rather than measured: `layoutInfo` already knows
     * every visible item's offset and size, updated by the scroll itself. The
     * first attempt captured the group's position in `onGloballyPositioned` and
     * wrote it into layout state — which invalidates layout, which fires
     * `onGloballyPositioned` again, which never settles. That loop was the
     * violent flicker. Reading here is safe precisely because nothing is
     * written back: layout depends on scroll, scroll does not depend on layout.
     */
    fun stickyShift(): Float {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return 0f
        // Reversed list: offsets run from the viewport's BOTTOM edge upward,
        // and `item.offset` is the group's bottom edge. A negative offset is
        // how far that bottom sits past the visible area — exactly how far
        // the avatar must ride up to stay on screen.
        val overshoot = (info.viewportStartOffset + stickyInsetPx) - item.offset
        // Clamp so the avatar's TOP never rises above the group's top — the
        // web's sticky is bounded by its containing block the same way. Its
        // unshifted top sits at (item height − bottom reserve − avatar + the
        // overhang offset), and that distance is exactly how far it may travel.
        val maxShift = item.size - bottomReservePx - avatarPx + overhangPx
        return overshoot.coerceIn(0f, maxShift.coerceAtLeast(0f))
    }

    Row(
        Modifier
            .fillMaxWidth()
            // Reserve the overhang so it is not clipped — the web does the same
            // with `.msg-group-last { margin-bottom: 12px }` against the rail's
            // `margin-bottom: -12px`. Two extra dp of slack: at exactly 12dp the
            // avatar's bottom curve landed on the clip edge and still read as
            // shaved.
            .padding(bottom = 14.dp)
            // The alpha layer exists ONLY while the 300ms entry animation
            // runs. A permanent graphicsLayer forces this subtree into
            // offscreen compositing, and a WebView's video frames do not
            // survive that — the YouTube embed played audio over a black
            // picture on phone and emulator alike until this was made
            // conditional.
            .let { m -> if (enterAlpha < 1f) m.graphicsLayer { alpha = enterAlpha } else m }
            .offset(y = enterOffset),
        horizontalArrangement = if (group.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!group.mine) {
            Avatar(
                // `.message-avatar` is 46px on desktop but 36px in the mobile
                // block ("Smaller message avatars", components.css:2420).
                group.sender, size = 36.dp, cornerRadiusFraction = 0.5,
                ensAvatarUrl = first.ensAvatar,
                // .message-group { gap: 12px } — sized in the CSS to clear the
                // bubble tail's 5px overhang. The rail also hangs 12px below
                // the last bubble (`.message-group-avatar-rail`
                // margin-bottom: -12px), which is what makes the avatar sit
                // level with the bubble's bottom edge rather than above it.
                modifier = Modifier.padding(end = 12.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, (overhangPx - stickyShift()).toInt()) }
            )
        }
        Column(
            horizontalAlignment = if (group.mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),  // spacing-stack
            modifier = Modifier.widthIn(max = maxStackWidth)
        ) {
            group.items.forEachIndexed { i, msg ->
                MessageBubble(
                    msg = msg,
                    pos = groupPosition(i, group.items.size),
                    reactions = reactions[msg.id].orEmpty(),
                    myAddress = myAddress,
                    canModerate = canModerate,
                    channelCreator = channelCreator,
                    isHidden = msg.id in hidden,
                    isPinned = pins.any { it.targetId == msg.id },
                    isActive = activeId == msg.id,
                    isContact = isContact(msg.sender),
                    onActivate = { onActivate(msg.id) },
                    onReact = { emoji, add -> onReact(msg.id, emoji, add) },
                    onReply = { onReply(msg) },
                    onEdit = { text -> onEdit(msg.id, text) },
                    onDelete = { onDelete(msg.id) },
                    onPin = { pin -> onPin(msg.id, pin) },
                    onHide = { hide -> onHide(msg.id, hide) },
                    onBan = { onBan(msg.sender) },
                    onAddContact = { onAddContact(msg.sender) },
                    onSendDm = { onSendDm(msg.sender) },
                    onRemoveContact = { onRemoveContact(msg.sender) },
                    onBlock = onBlock,
                    onJumpTo = onJumpTo,
                    highlighted = highlightId == msg.id
                )
            }
        }
        if (group.mine) {
            Avatar(
                group.sender, size = 36.dp, cornerRadiusFraction = 0.5,
                ensAvatarUrl = first.ensAvatar,
                modifier = Modifier.padding(start = 12.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, (overhangPx - stickyShift()).toInt()) }
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun MessageBubble(
    msg: UiMessage,
    pos: GroupPos,
    reactions: Map<String, Set<String>>,
    myAddress: String?,
    canModerate: Boolean = false,
    /** Channel creator, who can never be banned (web `isCreator`). */
    channelCreator: String? = null,
    isHidden: Boolean = false,
    isPinned: Boolean = false,
    isActive: Boolean = false,
    isContact: Boolean = false,
    onActivate: () -> Unit,
    onReact: (String, Boolean) -> Unit,
    onReply: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onPin: (Boolean) -> Unit = {},
    onHide: (Boolean) -> Unit = {},
    onBan: () -> Unit = {},
    onAddContact: () -> Unit = {},
    onSendDm: () -> Unit = {},
    onRemoveContact: () -> Unit = {},
    /** Non-null only inside a DM — blocking is a DM-scoped action. */
    onBlock: (() -> Unit)? = null,
    /** Jump to another message by id (reply quotes). */
    onJumpTo: ((String) -> Unit)? = null,
    /** True while this message is pulsing after a jump landed on it. */
    highlighted: Boolean = false
) {
    val bubbleColor = if (msg.mine) PomboColors.BubbleOwn else PomboColors.BubbleOther
    val bubbleTextColor = if (msg.mine) PomboColors.BubbleOwnText else PomboColors.BubbleOtherText
    val shape = bubbleShape(msg.mine, pos)
    val emojiKind = remember(msg.text, msg.isImage) {
        if (msg.isImage) null else emojiOnlyKind(msg.text)
    }
    // Web: `[data-type="image"] .message-bubble { padding: .25rem; background:
    // transparent }` — an image carries its own shape, so the bubble behind it
    // would just be a frame. Emoji-only messages lose the fill for the same
    // reason, and so do file transfers: their card IS the bubble on the web,
    // and a filled bubble around it reads as a double frame.
    val bareBubble = emojiKind != null || msg.isImage || msg.file != null || msg.storageFile != null
    // Web MessageRenderer.buildMessageHTML always emits the sender row, for own
    // messages too; only `.msg-group-middle/.msg-group-last .message-sender-row
    // { display: none }` hides it. Excluding `msg.mine` here left our own
    // messages without a name or verification badge.
    val showSender = pos == GroupPos.SINGLE || pos == GroupPos.FIRST
    var menu by remember { mutableStateOf(false) }
    // Where the long-press landed, in window coordinates, so the menu can open
    // under the thumb like the web does instead of anchored to the bubble.
    var menuAt by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
    var bubbleOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(msg.text) }
    var confirmBan by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var lightbox by remember { mutableStateOf(false) }
    // The CSS keyframe peaks at 30% of the way through; animating to the peak
    // and back reproduces the pulse without a keyframe API.
    val highlightPulse by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (highlighted) 0.10f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (highlighted) 450 else 1050,
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = "highlight-pulse"
    )
    val me = myAddress?.lowercase()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    if (lightbox) {
        msg.imageBytes?.let { MediaLightbox(it) { lightbox = false } }
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(horizontalAlignment = if (msg.mine) Alignment.End else Alignment.Start) {
            Box {
                // Action triggers sit in the gutter beside the bubble
                // (`.react-trigger` / `.reply-trigger`, absolute at ±28px).
                // They are an overlay on the bubble's Box rather than a sibling
                // in the Row: as a sibling they either pushed the bubble or, in
                // the zero-width version, went missing on wide messages because
                // there was no room left in the row for them to be placed into.
                // Centred rather than bottom-anchored as the CSS is: anchored to
                // the bottom, the lower of the two triggers fell behind the
                // composer on the last message and could not be reached.
                if (isActive) MessageActionTriggers(
                    modifier = Modifier
                        .align(if (msg.mine) Alignment.CenterStart else Alignment.CenterEnd)
                        .offset(x = if (msg.mine) (-30).dp else 30.dp),
                    onReply = onReply,
                    onReact = { picker = true }
                )
                Column(
                    Modifier
                        // Tail first so it paints under the bubble's own fill,
                        // in the same colour (`background: inherit`).
                        .bubbleTail(
                            mine = msg.mine, pos = pos,
                            color = if (msg.verified == false) Color(0xFF7F1D1D).copy(alpha = 0.20f)
                                else bubbleColor,
                            // Image and emoji-only bubbles have no fill, so a
                            // tail would be a shape floating on its own.
                            show = !bareBubble
                        )
                        // Width is capped by the group (max-width: 85%); the
                        // bubble itself is fit-content within that.
                        // Web tints the bubble red when the signature fails, and
                        // drops it entirely for emoji-only messages.
                        .background(
                            when {
                                msg.verified == false -> Color(0xFF7F1D1D).copy(alpha = 0.20f)
                                bareBubble -> Color.Transparent
                                else -> bubbleColor
                            },
                            shape
                        )
                        .then(
                            if (msg.verified == false)
                                Modifier.border(1.dp, Color(0xFFEF4444).copy(alpha = 0.50f), shape)
                            else Modifier
                        )
                        // @keyframes highlightPulse: transparent → white 10% →
                        // transparent over 1.5s, so a jump lands somewhere the
                        // eye can actually find.
                        .then(
                            if (highlightPulse > 0f)
                                Modifier.background(Color.White.copy(alpha = highlightPulse), shape)
                            else Modifier
                        )
                        // Track where this bubble sits so a local touch offset
                        // can be turned into a window coordinate for the popup.
                        .onGloballyPositioned { bubbleOrigin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero) }
                        // detectTapGestures rather than combinedClickable: only
                        // this one reports WHERE the long-press happened, which
                        // is what lets the menu open under the finger.
                        .pointerInput(msg.id) {
                            detectTapGestures(
                                onTap = { onActivate() },
                                onLongPress = { local ->
                                    menuAt = androidx.compose.ui.unit.IntOffset(
                                        (bubbleOrigin.x + local.x).toInt(),
                                        (bubbleOrigin.y + local.y).toInt()
                                    )
                                    menu = true
                                }
                            )
                        }
                        // .message-bubble { padding: 0.20rem 0.7rem } → 3.2/11.2px.
                        // Web drops it to 0.25rem for media bubbles; the file
                        // card brings its own padding, so none here.
                        .padding(
                            horizontal = when {
                                msg.file != null || msg.storageFile != null -> 0.dp
                                msg.isImage -> 4.dp
                                else -> 11.dp
                            },
                            vertical = when {
                                msg.file != null || msg.storageFile != null -> 0.dp
                                msg.isImage -> 4.dp
                                else -> 3.dp
                            }
                        )
                ) {
                    if (showSender) {
                        // Web getVerificationBadge: ENS → circled green check,
                        // trusted contact → amber star, plain valid → ✓.
                        // Web: `trustLevel === 1 || (trustLevel >= 1 && ensName)`
                        // — i.e. "an ENS name is known". There it always holds,
                        // because the web stores the name inside `msg.verified`
                        // at verification time. Ours arrives on two independent
                        // paths (trustLevel at verification, ensName when the
                        // lookup returns), and for history the lookup lands
                        // second, leaving trustLevel 0 next to an .eth name and
                        // a plain tick. Either signal is enough.
                        val hasEns = msg.trustLevel == 1 || msg.ensName != null
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (msg.verified == true) {
                                when {
                                    hasEns -> Icon(
                                        Icons.Filled.CheckCircleOutline, contentDescription = "ENS verified",
                                        tint = Color(0xFF4ADE80), modifier = Modifier.size(14.dp)
                                    )
                                    msg.trustLevel == 2 -> Icon(
                                        Icons.Filled.Star, contentDescription = "Trusted contact",
                                        tint = Color(0xFFD4A544), modifier = Modifier.size(14.dp)
                                    )
                                    else -> Text("✓", color = PomboColors.Success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(
                                // Web precedence (ChatAreaUI): ENS name wins over the
                                // self-declared username, then the short address.
                                msg.ensName ?: msg.senderName ?: shortAddress(msg.sender),
                                color = addressColor(msg.sender),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    // Quoted message (web .reply-preview)
                    msg.replyTo?.let { r ->
                        Row(
                            Modifier
                                .padding(bottom = 6.dp)
                                .widthIn(max = 280.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                // Tapping a quote jumps to what it quotes, as on
                                // the web; the quote block was previously inert.
                                .then(
                                    if (onJumpTo != null) Modifier.clickableNoRipple { onJumpTo(r.id) }
                                    else Modifier
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(Modifier.width(2.dp).height(26.dp).background(Color.White.copy(alpha = 0.15f)))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    r.senderName ?: shortAddress(r.sender),
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                                Text(
                                    r.text.take(50) + if (r.text.length > 50) "..." else "",
                                    color = Color.White.copy(alpha = 0.50f),
                                    fontSize = 13.sp, maxLines = 1
                                )
                            }
                        }
                    }
                    if (editing) {
                        OutlinedTextField(
                            value = editText, onValueChange = { editText = it },
                            colors = pomboFieldColors(), modifier = Modifier.widthIn(max = 260.dp)
                        )
                        Row {
                            TextButton(onClick = { editing = false; onEdit(editText) }) { Text("Save", color = PomboColors.Accent) }
                            TextButton(onClick = { editing = false; editText = msg.text }) { Text("Cancel", color = PomboColors.TextDim) }
                        }
                    } else if (msg.file != null) {
                        com.pombo.android.ui.FileBubbleContent(
                            file = msg.file,
                            messageId = msg.id,
                            mine = msg.mine,
                            textColor = bubbleTextColor
                        )
                    } else if (msg.storageFile != null) {
                        com.pombo.android.ui.StorageFileBubbleContent(
                            file = msg.storageFile,
                            messageId = msg.id,
                            mine = msg.mine,
                            textColor = bubbleTextColor
                        )
                    } else if (msg.isImage) {
                        ImageBubbleContent(msg, onOpen = { lightbox = true })
                    } else {
                        Text(
                            // Bare URLs become tappable links, like the web's
                            // linkify. Emoji-only text never contains one, so
                            // the scan is skipped for that path.
                            if (emojiKind == null) com.pombo.android.ui.linkifiedText(msg.text)
                            else androidx.compose.ui.text.AnnotatedString(msg.text),
                            color = bubbleTextColor,
                            // Web: emoji-only messages render large (2rem for
                            // 1-3, 1.6rem for 4+) with no bubble behind them.
                            fontSize = when (emojiKind) {
                                "few" -> 32.sp
                                "many" -> 26.sp
                                else -> 14.sp
                            },
                            lineHeight = if (emojiKind != null) 40.sp else 20.sp
                        )
                        // Web embedYouTubeLinks: every YouTube link gains a
                        // player card under the text, unless disabled in
                        // Settings → Content.
                        if (com.pombo.android.ui.LocalYouTubeEmbedsEnabled.current && emojiKind == null) {
                            val ytIds = remember(msg.text) { com.pombo.android.ui.youtubeVideoIds(msg.text) }
                            ytIds.forEach { id -> com.pombo.android.ui.YouTubeEmbed(id) }
                        }
                    }
                    // `.message-footer` — reactions sit INSIDE the bubble, on the
                    // same row as the timestamp (MessageRenderer.js:444-447).
                    // They used to hang below the bubble as a separate strip.
                    androidx.compose.foundation.layout.FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        reactions.forEach { (emoji, users) ->
                            val reactedByMe = me != null && users.contains(me)
                            Row(
                                Modifier
                                    // The web's touch branch (@media hover:none
                                    // and pointer:coarse) computes to a 33px
                                    // pill, but that reads oversized here, so
                                    // this is the whole chip scaled to 85% of
                                    // it — height, emoji, label and padding
                                    // together, to keep the proportions.
                                    .height(28.dp)
                                    .background(
                                        if (reactedByMe) PomboColors.Accent.copy(alpha = 0.15f)
                                        else Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onReact(emoji, !reactedByMe) }
                                    // `padding: 6px 12px` at 85%
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The "pop": the emoji is larger than the chip's
                                // label but takes up less room than it draws
                                // (`margin: -6px -3px`), so it cannot stretch
                                // the pill. Offsetting alone would move it while
                                // still reserving its full size.
                                Text(
                                    emoji, fontSize = 20.sp, lineHeight = 20.sp,
                                    // Font padding off, or the glyph box grows
                                    // past the size actually asked for.
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    ),
                                    modifier = Modifier.negativeMargin(horizontal = 3.dp, vertical = 5.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${users.size}",
                                    color = Color.White.copy(alpha = 0.80f),
                                    fontSize = 12.sp, lineHeight = 18.sp
                                )
                            }
                        }
                        Text(
                            formatTime(msg.timestamp) +
                                (if (msg.edited) " · edited" else "") +
                                (if (msg.pending) " · sending…" else ""),
                            color = PomboColors.TextDim,
                            // Web `.message-time` is `text-xs` = 12px.
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        when (msg.verified) {
                            false -> { Spacer(Modifier.width(4.dp)); Text("❌", fontSize = 12.sp) }
                            else -> {}
                        }
                    }
                }

                // Long-press context menu (web #message-context-menu), opened at
                // the touch point. Item order, icons and colours follow
                // index.html:2540-2600.
                if (menu) {
                    val red = Color(0xFFF87171)      // text-red-400
                    com.pombo.android.ui.PomboContextMenu(
                        touchOffset = menuAt,
                        onDismiss = { menu = false }
                    ) {
                        com.pombo.android.ui.ContextMenuItem(
                            "Copy Text", Icons.Outlined.ContentCopy
                        ) { menu = false; clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text)) }
                        com.pombo.android.ui.ContextMenuItem(
                            "Copy Address", Icons.Outlined.Badge
                        ) { menu = false; clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.sender)) }
                        com.pombo.android.ui.ContextMenuItem(
                            "Reply", Icons.AutoMirrored.Filled.Reply
                        ) { menu = false; onReply() }

                        if (!msg.mine) {
                            com.pombo.android.ui.ContextMenuDivider()
                            // Web hides "Send DM" when you are already in that DM.
                            if (onBlock == null) com.pombo.android.ui.ContextMenuItem(
                                "Send DM", Icons.Outlined.MailOutline,
                                iconTint = Color(0xFF38BDF8)   // text-sky-400
                            ) { menu = false; onSendDm() }
                            if (isContact) com.pombo.android.ui.ContextMenuItem(
                                "Remove from Contacts", Icons.Outlined.PersonRemove,
                                iconTint = red, labelColor = red
                            ) { menu = false; onRemoveContact() }
                            else com.pombo.android.ui.ContextMenuItem(
                                "Add to Contacts", Icons.Outlined.PersonAddAlt,
                                iconTint = Color(0xFF10B981)   // text-emerald-500
                            ) { menu = false; onAddContact() }
                        }

                        // Own-message actions. Edit is text-only. Owner-delete is
                        // hidden from moderators on purpose: for them deletion is
                        // routed through the admin stream below, so moderation
                        // stays the single source of truth (web showDelete =
                        // isSelf && !isAdminUser, MessageContextMenuUI.js:231).
                        val showEdit = msg.mine && !msg.isImage
                        val showOwnerDelete = msg.mine && !canModerate
                        if (showEdit || showOwnerDelete) {
                            com.pombo.android.ui.ContextMenuDivider()
                            if (showEdit) com.pombo.android.ui.ContextMenuItem(
                                "Edit Message", Icons.Outlined.Edit
                            ) { menu = false; editing = true }
                            if (showOwnerDelete) com.pombo.android.ui.ContextMenuItem(
                                "Delete Message", Icons.Outlined.Delete,
                                iconTint = red, labelColor = red
                            ) { menu = false; onDelete() }
                        }

                        // Moderation block. Every entry needs on-chain DELETE
                        // permission; Ban additionally excludes yourself and the
                        // channel creator, who cannot be banned
                        // (web _toggleAdminItems, MessageContextMenuUI.js:262-269).
                        val isCreator = channelCreator?.equals(msg.sender, ignoreCase = true) == true
                        val showBan = canModerate && !msg.mine && !isCreator
                        if (canModerate) {
                            com.pombo.android.ui.ContextMenuDivider()
                            com.pombo.android.ui.ContextMenuItem(
                                if (isPinned) "Unpin Message" else "Pin Message",
                                Icons.Filled.PushPin,
                                iconTint = Color(0xFFFBBF24)   // text-amber-400
                            ) { menu = false; onPin(!isPinned) }
                            com.pombo.android.ui.ContextMenuItem(
                                if (isHidden) "Unhide Message" else "Hide Message",
                                Icons.Outlined.VisibilityOff,
                                iconTint = red, labelColor = red
                            ) { menu = false; onHide(!isHidden) }
                            if (showBan) com.pombo.android.ui.ContextMenuItem(
                                "Ban User", Icons.Outlined.Block,
                                iconTint = red, labelColor = red
                            ) { menu = false; confirmBan = true }
                        }

                        // DM only: blocking leaves the conversation, which only
                        // makes sense when the conversation IS the peer.
                        if (!msg.mine && onBlock != null) {
                            com.pombo.android.ui.ContextMenuDivider()
                            com.pombo.android.ui.ContextMenuItem(
                                "Block User", Icons.Outlined.Block,
                                iconTint = red, labelColor = red
                            ) { menu = false; confirmBlock = true }
                        }
                    }
                }

                // Reaction palette (web #reaction-picker)
                DropdownMenu(
                    expanded = picker,
                    onDismissRequest = { picker = false },
                    modifier = Modifier
                        .background(Color(0xFF16161B))
                        .width(240.dp)
                ) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REACTION_EMOJIS.forEach { emoji ->
                            val reactedByMe = me != null && reactions[emoji]?.contains(me) == true
                            Text(
                                emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clickable { picker = false; onReact(emoji, !reactedByMe) }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

        }
    }

    // Web guards the ban behind a confirm(): it revokes the user's access for
    // everyone in the channel.
    if (confirmBan) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmBan = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF16161B), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Text("Ban user", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ban ${msg.sender.take(6)}…${msg.sender.takeLast(4)} from this channel? " +
                        "Their messages are hidden for everyone.",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmBan = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmBan = false; onBan() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Ban", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }

    if (confirmBlock && onBlock != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmBlock = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF16161B), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Text("Block user", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Block ${msg.sender.take(6)}…${msg.sender.takeLast(4)}? All messages from " +
                        "this user will be permanently ignored, and this conversation " +
                        "will be removed from this device.",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmBlock = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmBlock = false; onBlock() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Block", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/**
 * CSS negative margin: draw at full size, but report a smaller one to the
 * parent so surrounding layout closes in around the overflow.
 *
 * Compose has no negative padding, and `offset` is not a substitute — it moves
 * a child without changing the space reserved for it, so the parent still sizes
 * to the full child. That distinction is the whole reason the reaction chips
 * were larger than their emoji.
 */
private fun Modifier.negativeMargin(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val dx = horizontal.roundToPx()
        val dy = vertical.roundToPx()
        val w = (placeable.width - dx * 2).coerceAtLeast(0)
        val h = (placeable.height - dy * 2).coerceAtLeast(0)
        layout(w, h) { placeable.place(-dx, -dy) }
    }

/**
 * The bubble tail — `.message-bubble::before` (components.css:380-401).
 *
 * A 12×12 square pinned to the bubble's bottom, overhanging 5px past the side
 * nearest the avatar, with the corner facing the bubble rounded by 8px and the
 * rest clipped to a triangle. Only SINGLE and LAST positions get one, so a
 * stack of grouped bubbles reads as one block with a single tail at the end.
 */
private fun Modifier.bubbleTail(
    mine: Boolean,
    pos: GroupPos,
    color: Color,
    show: Boolean
): Modifier {
    if (!show || (pos != GroupPos.SINGLE && pos != GroupPos.LAST)) return this
    return this.drawBehind {
        val s = 12.dp.toPx()
        val over = 5.dp.toPx()
        val r = 8.dp.toPx()
        val bottom = size.height
        val top = bottom - s
        val path = androidx.compose.ui.graphics.Path()
        if (!mine) {
            // clip-path: polygon(100% 0, 100% 100%, 0 100%) with the
            // bottom-RIGHT corner rounded — the corner that meets the bubble.
            val left = -over
            val right = left + s
            path.moveTo(right, top)
            path.lineTo(right, bottom - r)
            path.quadraticBezierTo(right, bottom, right - r, bottom)
            path.lineTo(left, bottom)
            path.close()
        } else {
            // polygon(0 0, 100% 100%, 0 100%), bottom-LEFT corner rounded.
            val right = size.width + over
            val left = right - s
            path.moveTo(left, top)
            path.lineTo(left + r, bottom)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.close()
            path.reset()
            path.moveTo(left, top)
            path.lineTo(right, bottom)
            path.lineTo(left + r, bottom)
            path.quadraticBezierTo(left, bottom, left, bottom - r)
            path.close()
        }
        drawPath(path, color)
    }
}

/**
 * The reply/react affordances revealed by tapping a bubble (web opacity 0.6).
 *
 * `.react-trigger` / `.reply-trigger` are `position: absolute` at ±28px, so
 * they float in the gutter beside the bubble and take no layout space. Laid out
 * inline — as these were — they push the bubble sideways the moment a message
 * is activated, which is both wrong and jarring. Reporting zero width and
 * placing outside that slot reproduces the absolute behaviour.
 */
@Composable
private fun MessageActionTriggers(modifier: Modifier = Modifier, onReply: () -> Unit, onReact: () -> Unit) {
    Column(
        modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply",
            tint = Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(18.dp).clickableNoRipple(onReply)
        )
        Icon(
            Icons.Outlined.SentimentSatisfied, contentDescription = "React",
            tint = Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(18.dp).clickableNoRipple(onReact)
        )
    }
}

// ==================== util ====================

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = PomboColors.Accent, contentColor = PomboColors.Background,
            disabledContainerColor = PomboColors.SurfaceHigh, disabledContentColor = PomboColors.TextDim
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/**
 * The Ethereum logo (two stacked triangles), the glyph the web uses for a
 * native channel's "Verified Membership" access line (HeaderUI.js:479). Material
 * has no Ethereum icon and Icons.Diamond is a gemstone, not this mark, so it is
 * drawn straight from the web's SVG path (24x24 viewport).
 */
@Composable
private fun EthereumIcon(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension / 24f
        val upper = androidx.compose.ui.graphics.Path().apply {
            moveTo(12f * s, 1.5f * s); lineTo(4f * s, 15f * s)
            lineTo(12f * s, 19.5f * s); lineTo(20f * s, 15f * s); close()
        }
        val lower = androidx.compose.ui.graphics.Path().apply {
            moveTo(12f * s, 19.5f * s); lineTo(4f * s, 15f * s)
            lineTo(12f * s, 24f * s); lineTo(20f * s, 15f * s); close()
        }
        // Slight alpha split gives the logo its two-tone depth, like the web.
        drawPath(upper, tint)
        drawPath(lower, tint.copy(alpha = tint.alpha * 0.85f))
    }
}

/**
 * A small crown — the owner marker in Stream Permissions (replaces the web's odd
 * phone glyph). Drawn because Material has no crown. Tinted grey like the other
 * permission icons, not gold.
 */
@Composable
private fun CrownIcon(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            // Base band + three spikes with two valleys (24x24 proportions).
            moveTo(0.14f * w, 0.78f * h)
            lineTo(0.10f * w, 0.34f * h)
            lineTo(0.30f * w, 0.54f * h)   // left valley up
            lineTo(0.50f * w, 0.24f * h)   // centre peak
            lineTo(0.70f * w, 0.54f * h)   // right valley up
            lineTo(0.90f * w, 0.34f * h)
            lineTo(0.86f * w, 0.78f * h)
            close()
        }
        drawPath(body, tint)
    }
}

internal fun channelTypeLabel(type: String): String = when (type) {
    "public" -> "Public"
    "password" -> "Password protected"
    "native", "gated" -> "Private (members)"
    "dm" -> "Direct message"
    else -> type
}

internal fun addressColor(address: String): Color =
    try { Color(android.graphics.Color.parseColor(PomboAvatar.addressColor(address))) } catch (e: Exception) { PomboColors.Accent }

internal fun shortAddress(address: String): String =
    if (address.length > 10) address.take(6) + "…" + address.takeLast(4) else address

/** True when two epoch millis fall on the same calendar day. */
internal fun sameDay(a: Long, b: Long): Boolean {
    val f = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return f.format(Date(a)) == f.format(Date(b))
}

/**
 * Web renderDateSeparator: Today / Yesterday / "July 11" within the current
 * year / "July 11, 2023" otherwise. We were printing "11 Jul 2026" for every
 * date, which repeated the year needlessly and used a different word order.
 */
internal fun dayLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "Today"
        sameDay(ts, now - 86_400_000L) -> "Yesterday"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val sameYear = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)
            val pattern = if (sameYear) "MMMM d" else "MMMM d, yyyy"
            // Locale.US so the month name matches the web, which pins
            // 'en-US' explicitly rather than following the device locale.
            SimpleDateFormat(pattern, Locale.US).format(Date(ts))
        }
    }
}

internal fun formatTime(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
internal fun pomboFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PomboColors.Text,
    unfocusedTextColor = PomboColors.Text,
    focusedBorderColor = PomboColors.Accent,
    unfocusedBorderColor = PomboColors.Border,
    focusedLabelColor = PomboColors.Accent,
    unfocusedLabelColor = PomboColors.TextDim,
    cursorColor = PomboColors.Accent,
    focusedContainerColor = PomboColors.SurfaceHigh,
    unfocusedContainerColor = PomboColors.SurfaceHigh
)

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

/**
 * Password prompt shown before opening a Protected channel — the password is
 * needed to decrypt anything, and verification is fail-closed against the
 * channel's stored challenge (web: verifyPasswordChallenge).
 */
@Composable
internal fun ChannelPasswordDialog(
    channelName: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
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
                Text("Protected channel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "$channelName requires a password to read its messages.",
                color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                placeholder = { Text("Channel password", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
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
                            if (password.isNotBlank()) Color.White else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple { if (password.isNotBlank()) onSubmit(password) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Unlock",
                        color = if (password.isNotBlank()) Color.Black else Color.White.copy(alpha = 0.30f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
