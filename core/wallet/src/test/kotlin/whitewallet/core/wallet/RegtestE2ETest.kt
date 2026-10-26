package whitewallet.core.wallet

import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.ActivityKind
import fr.acinq.bitcoin.ByteVector32
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * The wallet against the real reference mint on real (regtest) Lightning. Needs the stack from dev/regtest
 * (`./dev/regtest/regtest.sh up`) and LNURLCASH_REGTEST=1; skipped otherwise.
 */
class RegtestE2ETest {
    private val host = "localhost:8111"
    private val script = File("../../dev/regtest/regtest.sh").canonicalFile
    private val random = SecureRandom()

    @BeforeEach
    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    private fun regtest(vararg args: String): String {
        val process = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "regtest.sh ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    private fun name() = "e2e" + (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    @Test
    fun `a note from regtest note is claimable as the app would claim it, in both encodings`() = runBlocking<Unit> {
        for (bech32 in listOf(false, true)) {
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())
            assertIs<OnboardResult.Ready>(wallet.onboard(name(), host))

            // The last line of output is the link; the log lines go to stderr, merged here.
            val args = if (bech32) arrayOf("note", "1500", "--bech32") else arrayOf("note", "1500")
            val link = regtest(*args).trim().lines().last()
            if (bech32) assertTrue(link.startsWith("LNURL1"), link) else assertTrue(link.startsWith("lnurlw://$host/w?k1="), link)
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
        val alice = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        val aliceName = name()
        assertIs<OnboardResult.Ready>(alice.onboard(aliceName, host))
        regtest("pay", "$aliceName@$host", "5000")
        alice.refresh()
        val start = alice.balanceMsat.first()

        // A real invoice from the user's Lightning node: the note is split to the exact amount and melted.
        val invoice = regtest("invoice", "1200", "white wallet e2e").trim().lines().last()
        val preview = assertIs<PayPreview.Ready>(alice.previewPayment(invoice))
        assertEquals(1_200_000, preview.fixedAmountMsat)
        val paid = assertIs<PayResult.Paid>(alice.pay(preview, 1_200_000))
        val hash = assertNotNull(whitewallet.lnurl.Bolt11.decode(invoice)).paymentHashHex
        val state = regtest("lncli-user", "lookupinvoice", hash)
        assertTrue("\"SETTLED\"" in state, "the user node was paid:\n$state")
        assertEquals(start - 1_200_000 - paid.feeMsat, alice.balanceMsat.first())

        // Bob on the same mint: the notes are handed over at Bob's key, without Lightning.
        val bob = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        val bobName = name()
        assertIs<OnboardResult.Ready>(bob.onboard(bobName, host))
        val toBob = assertIs<PayPreview.Ready>(alice.previewPayment("$bobName@$host"))
        assertTrue(toBob.internal)
        assertIs<PayResult.Paid>(alice.pay(toBob, 700_000))
        assertEquals(700_000, bob.refresh().receivedMsat)
        assertEquals(listOf(ActivityKind.Sent, ActivityKind.Sent, ActivityKind.Received), alice.activity.first().filterNot { it.kind.isMaintenance }.map { it.kind })
    }

    @Test
    fun `a note sent as a link is redeemed by another wallet, and another one taken back`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<OnboardResult.Ready>(wallet.onboard(name(), host))
        regtest("pay", "${wallet.profile.first()!!.address}", "3000")
        wallet.refresh()

        val sent = assertIs<CreateNoteResult.Created>(wallet.createNote(1_000_000)).note
        val other = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<OnboardResult.Ready>(other.onboard(name(), host))
        assertIs<ClaimResult.Claimed>(other.claim(assertIs<ClaimPreview.Ready>(other.previewClaim(sent.link!!))))
        assertEquals(SharedNoteState.Redeemed, wallet.checkSharedNote(sent.id)?.state)

        val kept = assertIs<CreateNoteResult.Created>(wallet.createNote(500_000)).note
        assertEquals(ReclaimResult.Reclaimed(500_000), wallet.reclaimNote(kept.id))
        assertEquals(listOf(ActivityStatus.Cancelled, ActivityStatus.Done), wallet.activity.first().filter { it.kind == ActivityKind.SentNote }.map { it.status })
    }

