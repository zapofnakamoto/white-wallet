package whitewallet.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.brand.WalletStatus
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.NotesCheck

/** What Home makes of received notes: shown apart from the balance, counted as incoming while they may still arrive. */
class HomeStateTest {
    private fun received(
        id: String,
        state: ReceivedState,
        amountMsat: Long? = null,
        declaredMsat: Long? = null,
        host: String = "mint.example",
    ) = ReceivedNote(id, "a", host, "w", "k1", null, declaredMsat, amountMsat, null, null, state, null, 1, 1)

    @Test
    fun `claimed notes aren't shown, the others are, in their order`() {
        val notes = ReceivedState.entries.map { received(it.name, it) }
        assertEquals(ReceivedState.entries - ReceivedState.Claimed, ReceivedNote.shown(notes).map { it.state })
    }

    @Test
    fun `incoming counts what may still arrive, by the mint's amount or else the link's`() {
        val state =
            HomeState(
                activeAccountId = "a",
                received =
                    listOf(
                        received("w", ReceivedState.Waiting, declaredMsat = 1_000),
                        received("l", ReceivedState.Locked, amountMsat = 2_000, declaredMsat = 9_999),
                        received("k", ReceivedState.KeyChanged, amountMsat = 4_000),
                        received("c", ReceivedState.Claiming, amountMsat = 8_000),
                        received("u", ReceivedState.Waiting),
                        received("g", ReceivedState.Gone, amountMsat = 100_000),
                        received("r", ReceivedState.Refused, amountMsat = 100_000),
                    ),
            )
        assertEquals(15_000, state.incomingMsat)
    }

    @Test
    fun `a wallet with only incoming money isn't empty, one with only notes it can no longer claim is`() {
        assertEquals(WalletStatus.Idle, HomeState(activeAccountId = "a", received = listOf(received("w", ReceivedState.Waiting))).status)
        assertEquals(
            WalletStatus.Empty,
            HomeState(activeAccountId = "a", received = listOf(received("g", ReceivedState.Gone), received("r", ReceivedState.Refused)))
                .status,
        )
    }

    @Test
    fun `a wallet whose money is all in notes handed out isn't empty`() {
        assertEquals(WalletStatus.Idle, HomeState(activeAccountId = "a", sharedMsat = 1_000).status)
    }

    @Test
    fun `asking again about unknown notes tells one thing, good news first, and nothing when there was nothing to ask`() {
        assertEquals(UnknownCheck.Back(1), UnknownCheck.of(NotesCheck(1, 0, listOf("b.example"), unknown = 2)))
        assertEquals(UnknownCheck.Unreachable("b.example"), UnknownCheck.of(NotesCheck(0, 0, listOf("b.example"), unknown = 2)))
        assertEquals(UnknownCheck.Still(2), UnknownCheck.of(NotesCheck(0, 0, emptyList(), unknown = 2)))
        assertEquals(null, UnknownCheck.of(NotesCheck(0, 0, emptyList())))
    }

    private fun mint(host: String, msat: Long, home: Boolean = false) = AccountMint(host, home, msat, 1, 0, trusted = true)

    @Test
    fun `the balance is told per mint only once it is on several, and where mints and the balance show`() {
        val two = listOf(mint("a.example", 6_000, home = true), mint("b.example", 2_000))
        assertEquals(two, HomeState(mints = two).balanceByMint)
        assertEquals(emptyList(), HomeState(mints = two.take(1)).balanceByMint)
        assertEquals(emptyList(), HomeState(mints = two, mintsVisible = false).balanceByMint)
        assertEquals(emptyList(), HomeState(mints = two, balanceHidden = true).balanceByMint)
    }

    @Test
    fun `an empty home mint has its row once money is on another mint`() {
        val mints = listOf(mint("a.example", 0, home = true), mint("b.example", 2_000))
        assertEquals(mints, HomeState(mints = mints).balanceByMint)
        // Without a home mint, one mint is the total alone.
        assertEquals(emptyList(), HomeState(mints = mints.drop(1)).balanceByMint)
    }

    @Test
    fun `up to four mints have a row each, more share a last one`() {
        val mints = listOf(mint("home.example", 9_000, home = true)) + (1..9).map { mint("m$it.example", 1_000L * (10 - it)) }
        val four = HomeState(mints = mints.take(4))
        assertEquals(4, four.mintRows.size)
        assertEquals(emptyList(), four.moreMints)
        val ten = HomeState(mints = mints)
        assertEquals(listOf("home.example", "m1.example", "m2.example"), ten.mintRows.map { it.host })
        assertEquals(7, ten.moreMints.size)
        assertEquals(ten.mints.sumOf { it.balanceMsat }, (ten.mintRows + ten.moreMints).sumOf { it.balanceMsat })
    }

    @Test
    fun `the key change a refresh found comes first, then a received note's`() {
        val notes =
            listOf(received("w", ReceivedState.Waiting, host = "a.example"), received("k", ReceivedState.KeyChanged, host = "b.example"))
        assertEquals("b.example", HomeState(received = notes).keyChangedMint)
        assertEquals("c.example", HomeState(received = notes, keyChangedHost = "c.example").keyChangedMint)
        assertEquals(null, HomeState(received = notes.take(1)).keyChangedMint)
    }

    @Test
    fun `alerts come the notes first, then what keeps money from coming in, then the reminders`() {
        val unknown = AccountMint("u.example", false, 0, 0, 0, trusted = true, unknownCount = 1, unknownMsat = 1_000)
        val state =
            HomeState(
                keyChangedHost = "k.example",
                unknownNotes = listOf(unknown),
                inactiveAddress = "me@mint.example",
                showAddMintPrompt = true,
                showBackupPrompt = true,
                showScanPrompt = true,
            )
        assertEquals(
            listOf(
                HomeAlert.KeyChanged("k.example"),
                HomeAlert.UnknownNotes(unknown),
                HomeAlert.AddressInactive("me@mint.example"),
                HomeAlert.AddMint,
                HomeAlert.Backup,
                HomeAlert.Scan,
            ),
            state.alerts,
        )
        assertEquals(emptyList(), HomeState().alerts)
    }
}
