package whitewallet.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.TrustDefault

/** Every mint of the wallet, with the account's money there: home first, then by money, then by name; what each card says. */
class MintCardsTest {
    private val key = "02" + "ab".repeat(32)

    private fun info(host: String, trusted: Boolean = true, home: Boolean = false) =
        MintInfo(host, key, trusted, if (home) TrustDefault.Home else null, isHome = home)

    private fun held(host: String, msat: Long, notes: Int = 1, home: Boolean = false, addresses: Int = 0, answering: Boolean = true) =
        AccountMint(host, home, msat, notes, addresses, trusted = true, answering = answering)

    @Test
    fun `the home comes first even with less money`() {
        val cards =
            mintCards(
                listOf(info("big.example"), info("home.example", home = true)),
                listOf(held("home.example", 1, home = true), held("big.example", 9_000)),
                emptySet(),
            )
        assertEquals(listOf("home.example", "big.example"), cards.map { it.host })
        assertTrue(cards.first().isHome)
    }

    @Test
    fun `then the mints with money, most first`() {
        val cards =
            mintCards(
                listOf(info("a.example"), info("b.example"), info("c.example")),
                listOf(held("a.example", 1_000), held("b.example", 5_000), held("c.example", 3_000)),
                emptySet(),
            )
        assertEquals(listOf("b.example", "c.example", "a.example"), cards.map { it.host })
    }

    @Test
    fun `then the mints without money, by name`() {
        val cards = mintCards(listOf(info("z.example"), info("m.example"), info("a.example")), listOf(held("m.example", 2_000)), emptySet())
        assertEquals(listOf("m.example", "a.example", "z.example"), cards.map { it.host })
    }

    @Test
    fun `equal money goes by name`() {
        val cards =
            mintCards(listOf(info("y.example"), info("x.example")), listOf(held("y.example", 1_000), held("x.example", 1_000)), emptySet())
        assertEquals(listOf("x.example", "y.example"), cards.map { it.host })
    }

    @Test
    fun `a mint the account holds notes on but the wallet doesn't list is shown, untrusted`() {
        val cards = mintCards(emptyList(), listOf(held("lost.example", 1_000)), emptySet())
        assertEquals(listOf("lost.example"), cards.map { it.host })
        assertFalse(cards.single().trusted)
    }

    @Test
    fun `a home the wallet doesn't list still comes first`() {
        val cards = mintCards(listOf(info("other.example")), listOf(held("home.example", 0, notes = 0, home = true)), emptySet())
        assertEquals(listOf("home.example", "other.example"), cards.map { it.host })
        assertTrue(cards.first().isHome)
        assertFalse(cards.first().trusted)
    }

    @Test
    fun `a mint whose key changed is never shown as trusted`() {
        val cards = mintCards(listOf(info("a.example", trusted = true)), emptyList(), setOf("a.example"))
        assertTrue(cards.single().keyChanged)
        assertFalse(cards.single().trusted)
    }

    @Test
    fun `money, notes and addresses come from the account`() {
        val card = mintCards(listOf(info("a.example")), listOf(held("a.example", 4_000, notes = 2, addresses = 1)), emptySet()).single()
        assertEquals(4_000, card.balanceMsat)
        assertEquals(2, card.noteCount)
        assertEquals(1, card.addresses)
        assertTrue(card.used)
    }

    @Test
    fun `a mint the account has nothing on is not used`() {
        assertFalse(mintCards(listOf(info("a.example")), emptyList(), emptySet()).single().used)
    }

    @Test
    fun `a mint the account still has notes on out of its balance is used, without money`() {
        val card = mintCards(listOf(info("a.example")), emptyList(), emptySet(), holding = setOf("a.example")).single()
        assertTrue(card.used)
        assertEquals(0L, card.balanceMsat)
    }

    @Test
    fun `the home is used even when empty`() {
        assertTrue(
            mintCards(listOf(info("h.example", home = true)), listOf(held("h.example", 0, notes = 0, home = true)), emptySet())
                .single()
                .used
        )
    }

    @Test
    fun `whether it answers comes from the account's mints, and a mint it doesn't hold counts as answering`() {
        val cards = mintCards(listOf(info("a.example"), info("b.example")), listOf(held("a.example", 1_000, answering = false)), emptySet())
        assertFalse(cards.first { it.host == "a.example" }.answering)
        assertTrue(cards.first { it.host == "b.example" }.answering)
    }

    @Test
    fun `trust comes from the wallet's mints`() {
        val cards = mintCards(listOf(info("a.example", trusted = false)), listOf(held("a.example", 1_000)), emptySet())
        assertFalse(cards.single().trusted)
    }

    @Test
    fun `home is the account's own, never a mint the wallet marks home that the account holds nothing on`() {
        val cards =
            mintCards(
                listOf(info("x.example"), info("y.example", home = true)),
                listOf(held("x.example", 1_000, home = true)),
                emptySet(),
            )
        assertEquals(listOf("x.example"), cards.filter { it.isHome }.map { it.host })
    }

    @Test
    fun `no mints give no cards`() {
        assertEquals(emptyList(), mintCards(emptyList(), emptyList(), emptySet()))
    }
}