    @Test
    fun `notes move to the second mint over Lightning and back`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val name = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(name, host))
        regtest("pay", "$name@$host", "3000")
        regtest("pay", "$name@$host", "2000")
        wallet.refresh()
        val second = "localhost:8112"
        suspend fun notes(h: String) = store.notes(store.currentAccountId()!!).first().filter { it.host == h && it.state == NoteState.Outstanding }

        // Two notes on the first mint: merged, melted into the second mint's invoice, minted there to our key.
        val ids = notes(host).map { it.id }
        assertEquals(2, ids.size)
        val quote = assertIs<ServiceMoveQuote.Ready>(wallet.quoteServiceMove(ids, second))
        val moved = assertIs<ServiceMoveResult.Moved>(wallet.moveToService(ids, second))
        assertEquals(quote.expectedMsat, moved.arrivedMsat, "the second mint charges its advertised 2 sat fee")
        assertEquals(emptyList(), notes(host))
        assertEquals(listOf(moved.arrivedMsat), notes(second).map { it.amountMsat })

        // And back again.
        val back = assertIs<ServiceMoveResult.Moved>(wallet.moveToService(notes(second).map { it.id }, host))
        assertEquals(listOf(back.arrivedMsat), notes(host).map { it.amountMsat })
        assertEquals(2, wallet.activity.first().count { it.kind == ActivityKind.MovedService && it.status == ActivityStatus.Done })
    }

    @Test
    fun `a note claimed on the second mint is collected at the home mint, unless the fee is over the cap`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID),
            collectDefaults = CollectDefaults(enabled = true, feeCapPercent = 2))
        assertIs<OnboardResult.Ready>(wallet.onboard(name(), host))
        val second = "localhost:8112"
        suspend fun on(h: String) = store.notes(store.currentAccountId()!!).first().filter { it.host == h && it.state == NoteState.Outstanding }

        // Claiming runs the note policies: the note is moved home over Lightning right away.
        val link = regtest("note", "3000", "--mint2").trim().lines().last()
        val claimed = assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(link))))
        assertEquals(emptyList(), on(second), "nothing left on the second mint")
        val home = on(host).single()
        val collected = wallet.lastPolicyRun.first()?.collected?.single()
        assertEquals(second, collected?.host)
        assertEquals(home.amountMsat, collected?.arrivedMsat)
        assertTrue(collected!!.feeMsat * 100 <= 2 * (home.amountMsat + collected.feeMsat), "within the 2 % cap")
        assertTrue(home.amountMsat < claimed.amountMsat)
        assertTrue(wallet.activity.first().single { it.kind == ActivityKind.MovedService }.automatic)

        // A small note: the home mint's 1 sat fee alone is over 1 %, so it stays where it is.
        wallet.setCollectFeeCap(1)
        val small = regtest("note", "60", "--mint2").trim().lines().last()
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(small))))
        assertEquals(1, on(second).size, "left on the second mint")
        assertIs<NotCollected.Reason.FeeTooHigh>(wallet.lastPolicyRun.first()?.notCollected?.single()?.reason)
    }

    @Test
    fun `restore on another phone finds the notes, takes back the address, and keeps receiving`() = runBlocking<Unit> {
        // Phone 1: an address, one payment to it, and one claimed note (purposes 2 and 0 in use).
        val phone1 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val alice = name()
        assertIs<OnboardResult.Ready>(phone1.onboard(alice, host))
        regtest("pay", "$alice@$host", "2000")
        phone1.refresh()
        val link = regtest("note", "1500").trim().lines().last()
        assertIs<ClaimResult.Claimed>(phone1.claim(assertIs<ClaimPreview.Ready>(phone1.previewClaim(link))))
        val words = phone1.recoveryPhrase()
        assertEquals(3_498_000, phone1.balanceMsat.first())

        // Phone 2: the phrase and the mint alone bring the notes back.
        val store = InMemoryWalletStore()
        val phone2 = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val restored = assertIs<RestoreResult.Restored>(phone2.restore(words.joinToString(" "), host, "Main"))
        assertEquals(2, restored.notes)
        assertEquals(3_498_000, phone2.balanceMsat.first())
        assertTrue(phone2.needsAddress())

        // Typing the old address re-attaches it without registering anything (the mint already maps it to this seed).
        val ready = assertIs<OnboardResult.Ready>(phone2.onboard(alice, host))
        assertTrue(ready.confirmed)
        assertEquals("$alice@$host", phone2.profile.first()?.address)
        assertEquals(1, store.accounts.first().size, "no second account")

        // New payments to the address reach phone 2 (the address scan continues after the recovered keys).
        regtest("pay", "$alice@$host", "1000")
        assertEquals(999_000, phone2.refresh().receivedMsat)
    }

    @Test
    fun `an address on the second mint gets paid there, and a restored phone takes it back`() = runBlocking<Unit> {
        val second = "localhost:8112"
        val store = InMemoryWalletStore()
        val phone1 = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val alice = name()
        assertIs<OnboardResult.Ready>(phone1.onboard(alice, host))
        val bob = name()
        assertTrue(assertIs<OnboardResult.Ready>(phone1.addAddress(bob, limit = 1, host = second)).confirmed)
        assertEquals("$alice@$host", phone1.profile.first()?.address, "the primary address stays on the home mint")

        // A payment to the second mint's address: found by refresh, as a note on that mint.
        regtest("pay", "$bob@$second", "2000")
        assertEquals(1_998_000, phone1.refresh().receivedMsat, "2000 sats minus mint2's 2 sat fee")
        assertEquals(listOf(second to 1_998_000L), store.notes(store.currentAccountId()!!).first().map { it.host to it.amountMsat })
        assertEquals(second, phone1.activity.first().single { it.kind == ActivityKind.Received }.host)

        // Phone 2 restores from the home mint, then adds the same name on the second mint: it's taken back, and
        // the note there comes with it.
        val phone2 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<RestoreResult.Restored>(phone2.restore(phone1.recoveryPhrase().joinToString(" "), host, "Main"))
        assertIs<OnboardResult.Ready>(phone2.onboard(alice, host))
        assertTrue(assertIs<OnboardResult.Ready>(phone2.addAddress(bob, limit = 1, host = second)).confirmed)
        phone2.refresh()
        assertEquals(1_998_000, phone2.balanceMsat.first())
    }

    @Test
    fun `onboard, receive, claim, add an account, move funds, receive again`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

        // 1. The reference mint accepts the registration proof and publishes our cx1 for the name.
        val alice = name()
        val first = assertIs<OnboardResult.Ready>(wallet.onboard(alice, host), "onboarding")
        assertTrue(first.confirmed, "address confirmed through text/cpub")
        val accountA = assertNotNull(store.currentAccountId())

        // 2. A payment to the address is auto-minted onto our branch; refresh finds it, certified.
        regtest("pay", "$alice@$host", "2000")
        val received = wallet.refresh()
        assertEquals(1, received.newNotes)
        assertEquals(1_999_000, received.receivedMsat, "2000 sats minus the mint's 1 sat base fee")
        assertEquals(listOf(ActivityKind.Received to 1_999_000L), wallet.activity.first().map { it.kind to it.amountMsat })
        assertTrue(store.allNotes().single().certificate != null, "the mint's certificate verified")

        // 3. A bearer note minted for a hash we chose can be claimed (rotated into our own keys).
        val preimage = ByteVector32(ByteArray(32).also(random::nextBytes))
        val bearer = Spend.Bearer(preimage)
        val invoice = java.net.URI("http://$host/p/cb?amount=3000000&comment=${bearer.h.toHex()}").toURL().readText()
            .substringAfter("\"pr\":\"").substringBefore('"')
        regtest("pay", invoice)
        val preview = assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://$host/w?k1=${preimage.toHex()}"), "claim preview")
        assertEquals(2_999_000, preview.amountMsat)
        assertTrue(preview.certified)
        assertIs<ClaimResult.Claimed>(wallet.claim(preview))
        assertEquals(4_998_000, wallet.balanceMsat.first())

        // 4. A second account, then move part of the first account's funds to it (a split on the real mint).
        val bob = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(bob, host, newAccountName = "Savings"))
        val accountB = assertNotNull(store.currentAccountId())
        wallet.switchAccount(accountA)
        assertEquals(MoveResult.Moved(500_000), wallet.moveFunds(accountB, 500_000))
        val balances = wallet.accounts.first().associate { it.account.id to it.balanceMsat }
        assertEquals(500_000, balances[accountB])
        // The split's base fee comes out of the change; the merge first (two notes) refunds nothing extra.
        assertEquals(4_998_000 - 500_000 - 1_000, balances[accountA], "mint fees: ${balances[accountA]}")

        // 5. The next payment to the address lands on the next index; refresh scans past the used one.
        regtest("pay", "$alice@$host", "1000")
        assertEquals(1, wallet.refresh().newNotes)
        assertEquals(1, store.addressScanFrom(accountA, host) - 1, "two address notes: indexes 0 and 1")

        // 6. Rotating that address note onto a wallet key is free on the real mint too.
        val before = wallet.balanceMsat.first()
        assertIs<RotateResult.Rotated>(wallet.rotateKey(host, 2, 1))
        assertEquals(before, wallet.balanceMsat.first())

        // 7. Even with the wallet counter forced back to 0, rotate all works: used keys are skipped or rediscovered.
        store.setIndex(accountA, host, 0, 0)
        val all = wallet.rotateAllKeys(host, 0)
        assertEquals(0, all.failed, "rotate all after a reset: $all")
        assertTrue(all.rotated > 0)
        assertEquals(before, wallet.balanceMsat.first())
    }

    @Test
    fun `a sent note claimed back through its own link shows as taken back`() = runBlocking<Unit> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(SingleNoteStrategy.ID))
        val name = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(name, host))
        regtest("pay", "$name@$host", "5000")
        wallet.refresh()
        val start = wallet.balanceMsat.first()

        val note = assertIs<CreateNoteResult.Created>(wallet.createNote(1_000_000)).note
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(note.link!!))))
        wallet.refresh()
        assertEquals(start, wallet.balanceMsat.first())
        // One row, taken back, as with Take back: not a sent note waiting forever next to a claim of the same money.
        val money = wallet.activity.first().filterNot { it.kind.isMaintenance || it.kind == ActivityKind.Received }
        assertEquals(listOf(ActivityKind.SentNote to ActivityStatus.Cancelled), money.map { it.kind to it.status })
        assertEquals(SharedNoteState.Reclaimed, wallet.sharedNote(note.id)?.state)
    }
}
