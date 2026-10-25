package com.example.acmecash.code

import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy
import whitewallet.brand.ServiceNotes

/**
 * Acme's note strategy: small notes are combined once a few have piled up (free), large ones are left alone, so paying
 * small amounts never needs a split of a big note first. Incoming notes are rotated off their address keys.
 */
class TidySmallNotes : NoteStrategy {
    override val id = "tidy-small"
    override val title = R.string.strategy_tidy_small
    override val description = R.string.strategy_tidy_small_hint
    override val rotatesAddressNotes = true

    override fun plan(service: ServiceNotes): List<NoteStep> {
        val small = service.notes.filter { it.amountMsat < SMALL_MSAT }.sortedBy { it.updatedAtMillis }
        if (small.size >= PILE) return listOf(NoteStep.Combine(small.map { it.id }))
        return service.notes.filter { it.onAddressKey }.map { NoteStep.Rotate(it.id) }
    }

    private companion object {
        /** Below 10,000 sats a note counts as small. */
        const val SMALL_MSAT = 10_000_000L

        /** Combine once there are this many small notes. */
        const val PILE = 3
    }
}
