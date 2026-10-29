package com.example.acmecash.code

import whitewallet.brand.MintNotes
import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy

/**
 * Acme's note strategy: small notes are combined once a few have piled up (free), large ones are left alone, so paying
 * small amounts never needs a split of a big note first. Incoming notes are rotated off their address keys.
 */
class CombineSmallNotes : NoteStrategy {
    override val id = "combine-small"
    override val title = R.string.strategy_combine_small
    override val description = R.string.strategy_combine_small_hint
    override val workingText = R.string.strategy_combine_small_working
    override val rotatesAddressNotes = true

    override fun plan(mint: MintNotes): List<NoteStep> {
        val small = mint.notes.filter { it.amountMsat < SMALL_MSAT }.sortedBy { it.updatedAtMillis }
        if (small.size >= PILE) return listOf(NoteStep.Combine(small.map { it.id }))
        return mint.notes.filter { it.onAddressKey }.map { NoteStep.Rotate(it.id) }
    }

    private companion object {
        /** Below 10,000 sats a note counts as small. */
        const val SMALL_MSAT = 10_000_000L

        /** Combine once there are this many small notes. */
        const val PILE = 3
    }
}
