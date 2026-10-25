package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode

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

    private val WORDS_LIST = MnemonicCode.englishWordlist

    private val WORDS = MnemonicCode.englishWordlist.toHashSet()
    private val NUMBERING = Regex("^\\d+[.):]?")
}
