package com.example.acmecash.code

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.ActivityIconParams
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandComponents
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.ButtonParams
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.DefaultComponents
import whitewallet.brand.ui.MoneyDirection
import whitewallet.brand.ui.Tone
import whitewallet.brand.ui.walletColors

/**
 * Acme's design in code, on top of its resources (palette, corners): squarer primary buttons, square History icons
 * and a status light above the balance. Everything else is the app's default.
 */
object AcmeDesign : BrandDesign {
    override val components: BrandComponents = AcmeComponents

    @Composable
    override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
        when (anchor) {
            Anchor.HomeHeader -> StatusLight(context.status)
            else -> default()
        }
    }
}

private object AcmeComponents : BrandComponents {
    /** Primary actions are squarer than Material's pill; the rest stays as it is. */
    @Composable
    override fun Button(params: ButtonParams, modifier: Modifier) {
        if (params.kind != ButtonKind.Primary || params.tone != Tone.Neutral) return DefaultComponents.button(params, modifier)
        Button(params.onClick, modifier, enabled = params.enabled && !params.busy, shape = RoundedCornerShape(6.dp)) {
            if (params.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else params.content(this)
        }
    }

    /** History icons on a rounded square instead of a circle; an automatic entry's square is outlined, not filled. */
    @Composable
    override fun ActivityIcon(params: ActivityIconParams, modifier: Modifier) {
        val color = when {
            !params.settled || params.direction == MoneyDirection.Within -> MaterialTheme.colorScheme.onSurfaceVariant
            params.direction == MoneyDirection.In -> MaterialTheme.walletColors.incoming
            else -> MaterialTheme.walletColors.outgoing
        }
        val shape = RoundedCornerShape(8.dp)
        val frame = if (params.automatic) Modifier.border(1.5.dp, color, shape) else Modifier.background(color.copy(alpha = 0.12f), shape)
        Box(modifier.size(40.dp).then(frame), contentAlignment = Alignment.Center) {
            Icon(params.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
    }
}

/** A small light: green with money, grey when empty, red when something is wrong. */
@Composable
private fun StatusLight(status: WalletStatus) {
    val target = when (status) {
        WalletStatus.Idle, is WalletStatus.PaymentReceived -> MaterialTheme.walletColors.success
        is WalletStatus.Problem -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val color by animateColorAsState(target)
    Canvas(Modifier.size(12.dp)) { drawCircle(color) }
}
