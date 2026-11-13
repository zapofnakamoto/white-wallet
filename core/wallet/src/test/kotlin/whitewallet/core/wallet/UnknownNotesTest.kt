package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * When the wallet asks a mint again about a note it set aside as unknown ([UnknownNotes.recheckDue]), against a mint that never knows it.
 */
class UnknownNotesTest {
    private var clock = 1_800_000_000_000L

    @Test
    fun `a note past a grace period shorter than a day is asked about at the interval after it`() =
        runBlocking<Unit> {
            NoAddressTest.FakeMint(0) { clock }
                .use { mint ->
                    val store = InMemoryWalletStore()
                    val settings =
                        WalletSettings(
                            unknownNoteGracePeriodMillis = HOUR,
                            unknownNoteCheckIntervalInGracePeriodMillis = 5 * MINUTE,
                            unknownNoteCheckIntervalAfterGracePeriodMillis = DAY,
                        )
                    val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock }, settings = settings)
                    val accountId = (wallet.createAccount(mint.host, "Main") as CreateAccountResult.Created).accountId
                    val q = wallet.keys(accountId, mint.host).pubkey(KeySet.Wallet, 900).toHex()
                    val setAsideAt = clock
                    val note =
                        Note(
                            q,
                            accountId,
                            mint.host,
                            NoteKind.KeyPath,
                            KeySet.Wallet.purpose,
                            900,
                            5_000_000,
                            null,
                            NoteState.Unknown,
                            setAsideAt,
                            scheme = KeyScheme.Bip32,
                        )
                    store.upsertNotes(listOf(note))
                    val unknownNotes = UnknownNotes(wallet)
                    suspend fun askedAt(): Long? {
                        unknownNotes.recheckDue(accountId)
                        return wallet.unknownCheckedAt[note.id]
                    }

                    assertEquals(setAsideAt, askedAt(), "a note never asked about is due")
                    clock += 5 * MINUTE
                    assertEquals(clock, askedAt(), "in the grace period, asked again after 5 minutes")
                    val lastAsked = clock
                    clock = setAsideAt + 2 * HOUR
                    assertEquals(lastAsked, askedAt(), "past the grace period, not asked again after 5 minutes, though it is within a day")
                    clock = lastAsked + DAY
                    assertEquals(clock, askedAt(), "past the grace period, asked again a day after the last time")
                }
        }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
