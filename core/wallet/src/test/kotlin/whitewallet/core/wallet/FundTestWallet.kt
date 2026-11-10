package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * `just regtest-fund-test-wallet [notes] [account]`: pays [notes] notes of 1,000 sats each into account [account] of the BIP-39 test phrase
 * (the one developer mode offers on the restore screen), on the first mint of the regtest stack that is up. The wallet restores the phrase
 * first, so its keys follow the ones already used: running it again adds notes.
 */
fun main(args: Array<String>) = runBlocking {
    val notes = args.getOrNull(0)?.toIntOrNull() ?: 3
    val index = args.getOrNull(1)?.toLongOrNull() ?: 0
    require(notes > 0 && index >= 0) { "usage: fundTestWallet [notes > 0] [account >= 0]" }

    val store = InMemoryWalletStore()
    val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
    val restored = wallet.restore(TEST_PHRASE, HOST, "Test")
    check(restored is RestoreResult.Restored) { "restoring the test phrase on $HOST failed: $restored (is the regtest stack up?)" }
    // A restore stops at the first unused account; the ones up to [index] are made here.
    val found = store.accounts.first().map { it.account.index }
    if (index > (found.maxOrNull() ?: -1) + 1)
        println("Note: accounts before $index hold nothing, so a restore in the app stops before account $index. Fund those first.")
    while (store.accounts.first().none { it.account.index == index }) {
        check(wallet.createAccount("Test") is CreateAccountResult.Created)
    }
    wallet.switchAccount(store.accounts.first().single { it.account.index == index }.account.id)

    repeat(notes) {
        val invoice = wallet.requestInvoice(1_000_000)
        check(invoice is InvoiceRequest.Ready) { "the mint made no invoice: $invoice" }
        Regtest.pay(invoice.invoice, 0)
        check(wallet.invoiceStatus(invoice.id) is InvoiceStatus.Paid) { "the invoice wasn't paid" }
    }
    println("Paid $notes note(s) of 1000 sats into account $index of the test phrase on $HOST.")
    println("Balance of that account now: ${wallet.balanceMsat.first() / 1000} sats.")
}

private const val TEST_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
