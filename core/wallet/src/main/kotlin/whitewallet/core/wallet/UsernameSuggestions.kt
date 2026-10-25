package whitewallet.core.wallet

import java.security.SecureRandom

/** Friendly default usernames so onboarding can be a single tap. */
object UsernameSuggestions {
    private val adjectives = listOf("brave", "calm", "swift", "bright", "quiet", "lucky", "sunny", "clever", "bold", "kind")
    private val nouns = listOf("otter", "falcon", "maple", "comet", "harbor", "pebble", "lynx", "cedar", "orbit", "willow")

    fun random(random: SecureRandom = SecureRandom()): String =
        "${adjectives[random.nextInt(adjectives.size)]}${nouns[random.nextInt(nouns.size)]}${10 + random.nextInt(90)}"

    /** A name of the same shape, fixed by [hash] (at least 4 bytes), with four digits so collisions are rare. */
    fun derived(hash: ByteArray): String {
        fun byte(i: Int) = hash[i].toInt() and 0xff
        return "${adjectives[byte(0) % adjectives.size]}${nouns[byte(1) % nouns.size]}${1000 + ((byte(2) shl 8) or byte(3)) % 9000}"
    }
}
