package whitewallet.core.wallet

import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.ActivityKind
import fr.acinq.bitcoin.ByteVector32
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.core.wallet.Regtest.HOST3
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * The wallet against the real reference mint on real (regtest) Lightning. Needs the stack from dev/regtest
 * (`./dev/regtest/regtest.sh up`) and LNURLCASH_REGTEST=1; skipped otherwise.
 */
class RegtestE2ETest {
    private val random = SecureRandom()

    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** The name of [phone]'s address, for paying it on another mint or typing it again. */
    private fun username(phone: Regtest.Phone) = phone.address.substringBefore('@')

    @Test
    fun `a note from regtest note is claimable as the app would claim it, in both encodings`() = runBlocking<Unit> {
        for (bech32 in listOf(false, true)) {
            val wallet = Regtest.phone(strategy = SingleNoteStrategy.ID).wallet

            val link = if (bech32) Regtest.run("note", "1500", "--bech32") else Regtest.note(1500)
            if (bech32) assertTrue(link.startsWith("LNURL1"), link) else assertTrue(link.startsWith("lnurlw://$HOST/w?k1="), link)
            val preview = assertIs<ClaimPreview.Ready>(wallet.previewClaim(link), "claim preview ($link)")
            assertEquals(1_499_000, preview.amountMsat, "1500 sats minus the mint's 1 sat base fee")
            assertTrue(preview.certified)
            assertIs<ClaimResult.Claimed>(wallet.claim(preview))
            assertEquals(1_499_000, wallet.balanceMsat.first())
            // History shows the claim once, whatever note maintenance followed.
            val money = wallet.activity.first().filterNot { it.kind.isMaintenance }
            assertEquals(listOf(ActivityKind.Claimed to 1_499_000L), money.map { it.kind to it.amountMsat })
            // Claiming moved the funds onto our own keys, so the handed-over link is spent now.
            assertIs<ClaimPreview.AlreadySpent>(wallet.previewClaim(link), "the same link again")
        }
    }

    @Test
    fun `pay a Lightning invoice and another user's address on the same mint`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
        alice.fund(5000)
        val start = alice.balance()

