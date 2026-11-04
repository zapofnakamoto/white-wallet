package whitewallet.lnurlcash

/**
 * Marks what gives out a note's secret: its spend (`k1`) or the parts it is made of, or a link that carries it. Whoever gets the secret can
 * claim the note, so it is read only where it must be: sent in the mutation that spends the note (through the wallet's one gate), stored,
 * or handed out on purpose (a link the user shares). Every such place opts in with `@OptIn(RevealsNoteSecret::class)`, as narrowly as it
 * can, so a search lists them all.
 */
@RequiresOptIn(
    message =
        "Reveals a note's secret: whoever gets it can claim the note. Check a note by its key (Q) instead; " +
            "send a spend only through the wallet's SecretGate.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class RevealsNoteSecret
