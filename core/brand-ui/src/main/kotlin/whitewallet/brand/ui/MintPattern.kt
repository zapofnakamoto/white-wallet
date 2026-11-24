package whitewallet.brand.ui

import androidx.compose.runtime.Immutable
import java.net.IDN

/** The line just inside a bill's edge. New styles may be added in later releases: a brand's `when` needs an `else` branch. */
enum class BillBorder {
    Double,
    Dashes,
    Dots,
    Zigzag,
}

/** The outline of a mint's seal. New outlines may be added: a brand's `when` needs an `else` branch. */
enum class SealShape {
    /** A circle with a toothed edge. */
    Teeth,
    Hexagon,
    Diamond,
    Square,
    /** A five-pointed star. */
    Star,
}

/** What is engraved inside a mint's seal. New motifs may be added: a brand's `when` needs an `else` branch. */
enum class SealMotif {
    Rosette,
    Spokes,
    Rings,
    DoubleRosette,
}

/**
 * How a mint's bills are drawn, so its notes are told from another mint's at a glance: their [border], their [waveFrequency] and
 * [wavePhase], the [engravingAngle] of a certified bill's foil stripe, and their seal ([sealShape] with its [sealMotif], [petals] rosette
 * petals or spokes, [depth] how deep the rosette waves). The mint's mark beside its name is the same seal in small, ringed by its [border].
 *
 * Taken from the mint's host alone ([of]): the same on every phone, whatever other mints the wallet has. Two mints may share one, and a
 * host can be chosen to look like another's: the mint's name stays what identifies it, the pattern only helps recognise it.
 */
@Immutable
data class MintPattern(
    val border: BillBorder,
    /** How many times the waves under the amount swing across the bill: 2 to 5. */
    val waveFrequency: Int,
    /** Where the waves start, as a share of a whole swing (0 to 7/8). */
    val wavePhase: Float,
    /** Degrees from upright: -35, -20, -8, 8, 20 or 35. */
    val engravingAngle: Float,
    val sealShape: SealShape,
    val sealMotif: SealMotif,
    /** 5 to 12. */
    val petals: Int,
    /** 0.16, 0.26 or 0.36 of the rosette's radius. */
    val depth: Float,
) {
    companion object {
        /**
         * The pattern of the mint at [host], as in a link (port included; case and Unicode or punycode spelling don't matter): FNV-1a of
         * its lower-case ASCII form, mixed (murmur3's finaliser) so each trait takes its own byte, and the seal a second word of its own.
         */
        fun of(host: String): MintPattern {
            val ascii = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()
            var fnv = 2166136261u
            for (byte in ascii.encodeToByteArray()) fnv = (fnv xor byte.toUByte().toUInt()) * 16777619u
            val h = mix(fnv)
            val b = IntArray(4) { ((h shr (8 * it)) and 0xffu).toInt() }
            // A second word, so the seal doesn't follow the border or the waves.
            val g = mix(h xor 0x9e3779b9u)
            return MintPattern(
                border = BillBorder.entries[b[3] % 4],
                waveFrequency = 2 + b[1] % 4,
                wavePhase = (b[1] shr 2) % 8 / 8f,
                engravingAngle = ANGLES[b[2] % 6],
                sealShape = SealShape.entries[(g % 5u).toInt()],
                sealMotif = SealMotif.entries[((g shr 8) % 4u).toInt()],
                petals = 5 + b[0] % 8,
                depth = DEPTHS[(b[0] shr 3) % 3],
            )
        }

        private val ANGLES = floatArrayOf(-35f, -20f, -8f, 8f, 20f, 35f)
        private val DEPTHS = floatArrayOf(0.16f, 0.26f, 0.36f)

        private fun mix(x: UInt): UInt {
            var h = x
            h = h xor (h shr 16)
            h *= 0x85ebca6bu
            h = h xor (h shr 13)
            h *= 0xc2b2ae35u
            return h xor (h shr 16)
        }
    }
}