        // A real invoice from the user's Lightning node: the note is split to the exact amount and melted.
        val invoice = Regtest.invoice(1200)
        val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(invoice))
        assertEquals(1_200_000, preview.fixedAmountMsat)
        val paid = assertIs<PayResult.Paid>(alice.wallet.pay(preview, 1_200_000))
        assertTrue(Regtest.settled(invoice), "the user node was paid")
        assertEquals(start - 1_200_000 - paid.feeMsat, alice.balance())

        // Bob on the same mint: the notes are handed over at Bob's key, without Lightning.
        val bob = Regtest.phone(strategy = SingleNoteStrategy.ID)
        val toBob = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(bob.address))
        assertTrue(toBob.internal)
        assertIs<PayResult.Paid>(alice.wallet.pay(toBob, 700_000))
        assertEquals(700_000, bob.wallet.refresh().receivedMsat)
        assertEquals(listOf(ActivityKind.Sent, ActivityKind.Sent, ActivityKind.Received), alice.wallet.activity.first().filterNot { it.kind.isMaintenance }.map { it.kind })
    }

    @Test
    fun `a note sent as a link is redeemed by another wallet, and another one taken back`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = ManualStrategy.ID)
        phone.fund(3000)
        val wallet = phone.wallet

        val sent = assertIs<CreateNoteResult.Created>(wallet.createNote(1_000_000)).note
        val other = Regtest.phone(strategy = ManualStrategy.ID).wallet
        assertIs<ClaimResult.Claimed>(other.claim(assertIs<ClaimPreview.Ready>(other.previewClaim(sent.link!!))))
        assertEquals(SharedNoteState.Redeemed, wallet.checkSharedNote(sent.id)?.state)

        val kept = assertIs<CreateNoteResult.Created>(wallet.createNote(500_000)).note
        assertEquals(ReclaimResult.Reclaimed(500_000), wallet.reclaimNote(kept.id))
        assertEquals(listOf(ActivityStatus.Cancelled, ActivityStatus.Done), wallet.activity.first().filter { it.kind == ActivityKind.SentNote }.map { it.status })
    }

    @Test
    fun `notes move to the second mint over Lightning and back`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = ManualStrategy.ID)
        phone.fund(3000, 2000)
        val wallet = phone.wallet

        // Two notes on the first mint: merged, melted into the second mint's invoice, minted there to our key.
        val ids = phone.notes(HOST).map { it.id }
        assertEquals(2, ids.size)
        val quote = assertIs<MintMoveQuote.Ready>(wallet.quoteMintMove(ids, HOST2))
        val moved = assertIs<MintMoveResult.Moved>(wallet.moveToMint(ids, HOST2))
        assertEquals(quote.expectedMsat, moved.arrivedMsat, "the second mint charges its advertised 2 sat fee")
        assertEquals(emptyList(), phone.notes(HOST))
        assertEquals(listOf(moved.arrivedMsat), phone.notes(HOST2).map { it.amountMsat })

        // And back again.
        val back = assertIs<MintMoveResult.Moved>(wallet.moveToMint(phone.notes(HOST2).map { it.id }, HOST))
        assertEquals(listOf(back.arrivedMsat), phone.notes(HOST).map { it.amountMsat })
        assertEquals(2, wallet.activity.first().count { it.kind == ActivityKind.MovedMint && it.status == ActivityStatus.Done })
    }

    @Test
    fun `a note claimed on the second mint is collected at the home mint, unless the fee is over the cap`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = ManualStrategy.ID, collectDefaults = CollectDefaults(enabled = true, feeCapPercent = 2))
        val wallet = phone.wallet

        // Claiming runs the note policies: the note is moved home over Lightning right away.
        val link = Regtest.note(3000, second = true)
        val claimed = assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(link))))
        assertEquals(emptyList(), phone.notes(HOST2), "nothing left on the second mint")
        val home = phone.notes(HOST).single()
        val collected = wallet.lastPolicyRun.first()?.collected?.single()
        assertEquals(HOST2, collected?.host)
        assertEquals(home.amountMsat, collected?.arrivedMsat)
        assertTrue(collected!!.feeMsat * 100 <= 2 * (home.amountMsat + collected.feeMsat), "within the 2 % cap")
        assertTrue(home.amountMsat < claimed.amountMsat)
        assertTrue(wallet.activity.first().single { it.kind == ActivityKind.MovedMint }.automatic)

        // A small note: the home mint's 1 sat fee alone is over 1 %, so it stays where it is.
        wallet.setCollectFeeCap(1)
        val small = Regtest.note(60, second = true)
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(small))))
        assertEquals(1, phone.notes(HOST2).size, "left on the second mint")
        assertIs<NotCollected.Reason.FeeTooHigh>(wallet.lastPolicyRun.first()?.notCollected?.single()?.reason)
    }

    @Test
    fun `restore on another phone finds the notes, takes back the address, and keeps receiving`() = runBlocking<Unit> {
        // Phone 1: an address, one payment to it, and one claimed note (purposes 2 and 0 in use).
        val phone1 = Regtest.phone(strategy = ManualStrategy.ID)
        phone1.fund(2000)
        val link = Regtest.note(1500)
        assertIs<ClaimResult.Claimed>(phone1.wallet.claim(assertIs<ClaimPreview.Ready>(phone1.wallet.previewClaim(link))))
        val words = phone1.wallet.recoveryPhrase()
        assertEquals(3_498_000, phone1.balance())

        // Phone 2: the phrase and the mint alone bring the notes back.
        val store = InMemoryWalletStore()
        val phone2 = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val restored = assertIs<RestoreResult.Restored>(phone2.restore(words.joinToString(" "), HOST, "Main"))
        assertEquals(2, restored.notes)
        assertEquals(3_498_000, phone2.balanceMsat.first())
        assertTrue(phone2.needsAddress())

        // Typing the old address re-attaches it without registering anything (the mint already maps it to this seed).
        val ready = assertIs<OnboardResult.Ready>(phone2.onboard(username(phone1), HOST))
        assertTrue(ready.confirmed)
        assertEquals(phone1.address, phone2.profile.first()?.address)
        assertEquals(1, store.accounts.first().size, "no second account")

        // New payments to the address reach phone 2 (the address scan continues after the recovered keys).
        Regtest.pay(phone1.address, 1000)
        assertEquals(999_000, phone2.refresh().receivedMsat)
    }

    @Test
    fun `an address on the second mint gets paid there, and a restored phone takes it back`() = runBlocking<Unit> {
        val phone1 = Regtest.phone(strategy = ManualStrategy.ID)
        val store = phone1.store
        val bob = Regtest.name("e2e")
        assertTrue(assertIs<OnboardResult.Ready>(phone1.wallet.addAddress(bob, limit = 1, host = HOST2)).confirmed)
        assertEquals(phone1.address, phone1.wallet.profile.first()?.address, "the primary address stays on the home mint")

        // A payment to the second mint's address: found by refresh, as a note on that mint.
        Regtest.pay("$bob@$HOST2", 2000)
        assertEquals(1_998_000, phone1.wallet.refresh().receivedMsat, "2000 sats minus mint2's 2 sat fee")
        assertEquals(listOf(HOST2 to 1_998_000L), store.notes(store.currentAccountId()!!).first().map { it.host to it.amountMsat })
        assertEquals(HOST2, phone1.wallet.activity.first().single { it.kind == ActivityKind.Received }.host)

        // Phone 2 restores from the home mint, then adds the same name on the second mint: it's taken back, and
        // the note there comes with it.
        val phone2 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<RestoreResult.Restored>(phone2.restore(phone1.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        assertIs<OnboardResult.Ready>(phone2.onboard(username(phone1), HOST))
        assertTrue(assertIs<OnboardResult.Ready>(phone2.addAddress(bob, limit = 1, host = HOST2)).confirmed)
        phone2.refresh()
        assertEquals(1_998_000, phone2.balanceMsat.first())
    }

    @Test
    fun `changing the home mint to the second one, then moving the money there as quoted`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = ManualStrategy.ID, collectDefaults = CollectDefaults(enabled = true, feeCapPercent = 2))
        val wallet = phone.wallet
        val alice = username(phone)
        phone.fund(3000)
        assertEquals(1, phone.notes(HOST).size)
        // A small note on a third mint: moving it costs more than 2 %, so the summary leaves it out.
        val small = Regtest.note3(30)
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(small))))
        assertEquals(1, phone.notes(HOST3).size)

        // The summary first: the old home's money moves for a quoted fee within the cap; the small note doesn't.
        val preview = assertNotNull(wallet.previewMove(HOST2))
        val fromHome = preview.lines.single { it.host == HOST }
        val quoted = assertIs<MovePreview.Quote.Ok>(fromHome.quote)
        assertTrue(!fromHome.overCap(2), "within the 2 % cap")
        val smallLine = preview.lines.single { it.host == HOST3 }
        assertTrue(smallLine.quote !is MovePreview.Quote.Ok || smallLine.overCap(2), "the small note stays")

        // The change itself moves nothing: the same name on the second mint becomes the primary address there.
        assertTrue(assertIs<OnboardResult.Ready>(wallet.changeHome(HOST2, alice)).confirmed)
        assertEquals(HOST2, wallet.homeHost.first())
        assertEquals("$alice@$HOST2", wallet.profile.first()?.address)
        assertEquals(1, phone.notes(HOST).size, "the change moved nothing")

        // Then the money the user confirmed: over Lightning, arrived as quoted.
        val moved = wallet.moveMoney(HOST2, preview.lines.filter { it.quote is MovePreview.Quote.Ok && !it.overCap(2) }).single()
        assertEquals(MintMoveResult.Moved(quoted.arrivesMsat, quoted.feeMsat), moved.result)
        assertEquals(emptyList(), phone.notes(HOST), "nothing left on the old home")
        assertEquals(listOf(quoted.arrivesMsat), phone.notes(HOST2).map { it.amountMsat })
        assertEquals(1, phone.notes(HOST3).size, "the small note stayed")

        // A payment to the old address arrives on the old mint and is collected at the new home.
        Regtest.pay("$alice@$HOST", 3000)
        wallet.refresh()
        assertEquals(emptyList(), phone.notes(HOST))
        assertEquals(2, phone.notes(HOST2).size)
    }

    @Test
    fun `onboard, receive, claim, add an account, move funds, receive again`() = runBlocking<Unit> {
        // Built by hand: onboarding itself is under test here.
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

        // 1. The reference mint accepts the registration proof and publishes our cx1 for the name.
        val alice = Regtest.name("e2e")
        val first = assertIs<OnboardResult.Ready>(wallet.onboard(alice, HOST), "onboarding")
        assertTrue(first.confirmed, "address confirmed through text/cpub")
        val accountA = assertNotNull(store.currentAccountId())

        // 2. A payment to the address is auto-minted onto our branch; refresh finds it, certified.
        Regtest.pay("$alice@$HOST", 2000)
        val received = wallet.refresh()
        assertEquals(1, received.newNotes)
        assertEquals(1_999_000, received.receivedMsat, "2000 sats minus the mint's 1 sat base fee")
        assertEquals(listOf(ActivityKind.Received to 1_999_000L), wallet.activity.first().map { it.kind to it.amountMsat })
        assertTrue(store.allNotes().single().certificate != null, "the mint's certificate verified")

        // 3. A bearer note minted for a hash we chose can be claimed (rotated into our own keys).
        val preimage = ByteVector32(ByteArray(32).also(random::nextBytes))
        val bearer = Spend.Bearer(preimage)
        val invoice = java.net.URI("http://$HOST/p/cb?amount=3000000&comment=${bearer.h.toHex()}").toURL().readText()
            .substringAfter("\"pr\":\"").substringBefore('"')
        Regtest.run("pay", invoice)
        val preview = assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://$HOST/w?k1=${preimage.toHex()}"), "claim preview")
        assertEquals(2_999_000, preview.amountMsat)
        assertTrue(preview.certified)
        assertIs<ClaimResult.Claimed>(wallet.claim(preview))
        assertEquals(4_998_000, wallet.balanceMsat.first())

        // 4. A second account, then move part of the first account's funds to it (a split on the real mint).
        val bob = Regtest.name("e2e")
        assertIs<OnboardResult.Ready>(wallet.onboard(bob, HOST, newAccountName = "Savings"))
        val accountB = assertNotNull(store.currentAccountId())
        wallet.switchAccount(accountA)
        assertEquals(MoveResult.Moved(500_000), wallet.moveFunds(accountB, 500_000))
        val balances = wallet.accounts.first().associate { it.account.id to it.balanceMsat }
        assertEquals(500_000, balances[accountB])
        // The split's base fee comes out of the change; the merge first (two notes) refunds nothing extra.
        assertEquals(4_998_000 - 500_000 - 1_000, balances[accountA], "mint fees: ${balances[accountA]}")

        // 5. The next payment to the address lands on the next index; refresh scans past the used one.
        Regtest.pay("$alice@$HOST", 1000)
        assertEquals(1, wallet.refresh().newNotes)
        assertEquals(1, store.addressScanFrom(accountA, HOST) - 1, "two address notes: indexes 0 and 1")

        // 6. Rotating that address note onto a wallet key is free on the real mint too.
        val before = wallet.balanceMsat.first()
        assertIs<RotateResult.Rotated>(wallet.rotateKey(HOST, 2, 1))
        assertEquals(before, wallet.balanceMsat.first())

        // 7. Even with the wallet counter forced back to 0, rotate all works: used keys are skipped or rediscovered.
        store.setIndex(accountA, HOST, 0, 0)
        val all = wallet.rotateAllKeys(HOST, 0)
        assertEquals(0, all.failed, "rotate all after a reset: $all")
        assertTrue(all.rotated > 0)
        assertEquals(before, wallet.balanceMsat.first())
    }

    @Test
    fun `a sent note claimed back through its own link shows as taken back`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = SingleNoteStrategy.ID)
        phone.fund(5000)
        val wallet = phone.wallet
        val start = phone.balance()

        val note = assertIs<CreateNoteResult.Created>(wallet.createNote(1_000_000)).note
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(note.link!!))))
        wallet.refresh()
        assertEquals(start, phone.balance())
        // One row, taken back, as with Take back: not a sent note waiting forever next to a claim of the same money.
        val money = wallet.activity.first().filterNot { it.kind.isMaintenance || it.kind == ActivityKind.Received }
        assertEquals(listOf(ActivityKind.SentNote to ActivityStatus.Cancelled), money.map { it.kind to it.status })
        assertEquals(SharedNoteState.Reclaimed, wallet.sharedNote(note.id)?.state)
    }

    @Test
    fun `a payment to a user on the same mint that drops while someone else pays them is not taken as paid`() = runBlocking<Unit> {
        val bob = Regtest.phone(strategy = SingleNoteStrategy.ID)
        val carol = Regtest.phone(strategy = SingleNoteStrategy.ID)
        carol.fund(3000)

        // Alice's connection drops on every rotate, merge or split while armed, before it reaches the mint. The first
        // time, Carol pays Bob first: her note takes the key of Bob's that Alice's payment names.
        var armed = false
        var carolPaid = false
        val dropping = Regtest.client { chain ->
            val url = chain.request().url
            if (armed && url.queryParameter("k1") != null && url.queryParameter("p1") != null) {
                if (!carolPaid) {
                    carolPaid = true
                    runBlocking { assertIs<PayResult.Paid>(carol.wallet.pay(assertIs<PayPreview.Ready>(carol.wallet.previewPayment(bob.address)), 700_000)) }
                }
                throw java.io.IOException("connection dropped")
            }
            chain.proceed(chain.request())
        }
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID, client = dropping)
        alice.fund(5000)
        val start = alice.balance()

        val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(bob.address))
        assertTrue(preview.internal)
        armed = true
        val dropped = alice.wallet.pay(preview, 1_000_000)
        armed = false
        assertTrue(carolPaid)
        assertIs<PayResult.Failed>(dropped, "Bob's key holds Carol's note, not Alice's")
        alice.wallet.refresh()
        assertEquals(start, alice.balance(), "Alice's notes were never spent")
        assertEquals(emptyList(), alice.wallet.activity.first().filter { it.kind == ActivityKind.Sent })

        // Paying again works, at Bob's next key.
        assertIs<PayResult.Paid>(alice.wallet.pay(assertIs<PayPreview.Ready>(alice.wallet.previewPayment(bob.address)), 1_000_000))
        bob.wallet.refresh()
        assertEquals(listOf(700_000L, 1_000_000L), bob.wallet.activity.first().filter { it.kind == ActivityKind.Received }.map { it.amountMsat }.sorted())
    }

    @Test
    fun `a combine the mint pays fees back for shows the refund`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
        alice.fund(5000)
        val bob = Regtest.phone(strategy = SingleNoteStrategy.ID)
        for (amount in listOf(1_000_000L, 700_000L)) {
            assertIs<PayResult.Paid>(alice.wallet.pay(assertIs<PayPreview.Ready>(alice.wallet.previewPayment(bob.address)), amount))
        }

        bob.wallet.refresh()
        // Keep-one-note combined the two notes; the mint paid back a base fee, which its certificate proves.
        val combined = bob.wallet.activity.first().single { it.kind == ActivityKind.Combined }
        assertEquals(1_701_000, combined.amountMsat)
        assertEquals(-1_000, combined.feeMsat)
        assertEquals(1_701_000, bob.balance())
    }
}
