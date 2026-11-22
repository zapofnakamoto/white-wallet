@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.PrivateKey
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.Requests

/**
 * A mutation the mint refuses as `already in use` names either a burned input or an output key the mint holds already: on the real mint,
 * the inputs are looked up first, and only a clash on a new key is tried again with fresh ones.
 */
class RegtestInUseTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /**
     * Counts the mutations that reach the mint. While [spendFirst], the first one's input is spent elsewhere just before it arrives, as by
     * another phone with the phrase.
     */
    private class Wire : okhttp3.Interceptor {
        var spendFirst = false
        var mutations = 0

        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val url = chain.request().url
            if (url.isMutation) {
                if (spendFirst) {
                    spendFirst = false
                    val k1 = url.queryParameter("k1")!!
                    val callback = url.newBuilder().query(null).build().toString()
                    val elsewhere = PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)).publicKey().xOnly().value
                    val outcome = runBlocking { LnurlcashClient().mutate(MutationType.Rotate, Requests.rotate(callback, k1, elsewhere)) }
                    check(outcome is MutationOutcome.Confirmed) { "spent elsewhere: $outcome" }
                }
                mutations++
            }
            return chain.proceed(chain.request())
        }
    }

    @Test
    fun `a split whose input another phone spent first is refused once, not tried again on fresh keys`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            alice.fund(3000)
            val input = alice.notes().single()
            val keysBefore = alice.store.indexOf(alice.wallet.activeId(), Regtest.HOST, KeySet.Wallet)

            wire.spendFirst = true
            wire.mutations = 0
            assertIs<CreateNoteResult.Failed>(alice.wallet.createNote(1_000_000))
            assertEquals(1, wire.mutations, "its input was looked up, and found spent: no fresh keys tried")
            assertNotEquals(NoteState.Outstanding, alice.store.note(input.id)?.state, "set aside, as any spent input")
            assertEquals(0, alice.balance())
            assertTrue(
                alice.store.indexOf(alice.wallet.activeId(), Regtest.HOST, KeySet.Wallet) <= keysBefore + 2,
                "no run of unused keys handed out",
            )
        }
}
