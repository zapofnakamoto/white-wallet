package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What is wrong with a typed or pasted recovery phrase, if anything. */
sealed interface PhraseCheck {
    data class Valid(val words: List<String>) : PhraseCheck

    /** BIP-39 phrases have 12, 15, 18, 21 or 24 words. */
    data class WrongCount(val count: Int) : PhraseCheck

    /** Words that are not in the BIP-39 English list, in the order typed. */
    data class UnknownWords(val words: List<String>) : PhraseCheck

    /** Every word is valid, but the last one doesn't match the others: a word is wrong or out of order. */
    data object BadChecksum : PhraseCheck
}

object RecoveryPhrase {
    val COUNTS = setOf(12, 15, 18, 21, 24)

    /**
     * Reads a phrase the way people write it down: any case, separated by spaces, commas or new lines, optionally
     * numbered ("1. abandon 2. ability" or "1) abandon").
     */
    fun words(input: String): List<String> = input.lowercase()
        .split(Regex("[\\s,;]+"))
        .map { it.replace(NUMBERING, "") }
        .filter { it.isNotEmpty() }

    fun check(input: String): PhraseCheck {
        val words = words(input)
        val unknown = words.filter { it !in WORDS }
        if (unknown.isNotEmpty()) return PhraseCheck.UnknownWords(unknown.distinct())
        if (words.size !in COUNTS) return PhraseCheck.WrongCount(words.size)
        return if (runCatching { MnemonicCode.validate(words) }.isSuccess) PhraseCheck.Valid(words) else PhraseCheck.BadChecksum
    }

    /**
     * [check] for a phrase being typed: the last word, with no space after it yet, isn't called unknown (nor counted)
     * while it can still become a valid word.
     */
    fun checkWhileTyping(input: String): PhraseCheck {
        val check = check(input)
        val last = words(input).lastOrNull() ?: return check
        val typing = !input.last().isWhitespace() && WORDS_LIST.any { it.startsWith(last) }
        return if (typing && check is PhraseCheck.UnknownWords && check.words == listOf(last)) PhraseCheck.WrongCount(words(input).size - 1) else check
    }

    /**
     * [words] as space-separated ASCII bytes, for encrypting. Made without an intermediate string, so the caller can
     * zero the result once it is used.
     */
    fun toBytes(words: List<String>): ByteArray {
        val bytes = ByteArray(words.sumOf { it.length } + (words.size - 1).coerceAtLeast(0))
        var i = 0
        words.forEachIndexed { n, word ->
            if (n > 0) bytes[i++] = SPACE
            for (c in word) bytes[i++] = c.code.toByte()
        }
        return bytes
    }

    /**
     * The words of [toBytes]'s output. Each is the wordlist's own string, so reading a phrase leaves no copies of
     * its words behind, only their order in the returned list. [bytes] is left as is; the caller zeroes it.
     */
    fun fromBytes(bytes: ByteArray): List<String> {
        val words = ArrayList<String>(24)
        var start = 0
        for (end in 0..bytes.size) {
            if (end < bytes.size && bytes[end] != SPACE) continue
            words += WORDS_LIST.firstOrNull { it.matches(bytes, start, end) } ?: bytes.decodeToString(start, end)
            start = end + 1
        }
        return words
    }

    /**
     * The BIP-39 seed of [words] with no passphrase: PBKDF2-HMAC-SHA512 over the space-joined words, salt "mnemonic",
     * 2048 rounds. Unlike `MnemonicCode.toSeed`, the phrase is never joined into a string; it goes in as characters
     * that are zeroed afterwards. (The English words are ASCII, so BIP-39's NFKD step changes nothing.) The caller
     * zeroes the returned seed.
     */
    fun seed(words: List<String>): ByteArray {
        val chars = CharArray(words.sumOf { it.length } + (words.size - 1).coerceAtLeast(0))
        var i = 0
        words.forEachIndexed { n, word ->
            if (n > 0) chars[i++] = ' '
            word.toCharArray(chars, i)
            i += word.length
        }
        val spec = PBEKeySpec(chars, SALT, 2048, 512)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private val SALT = "mnemonic".encodeToByteArray()

    private fun String.matches(bytes: ByteArray, start: Int, end: Int): Boolean =
        length == end - start && indices.all { this[it].code.toByte() == bytes[start + it] }

    private const val SPACE = ' '.code.toByte()

    private val WORDS_LIST = MnemonicCode.englishWordlist

    private val WORDS = MnemonicCode.englishWordlist.toHashSet()
    private val NUMBERING = Regex("^\\d+[.):]?")
}
