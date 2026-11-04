@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteLink

/**
 * Every kind of note a link can carry is checked by its key alone and claimed with one request that carries its spend: online right away,
 * offline kept and claimed by the first refresh once the connection is back.
 */
class RegtestNoteKindsTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /** Drops every request while [offline]; writes down every request that carries a spend. */
    private class Wire : Interceptor {
        var offline = false
        val spends = mutableListOf<HttpUrl>()

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) Regtest.dropped()
            chain.request().url.takeIf { it.queryParameter("k1") != null }?.let(spends::add)
            return chain.proceed(chain.request())
        }
    }

    /** A link of each kind, made by someone other than the receiver (its own shared note is taken back: RegtestSharedNotesTest). */
    private val kinds: Map<String, suspend () -> String> =
        mapOf(
            "bearer, hex" to { Regtest.note(1000) },
            "bearer, cw1" to
                {
                    val link = assertNotNull(NoteLink.parse(Regtest.note(1000)))
                    link.copy(spend = assertIs<Spend.Bearer>(link.spend).copy(asCw1 = true)).encode()
                },
            "key path, another wallet's" to
                {
                    val sender = Regtest.phone()
                    sender.fund(2000)
                    assertNotNull(assertIs<CreateNoteResult.Created>(sender.wallet.createNote(1_000_000)).note.link)
                },
            "script, one checksig leaf" to
                {
                    val sender = Regtest.phone()
                    sender.fund(2000)
                    val note = LockedNote.checksig()
                    sender.lockTo(note.q)
                    note.link(HOST, assertNotNull(sender.store.mint(HOST)).withdrawUrl)
                },
        )

    @Test
    fun `online, every kind is claimed with one request carrying its spend, to its mint`() =
        runBlocking<Unit> {
            for ((kind, make) in kinds) {
                val wire = Wire()
                val receiver = Regtest.phone(client = Regtest.client(wire))
                val link = make()
                val before = receiver.balance()
                wire.spends.clear()

                val preview = assertIs<ClaimPreview.Ready>(receiver.wallet.previewClaim(link), kind)
                assertEquals(emptyList(), wire.spends, "$kind: no spend to look at it")
                assertIs<ClaimResult.Claimed>(receiver.wallet.claim(preview), kind)
                assertEquals(1, wire.spends.size, "$kind: one request carried the spend")
                assertTrue(wire.spends.single().isMutation && "${wire.spends.single().host}:${wire.spends.single().port}" == HOST, kind)
                assertEquals(before + preview.amountMsat, receiver.balance(), "$kind: the money is in the wallet")
            }
        }

    @Test
    fun `offline, every kind is kept with its spend and claimed by the first refresh online`() =
        runBlocking<Unit> {
            for ((kind, make) in kinds) {
                val wire = Wire()
                val receiver = Regtest.phone(client = Regtest.client(wire))
                val link = make()
                val before = receiver.balance()

                wire.offline = true
                val preview = assertIs<ClaimPreview.Offline>(receiver.wallet.previewClaim(link), kind)
                assertEquals(ReceivedState.Waiting, receiver.wallet.keepClaim(preview).note.state, kind)
                assertEquals(before, receiver.balance(), kind)

                wire.offline = false
                wire.spends.clear()
                receiver.wallet.refresh()
                assertEquals(ReceivedState.Claimed, receiver.wallet.receivedNotes.first().single().state, kind)
                assertEquals(1, wire.spends.size, "$kind: one request carried the spend")
                assertTrue(receiver.balance() > before, "$kind: the money is in the wallet")
            }
        }
}
