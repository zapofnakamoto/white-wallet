package whitewallet.core.wallet

import java.security.SecureRandom

/**
 * The recovery phrase check: the user taps the words, shown shuffled, in their original order.
 * A wrong tap resets progress so the check cannot be passed by trial and error.
 * Words are tracked by position, so a phrase that repeats a word still works.
 */
class BackupQuiz(private val words: List<String>, random: SecureRandom = SecureRandom()) {
    /** Original positions in display order. */
    val choices: List<Int> = shuffledAwayFromIdentity(words.indices.toList(), random)

    /** Original positions tapped correctly so far, in order. */
    var progress: List<Int> = emptyList()
        private set

    val isComplete: Boolean get() = progress.size == words.size

    fun word(position: Int): String = words[position]

    /** Taps the choice at original [position]. */
    fun tap(position: Int): Result {
        if (isComplete) return Result.Complete
        val expected = progress.size
        // Duplicates: any position carrying the expected word is accepted.
        if (position in progress || words[position] != words[expected]) {
            progress = emptyList()
            return Result.Wrong(expectedNumber = expected + 1)
        }
        progress = progress + position
        return if (isComplete) Result.Complete else Result.Correct
    }

    sealed interface Result {
        data object Correct : Result
        data object Complete : Result

        /** [expectedNumber] is the 1-based number of the word that was due. */
        data class Wrong(val expectedNumber: Int) : Result
    }

    private companion object {
        fun shuffledAwayFromIdentity(list: List<Int>, random: SecureRandom): List<Int> {
            if (list.size < 2) return list
            var shuffled: List<Int>
            do {
                shuffled = list.shuffled(random)
            } while (shuffled == list)
            return shuffled
        }
    }
}
