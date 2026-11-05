package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.KeyScheme
import whitewallet.core.wallet.Regtest.HOST4
import whitewallet.core.wallet.legacy.LegacyChange
import whitewallet.core.wallet.legacy.LegacyWallet
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind

/** A mint moving from lnurl-mint 0.12.2 to 0.13 under wallets that registered on it: what a production upgrade looks like to them. */
class RegtestMintUpgradeTest {
    @BeforeEach fun requireStack() = Regtest.requireStack(MintDialect.Current)

    @Test
    fun `a mint's upgrade moves the wallet's notes and address on by themselves, and a restored phone takes its address back`() =
        runBlocking<Unit> {
            Regtest.resetMint4()
            val alice = Regtest.phone(host = HOST4)
            val bob = Regtest.phone(host = HOST4)
            alice.fund(2000)
            bob.fund(3000)
            val aliceId = alice.wallet.activeId()
            assertEquals(listOf(KeyScheme.Legacy), alice.notes(HOST4).map { it.scheme }, "address notes stay put on a legacy mint")
            assertNull(alice.store.addresses(aliceId).first().single().share)

            Regtest.upgradeMint4()

            // Paid before this wallet has heard of the upgrade: the mint still credits the old keys.
            Regtest.pay(alice.address, 1000)
            alice.wallet.refresh()
            assertEquals(0L, alice.store.addresses(aliceId).first().single().share, "moved to a share")
            assertEquals(1_999_000 + 999_000, alice.balance(), "nothing lost, and the move is free")
            assertEquals(setOf(KeyScheme.Bip32), alice.notes(HOST4).map { it.scheme }.toSet(), "swept: ${alice.notes(HOST4)}")
            assertEquals(AddressStatus.Ours, alice.wallet.checkAddress(alice.address.substringBefore('@'), HOST4))
            assertTrue(alice.wallet.activity.first().none { it.kind == ActivityKind.Rotated }, "silently")

            Regtest.pay(alice.address, 500)
            assertEquals(499_000, alice.wallet.refresh().receivedMsat)
            assertEquals(1, alice.notes(HOST4).count { it.share == 0L })

            // Bob's old phone never came back. Only the branch key on file can prove the name: claiming it moves it.
            val store = InMemoryWalletStore()
            val restored = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            assertIs<RestoreResult.Restored>(restored.restore(bob.wallet.recoveryPhrase().joinToString(" "), HOST4, "Main"))
            val name = bob.address.substringBefore('@')
            assertTrue(assertIs<OnboardResult.Ready>(restored.onboard(name, HOST4)).confirmed)
            assertEquals(0L, store.addresses(restored.activeId()).first().single().share)
            Regtest.pay(bob.address, 1000)
            restored.refresh()
            assertEquals(2_999_000 + 999_000, restored.balanceMsat.first())
        }

    @Test
    fun `a further account can't take the first account's address, though the old branch key would prove it`() =
        runBlocking<Unit> {
            Regtest.resetMint4()
            val carol = Regtest.phone(host = HOST4)
            val first = carol.wallet.activeId()
            val name = carol.address.substringBefore('@')
            Regtest.upgradeMint4()

            assertIs<OnboardResult.UsernameTaken>(carol.wallet.onboard(name, HOST4, "Savings", inActiveWallet = true))
            assertEquals(first, carol.wallet.activeId())
            carol.wallet.refresh()
            assertEquals(0L, carol.store.addresses(first).first().single().share, "the first account moved it itself")
        }

    /** What an old app left behind: notes on the legacy wallet and change keys, besides the address payments the mint put on purpose 2. */
    private suspend fun oldNotes(phone: Regtest.Phone) {
        val keys = phone.wallet.keys(phone.wallet.activeId(), HOST4)
        Regtest.mintTo(HOST4, keys.pubkey(KeySet.LegacyWallet, 0), 3000)
        Regtest.mintTo(HOST4, keys.pubkey(KeySet.LegacyWallet, 1), 4000)
        Regtest.mintTo(HOST4, keys.pubkey(KeySet.LegacyChange, 0), 5000)
    }

    private val oldTotal = 1_999_000L + 2_999_000 + 3_999_000 + 4_999_000

    /** Everything [phone] holds is on the new wallet chain or a share, and spends: a payment out, for real. */
    private suspend fun assertAllNewAndSpendable(phone: Regtest.Phone) {
        val notes = phone.notes(HOST4)
        assertEquals(setOf(KeyScheme.Bip32), notes.map { it.scheme }.toSet(), "$notes")
        val invoice = Regtest.invoice(1500)
        val preview = assertIs<PayPreview.Ready>(phone.wallet.previewPayment(invoice))
        assertIs<PayResult.Paid>(phone.wallet.pay(preview, 1_500_000))
        assertTrue(Regtest.settled(invoice))
    }

