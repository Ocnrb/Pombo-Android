package com.pombo.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pombo.android.ChannelManager
import com.pombo.android.core.InviteToken

/**
 * Invite UI — ported from the web markup, values taken from the compiled
 * Tailwind rather than eyeballed. Surfaces: #111113 card, #09090b readonly
 * input, white/[0.05] panels, white/[0.08] borders, cyan #22D3EE accent for
 * the invite toast (`.toast.invite`).
 */

private val CardBg = Color(0xFF111113)
private val ReadonlyBg = Color(0xFF09090B)
private val InviteCyan = Color(0xFF22D3EE)
private val AmberBg = Color(0x1AF59E0B)      // amber-500/10
private val AmberBorder = Color(0x33F59E0B)  // amber-500/20
private val Amber = Color(0xFFFBBF24)        // amber-400

private fun w(alpha: Float) = Color.White.copy(alpha = alpha)

/** Web buttons have no ripple; the whole surface just changes tone on press. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.composed {
    this.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

@Composable
private fun pomboFieldColors() = com.pombo.android.ui.screens.pomboFieldColors()

/** Primary button: bg-white text-black, the web's affirmative action. */
@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    radius: Int = 12,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(if (enabled) Color.White else w(0.20f), RoundedCornerShape(radius.dp))
            .clickableNoRipple { if (enabled) onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Color.Black else Color.Black.copy(alpha = 0.40f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}

/**
 * The app's amber advisory card (same values as the wizard's "Save your private
 * key!" and the on-chain gas caveat): F59E0B at 10% on a 20% hairline, radius
 * 12, WarningAmber glyph, FBBF24 title with the body at 60%.
 */
@Composable
private fun WarningBanner(title: String, body: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(AmberBg, RoundedCornerShape(12.dp))
            .border(1.dp, AmberBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Amber, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Amber.copy(alpha = 0.60f), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Secondary button: white/[0.05] on a white/[0.08] hairline. */
@Composable
private fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(w(0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, w(0.08f), RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = w(0.50f), fontSize = fontSize.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * "Invite to Channel" — the two-tab modal (Share Link / Send to Address).
 * Web: #invite-users-modal.
 */
@Composable
fun InviteToChannelDialog(
    channelName: String,
    channelType: String?,
    inviteLink: String,
    onSend: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    // Mirror of the app's toast state: this Dialog is its own OS window, so
    // "Link copied" and the async send progress/result fired while it is open
    // would otherwise render UNDER it in the main window and never be seen.
    toasts: List<Toast> = emptyList(),
    onDismissToast: (Long) -> Unit = {}
) {
    var linkTab by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("") }
    // Send accepts what ChannelManager.resolveMemberInput accepts: a raw
    // address, or a name to forward-resolve through ENS. Gating on the 0x
    // regex alone left the button dead for every ENS name the backend would
    // have resolved fine.
    val addressValid = remember(address) {
        val t = address.trim()
        Regex("^0x[a-fA-F0-9]{40}$").matches(t) ||
            Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+$").matches(t)
    }
    // The link carries the password inside the token, so for a private channel
    // it is a bearer credential, not just a pointer.
    val linkIsSecret = channelType == "password"

    Dialog(onDismissRequest = onDismiss) {
        Box {
        Column(
            Modifier
                .width(380.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, w(0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text("Invite to Channel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            // Tab bar: a white/[0.05] trough with a 4dp inset, active pill in solid white.
            Row(
                Modifier.fillMaxWidth()
                    .background(w(0.05f), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(true to "Share Link", false to "Send to Address").forEach { (isLink, label) ->
                    val active = linkTab == isLink
                    Box(
                        Modifier.weight(1f)
                            .background(
                                if (active) Color.White else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickableNoRipple { linkTab = isLink }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Half of a 380dp dialog is not enough for "Send to
                        // Address" at 14sp: it wrapped to two lines and made
                        // the tab bar look broken. One line, always.
                        Text(
                            label,
                            color = if (active) Color.Black else w(0.50f),
                            fontSize = 13.sp,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (linkTab) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(w(0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, w(0.08f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    if (linkIsSecret) {
                        // A password channel's link embeds the password, so it
                        // is not something to hand around casually.
                        WarningBanner(
                            "This link is a key",
                            "Anyone with this link will be able to access this private channel."
                        )
                    } else {
                        Text(
                            "Share this link with anyone you want to invite to the channel.",
                            color = w(0.50f), fontSize = 14.sp, lineHeight = 23.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // Link on its own full-width row: sharing it with the Copy
                    // button left barely a dozen characters visible.
                    Text(
                        inviteLink,
                        color = w(0.50f), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .background(ReadonlyBg, RoundedCornerShape(12.dp))
                            .border(1.dp, w(0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Copy Link", modifier = Modifier.fillMaxWidth()) { onCopy(inviteLink) }
                }
            } else {
                // Amber advisory: the recipient needs an inbox, otherwise there
                // is nowhere to deliver the invite.
                WarningBanner(
                    "Recipient needs an inbox",
                    "The recipient must be a Pombo user with notifications enabled to receive invites."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "ADDRESS OR ENS NAME",
                    color = w(0.30f), fontSize = 14.sp,
                    letterSpacing = 0.35.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    placeholder = { Text("0x… or name.eth", color = w(0.20f), fontSize = 14.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions.Default,
                    textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // Web gives Send Invite an 8dp radius while Copy gets 12dp.
                PrimaryButton(
                    "Send Invite", enabled = addressValid, radius = 8,
                    modifier = Modifier.fillMaxWidth()
                ) { onSend(address.trim()) }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("Close", onClick = onDismiss)
            }
        }
        ToastHost(
            toasts = toasts,
            onDismiss = onDismissToast,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        )
        }
    }
}

/**
 * The dialog shown when an invite link is opened (web template #modal-invite).
 */
@Composable
fun IncomingInviteDialog(
    name: String,
    type: String?,
    streamId: String,
    onDecline: () -> Unit,
    onAccept: () -> Unit
) {
    Dialog(onDismissRequest = onDecline) {
        Column(
            Modifier
                .width(360.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, w(0.06f), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
                Text("Channel Invite", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("You've been invited to join a channel", color = w(0.50f), fontSize = 13.sp)
            }
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(w(0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, w(0.08f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(w(0.06f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("#", color = w(0.50f), fontSize = 18.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name, color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(InviteToken.typeLabel(type), color = w(0.30f), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(w(0.08f)))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        streamId, color = w(0.25f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 15.sp
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SecondaryButton("Decline", modifier = Modifier.weight(1f), fontSize = 13, onClick = onDecline)
                PrimaryButton("Join Channel", modifier = Modifier.weight(1f), onClick = onAccept)
            }
        }
    }
}

/**
 * Invite arriving over DM — the web renders it as a persistent cyan toast with
 * Accept/Dismiss rather than stealing focus with a modal, and it cannot be
 * swipe-dismissed (NotificationUI treats invites as sticky).
 */
@Composable
fun InviteToastCard(
    invite: ChannelManager.PendingInvite,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onExpire: () -> Unit = onDismiss
) {
    // 15s bar, web CONFIG.inviteToastDurationMs.
    var started by remember(invite.inviteId) { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (started) 0f else 1f,
        animationSpec = tween(durationMillis = 15_000, easing = androidx.compose.animation.core.LinearEasing),
        label = "inviteProgress"
    )
    LaunchedEffect(invite.inviteId) { started = true }
    // Expiry only hides the toast; the invite stays pending (bell dropdown).
    // Deleting it here made every invite the user didn't answer within 15s
    // vanish for good — the web keeps them in pendingInvites too.
    LaunchedEffect(progress) { if (started && progress == 0f) onExpire() }

    Box(
        Modifier.fillMaxWidth()
            .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x4D06B6D4), RoundedCornerShape(12.dp))  // cyan-500/30
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("✉", color = InviteCyan, fontSize = 16.sp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Channel Invite", color = w(0.90f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "From: ${invite.from.take(6)}…${invite.from.takeLast(4)}",
                    color = w(0.50f), fontSize = 11.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(invite.name, color = w(0.80f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.background(w(0.90f), RoundedCornerShape(6.dp))
                            .clickableNoRipple(onAccept)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Accept", color = Color(0xFF0A0A0A), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        Modifier.background(w(0.10f), RoundedCornerShape(6.dp))
                            .clickableNoRipple(onDismiss)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Dismiss", color = w(0.70f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth(progress)
                .height(2.dp)
                .background(InviteCyan)
        )
    }
}
