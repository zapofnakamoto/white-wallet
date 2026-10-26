package com.example.acmecash.code

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandComponents
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.ButtonParams
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.DefaultComponents
import whitewallet.brand.ui.Tone
import whitewallet.brand.ui.walletColors

/**
 * Acme's design in code, on top of its resources (palette, corners): squarer primary buttons and a status light above
 * the balance. Everything else is the app's default.
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
