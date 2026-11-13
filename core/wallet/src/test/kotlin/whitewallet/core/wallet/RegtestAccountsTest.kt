package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurlcash.client.LnurlcashClient

/** Moving money between two accounts, of one wallet or of two, on the real mint. */
class RegtestAccountsTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /** Main (active, funded with [sats]) and an empty Savings account, both with an address on the mint. */
    private suspend fun accounts(vararg sats: Long, client: LnurlcashClient = LnurlcashClient()): Triple<Regtest.Phone, String, String> {
        val phone = Regtest.phone(client = client)
        val main = assertNotNull(phone.store.currentAccountId())
        assertIs<OnboardResult.Ready>(phone.wallet.onboard(Regtest.name(), HOST, newAccountName = "Savings"))
        val savings = assertNotNull(phone.store.currentAccountId())
        phone.wallet.switchAccount(main)
        phone.fund(*sats)
        return Triple(phone, main, savings)
    }

    private suspend fun Regtest.Phone.balances() = wallet.accounts.first().associate { it.account.id to it.balanceMsat }

    @Test
    fun `moving everything rotates a single note into the other account, at no fee`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts(2000)
            assertEquals(MoveResult.Moved(1_999_000), phone.wallet.moveFunds(savings, null))
            assertEquals(mapOf(main to 0L, savings to 1_999_000L), phone.balances())
            val out = phone.wallet.activity.first().single { it.kind == ActivityKind.MovedOut }
            assertEquals("Savings", out.counterparty)
        }

    @Test
    fun `moving everything merges several notes into one note of the other account`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts(1000, 2000)
            val moved = assertIs<MoveResult.Moved>(phone.wallet.moveFunds(savings, null))
            assertTrue(moved.amountMsat >= 2_998_000, "a merge never loses value")
            assertEquals(mapOf(main to 0L, savings to moved.amountMsat), phone.balances())
            phone.wallet.switchAccount(savings)
            assertEquals(1, phone.store.notes(savings).first().size)
        }

    @Test
    fun `moving more than any single note merges first, then splits`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts(1000, 2000)
            assertEquals(MoveResult.Moved(2_500_000), phone.wallet.moveFunds(savings, 2_500_000))
            val balances = phone.balances()
            assertEquals(2_500_000, balances[savings])
            assertTrue(balances[main]!! >= 2_998_000 - 2_500_000 - 1_000, "the change, less at most the split fee: $balances")
        }

    @Test
    fun `moving more than the balance is refused without touching anything`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts(2000)
            val before = phone.notes().map { it.id }
            assertEquals(MoveFailure.InsufficientFunds, assertIs<MoveResult.Failed>(phone.wallet.moveFunds(savings, 5_000_000)).reason)
            assertEquals(before, phone.notes().map { it.id })
            assertEquals(mapOf(main to 1_999_000L, savings to 0L), phone.balances())
        }

    @Test
    fun `moving to an account of another wallet adds the mint to that wallet, whose scan comes to the same note`() =
        runBlocking<Unit> {
            val phone = Regtest.phone()
            val main = assertNotNull(phone.store.currentAccountId())
            assertIs<CreateAccountResult.Created>(phone.wallet.createWallet("Other"))
            phone.wallet.setSetupStep(null)
            val other = assertNotNull(phone.store.currentAccountId())
            phone.wallet.switchAccount(main)
            phone.fund(2000)
            assertEquals(null, phone.store.mint(other, HOST), "the other wallet has no mint yet")
            assertEquals(listOf(HOST), phone.wallet.moveAddsMints(other, null))

            assertEquals(MoveResult.Moved(1_999_000), phone.wallet.moveFunds(other, null))
            assertEquals(MintScan.Never, phone.store.mint(other, HOST)?.scan)
            assertEquals(null, phone.store.account(other)?.homeHost, "a mint met by a moved note is nobody's home")
            assertEquals(phone.store.mint(main, HOST)?.mintPubkey, phone.store.mint(other, HOST)?.mintPubkey)
            assertEquals(mapOf(main to 0L, other to 1_999_000L), phone.balances())

            // The other wallet, once in use, looks through its keys at the mint as a restore does: the note is where it looks.
            phone.wallet.switchAccount(other)
            phone.wallet.scan()
            assertEquals(MintScan.Finished, phone.store.mint(other, HOST)?.scan)
            assertEquals(mapOf(main to 0L, other to 1_999_000L), phone.balances())
            assertEquals(1, phone.store.notes(other).first().size)
        }

    /**
     * A connection that drops every request to the mint at [down], which never sees them; while [changed], mints present another key, and
     * while [keyless], none. The first note-changing request to the mint at [lose] reaches it, its answer is lost, and that mint is [down]
     * from then on.
     */
    private class Connection : Interceptor {
        var down: String? = null
        var changed = false
        var keyless = false
        var lose: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val host = "${url.host}:${url.port}"
            if (host == down) Regtest.dropped()
            if (host == lose && url.isMutation) {
                lose = null
                down = host
                chain.proceed(chain.request()).close()
                Regtest.lost()
            }
            val response = chain.proceed(chain.request())
            return when {
                changed -> Regtest.presentingOtherKey(response)
                keyless -> Regtest.withoutSigningKey(response)
                else -> response
            }
        }
    }

    /** A phone with money at both mints in its first account, and a second wallet without any mint: the two account ids. */
    private suspend fun twoMints(phone: Regtest.Phone): Pair<String, String> {
        val main = assertNotNull(phone.store.currentAccountId())
        phone.fund(2000)
        val note = assertIs<ClaimPreview.Ready>(phone.wallet.previewClaim(Regtest.note(3000, second = true)))
        assertIs<ClaimResult.Claimed>(phone.wallet.claim(note))
        assertIs<CreateAccountResult.Created>(phone.wallet.createWallet("Other"))
        phone.wallet.setSetupStep(null)
        val other = assertNotNull(phone.store.currentAccountId())
        phone.wallet.switchAccount(main)
        return main to other
    }

    @Test
    fun `a move of everything with one mint out of reach is known before it is made, moves the rest, and the rest of it later`() =
        runBlocking<Unit> {
            val connection = Connection()
            val phone = Regtest.phone(client = Regtest.client(connection))
            val (main, other) = twoMints(phone)
            val second = phone.notes(HOST2).sumOf { it.amountMsat }
            val stayed = listOf(MoveStayed(HOST2, second, StayReason.Unreachable))

            connection.down = HOST2
            assertEquals(MoveCheck(1_999_000, stayed), phone.wallet.checkMoveFunds(other, null))
            assertEquals(null, phone.store.mint(other, HOST), "asking adds no mint")
            assertEquals(mapOf(main to 1_999_000 + second, other to 0L), phone.balances())

            assertEquals(MoveResult.Moved(1_999_000, stayed), phone.wallet.moveFunds(other, null))
            assertEquals(mapOf(main to second, other to 1_999_000L), phone.balances())
            assertEquals(null, phone.store.mint(other, HOST2), "a mint that didn't answer isn't added")

            // The mint answers again: the same move takes what stayed.
            connection.down = null
            assertEquals(MoveCheck(second, emptyList()), phone.wallet.checkMoveFunds(other, null))
            val rest = assertIs<MoveResult.Moved>(phone.wallet.moveFunds(other, null))
            assertEquals(emptyList(), rest.stayed)
            assertEquals(0L, phone.balances().getValue(main))
            assertEquals(1_999_000 + rest.amountMsat, phone.balances().getValue(other))
            assertEquals(null, phone.store.account(other)?.homeHost)
        }

    @Test
    fun `a mint that shows another signing key keeps its notes, as the check says, and the other mint's notes move`() =
        runBlocking<Unit> {
            val connection = Connection()
            val phone = Regtest.phone(client = Regtest.client(connection))
            val (main, other) = twoMints(phone)
            val held = 1_999_000 + phone.notes(HOST2).sumOf { it.amountMsat }

            connection.changed = true
            val check = phone.wallet.checkMoveFunds(other, null)
            assertEquals(0, check.movableMsat)
            assertEquals(
                setOf(HOST to StayReason.KeyChanged, HOST2 to StayReason.KeyChanged),
                check.stayed.map { it.host to it.reason }.toSet(),
            )
            val failed = assertIs<MoveResult.Failed>(phone.wallet.moveFunds(other, null))
            assertEquals(check.stayed.toSet(), failed.stayed.toSet())
            assertEquals(mapOf(main to held, other to 0L), phone.balances())
            assertEquals(emptyList(), phone.store.mints(other).first(), "no mint was added")
        }

    @Test
    fun `a mint that shows no signing key keeps its notes from another account of the wallet, as the check says`() =
        runBlocking<Unit> {
            val connection = Connection()
            val (phone, main, savings) = accounts(2000, client = Regtest.client(connection))

            connection.keyless = true
            val check = phone.wallet.checkMoveFunds(savings, null)
            assertEquals(MoveCheck(0, listOf(MoveStayed(HOST, 1_999_000, StayReason.KeyChanged))), check)
            val failed = assertIs<MoveResult.Failed>(phone.wallet.moveFunds(savings, null))
            assertEquals(check.stayed, failed.stayed)
            assertEquals(mapOf(main to 1_999_000L, savings to 0L), phone.balances())
        }

    @Test
    fun `a mint whose answer is lost leaves its notes open, the other mint's notes move, and a refresh finds them moved`() =
        runBlocking<Unit> {
            val connection = Connection()
            val phone = Regtest.phone(client = Regtest.client(connection))
            val (main, other) = twoMints(phone)
            val second = phone.notes(HOST2).sumOf { it.amountMsat }

            connection.lose = HOST2
            assertEquals(
                MoveResult.Moved(1_999_000, pending = listOf(MovePending(HOST2, second))),
                phone.wallet.moveFunds(other, null),
            )
            assertEquals(mapOf(main to 0L, other to 1_999_000L), phone.balances(), "what is open is in neither balance")
            assertEquals(1, phone.wallet.unresolvedOperations())

            connection.down = null
            phone.wallet.refresh()
            assertEquals(0, phone.wallet.unresolvedOperations())
            assertEquals(0L, phone.balances().getValue(main))
            assertTrue(phone.balances().getValue(other) > 1_999_000L, "the second mint's notes arrived")
            assertNotNull(phone.store.mint(other, HOST2), "the other wallet has the mint its note is at")
        }
}