    @Test
    fun `every legacy path moves on with a mint's upgrade - wallet, change and address notes, then the address's payments on its share`() =
        runBlocking<Unit> {
            Regtest.resetMint4()
            val phone = Regtest.phone(host = HOST4)
            val id = phone.wallet.activeId()
            phone.fund(2000)
            oldNotes(phone)
            phone.wallet.scanKeysAhead(HOST4, KeySet.LegacyWallet)
            phone.wallet.scanKeysAhead(HOST4, KeySet.LegacyChange)
            val old = phone.notes(HOST4)
            assertEquals(
                mapOf(0 to 2, 1 to 1, 2 to 1),
                old.groupingBy { it.purpose!! }.eachCount(),
                "two wallet notes, one change, one address: $old",
            )
            assertEquals(setOf(KeyScheme.Legacy), old.map { it.scheme }.toSet())
            assertEquals(oldTotal, phone.balance())

            Regtest.upgradeMint4()
            phone.wallet.refresh()

            val moved = phone.notes(HOST4)
            assertEquals(4, moved.size, "$moved")
            assertTrue(moved.all { it.scheme == KeyScheme.Bip32 && it.purpose == 0 }, "on the new wallet chain: $moved")
            assertEquals(oldTotal, phone.balance(), "nothing lost, and the move is free")
            assertTrue(phone.wallet.activity.first().none { it.kind == ActivityKind.Rotated }, "silently")

            // Burned at the mint: no old key can spend or show them any more.
            val client = LnurlcashClient()
            val withdraw = client.servicePayRequest(HOST4).withdrawUrl
            for (note in old) {
                val gone = assertFailsWith<NoteLookupError> { client.lookup(withdraw, ByteVector32(note.id)) }
                assertEquals(RejectionKind.Spent, gone.kind, gone.reason)
            }

            val name = phone.address.substringBefore('@')
            assertEquals(0L, phone.store.addresses(id).first().single().share)
            assertEquals(AddressStatus.Ours, phone.wallet.checkAddress(name, HOST4))
            Regtest.pay(phone.address, 1000)
            assertEquals(999_000, phone.wallet.refresh().receivedMsat)
            val paid = phone.notes(HOST4).single { it.purpose == 2 }
            assertEquals(KeyScheme.Bip32 to 0L, paid.scheme to paid.share)
            assertEquals(999_000, paid.amountMsat)

            phone.wallet.refresh()
            assertEquals(oldTotal + 999_000, phone.balance(), "counted once")
            assertAllNewAndSpendable(phone)
        }

    @Test
    fun `a phone restored after the upgrade finds what every legacy path holds, and moves it on`() =
        runBlocking<Unit> {
            Regtest.resetMint4()
            val gone = Regtest.phone(host = HOST4)
            gone.fund(2000)
            oldNotes(gone)
            Regtest.upgradeMint4()

            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            assertIs<RestoreResult.Restored>(wallet.restore(gone.wallet.recoveryPhrase().joinToString(" "), HOST4, "Main"))
            val name = gone.address.substringBefore('@')
            assertTrue(assertIs<OnboardResult.Ready>(wallet.onboard(name, HOST4)).confirmed)
            val phone = Regtest.Phone(store, wallet, gone.address)
            assertEquals(oldTotal, phone.balance(), "all four found: ${phone.notes(HOST4)}")

            wallet.refresh()
            assertEquals(oldTotal, phone.balance())
            assertEquals(setOf(KeyScheme.Bip32), phone.notes(HOST4).map { it.scheme }.toSet(), "${phone.notes(HOST4)}")
            assertEquals(0L, store.addresses(wallet.activeId()).first().single().share)

            Regtest.pay(gone.address, 1000)
            assertEquals(999_000, wallet.refresh().receivedMsat)
            assertEquals(0L, phone.notes(HOST4).single { it.purpose == 2 }.share)
            assertAllNewAndSpendable(phone)
        }

    @Test
    fun `legacy 2f17578 - before its mint upgrades, wallet and change notes move on and address notes stay, then follow after the upgrade`() =
        runBlocking<Unit> {
            Regtest.resetMint4()
            val phone = Regtest.phone(host = HOST4)
            phone.fund(2000)
            oldNotes(phone)
            phone.wallet.scanKeysAhead(HOST4, KeySet.LegacyWallet)
            phone.wallet.scanKeysAhead(HOST4, KeySet.LegacyChange)

            phone.wallet.refresh()
            val notes = phone.notes(HOST4)
            assertEquals(oldTotal, phone.balance(), "$notes")
            assertEquals(listOf(KeyScheme.Legacy), notes.filter { it.purpose == 2 }.map { it.scheme })
            assertTrue(notes.filter { it.purpose != 2 }.all { it.scheme == KeyScheme.Bip32 }, "$notes")

            Regtest.upgradeMint4()
            phone.wallet.refresh()
            assertEquals(oldTotal, phone.balance())
            assertEquals(setOf(KeyScheme.Bip32), phone.notes(HOST4).map { it.scheme }.toSet(), "${phone.notes(HOST4)}")
        }
}
