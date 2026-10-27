package whitewallet.lnurlcash.client

import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.spendDomain
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.testkit.MockMint

/** Runs the client against the conformance mock mint. Skipped until dev/conformance.sh has prepared it. */
class MockMintIntegrationTest {
    private val seed = Hex.decode("000102030405060708090a0b0c0d0e0f")

    @BeforeEach
    fun requireMockMint() = assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")

    @Test
    fun `credit, look up, and rotate a key-path note`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val client = LnurlcashClient()
            val pay = client.servicePayRequest(mint.host)
            val branch = CashBranch.fromSeed(seed, mint.host)
            val q0 = branch.notePubkey(NotePurpose.Wallet, 0)
            mint.credit(Cp1.encode(q0), 21_000)

            val looked = client.lookup(pay.withdrawUrl, q0)
            assertEquals(21_000, looked.maxWithdrawableMsat)
            assertTrue(Certificates.verify(assertNotNull(looked.certificate), q0, looked.mintPubkey))

            val spend = branch.spend(NotePurpose.Wallet, 0, spendDomain(mint.host))
            val info = client.noteInfo(pay.withdrawUrl, spend.k1)
            assertEquals(21_000, info.maxWithdrawableMsat)

            val q1 = branch.notePubkey(NotePurpose.Wallet, 1)
            val outcome = client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, q1))
            val confirmed = assertIs<MutationOutcome.Confirmed>(outcome)
            val cert = assertNotNull(Cs1.decode(assertNotNull(confirmed.certificate)))
            assertEquals(21_000, cert.amountMsat)
            assertTrue(Certificates.verify(cert, q1, info.mintPubkey))

            val spent = assertFailsWith<NoteLookupError> { client.lookup(pay.withdrawUrl, q0) }
            assertEquals(RejectionKind.Spent, spent.kind, spent.reason)
            assertEquals(21_000, client.lookup(pay.withdrawUrl, q1).maxWithdrawableMsat)

            // Naming an output that is already outstanding is refused, and nothing is burned.
            val spend1 = branch.spend(NotePurpose.Wallet, 1, spendDomain(mint.host))
            val reused = client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend1.k1, q1))
            assertEquals(RejectionKind.AlreadyInUse, assertIs<MutationOutcome.Rejected>(reused).kind)
        }
    }

    @Test
    fun `split keeps value and certifies both outputs`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val client = LnurlcashClient()
            val pay = client.servicePayRequest(mint.host)
            val branch = CashBranch.fromSeed(seed, mint.host)
            mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 0)), 21_000)
            val spend = branch.spend(NotePurpose.Wallet, 0, spendDomain(mint.host))
            val info = client.noteInfo(pay.withdrawUrl, spend.k1)
            val p1 = branch.notePubkey(NotePurpose.Wallet, 1)
            val p2 = branch.notePubkey(NotePurpose.Change, 0)
            val outcome = assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Split, Requests.split(info.callback, listOf(spend.k1), 5_000, p1, p2)))
            assertEquals(5_000, Cs1.decode(outcome.certificate!!)!!.amountMsat)
            assertEquals(16_000, Cs1.decode(outcome.changeCertificate!!)!!.amountMsat)
        }
    }

    @Test
    fun `a dropped connection is ambiguous, and a byte-identical replay recovers the original success`() = runBlocking<Unit> {
        MockMint.start("--dropAfterMutation").use { mint ->
            val branch = CashBranch.fromSeed(seed, mint.host)
            val domain = spendDomain(mint.host)
            val withdrawUrl = LnurlcashClient().servicePayRequest(mint.host).withdrawUrl
            mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 0)), 21_000)
            val spend = branch.spend(NotePurpose.Wallet, 0, domain)
            val info = LnurlcashClient().noteInfo(withdrawUrl, spend.k1)
            val url = Requests.rotate(info.callback, spend.k1, branch.notePubkey(NotePurpose.Wallet, 1))

            val noReplay = LnurlcashClient(mutationReplays = 0).mutate(MutationType.Rotate, url)
            assertIs<MutationOutcome.Ambiguous>(noReplay)
            // The rotate landed even though the answer was lost: the input is burned.
            val gone = assertFailsWith<NoteLookupError> { LnurlcashClient().lookup(withdrawUrl, spend.q) }
            assertEquals(RejectionKind.Spent, gone.kind)
            assertEquals(21_000, LnurlcashClient().lookup(withdrawUrl, branch.notePubkey(NotePurpose.Wallet, 1)).maxWithdrawableMsat)

            // With the default single replay, the same lost answer resolves into the original success.
            mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 2)), 7_000)
            val spend2 = branch.spend(NotePurpose.Wallet, 2, domain)
            val url2 = Requests.rotate(info.callback, spend2.k1, branch.notePubkey(NotePurpose.Wallet, 3))
            val replayed = assertIs<MutationOutcome.Confirmed>(LnurlcashClient().mutate(MutationType.Rotate, url2))
            assertEquals(7_000, Cs1.decode(replayed.certificate!!)!!.amountMsat)
        }
    }

    @Test
    fun `a bearer note can be claimed by rotating it into a key-path note`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val client = LnurlcashClient()
            val pay = client.servicePayRequest(mint.host)
            val preimage = fr.acinq.bitcoin.ByteVector32(Hex.decode("11".repeat(32)))
            val bearer = Spend.Bearer(preimage)
            mint.credit(bearer.h.toHex(), 10_000)
            val link = assertNotNull(NoteLink.parse("lnurlw://${mint.host}/w?k1=${bearer.k1}"))
            val info = client.noteInfo(link.withdrawUrl, link.spend.k1)
            val target = CashBranch.fromSeed(seed, mint.host).notePubkey(NotePurpose.Wallet, 0)
            assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Rotate, Requests.rotate(info.callback, link.spend.k1, target)))
            assertEquals(10_000, client.lookup(pay.withdrawUrl, target).maxWithdrawableMsat)
        }
    }
}
