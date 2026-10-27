package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.secp256k1.Hex
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient

class OnboardingTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    /** Behaves like lnurl-mint: verifies the ownership proof and publishes text/cpub for registered names. */
    private class FakeMint(
        private val server: MockWebServer,
        private val publishCpub: Boolean = true,
        private val registrationEnabled: Boolean = true,
    ) : Dispatcher() {
        val registered = ConcurrentHashMap<String, String>()
        var posts = 0
        var deletes = 0
        /** When false, unregister requests are refused (the name stays registered). */
        var unregisterEnabled = true

        override fun dispatch(request: RecordedRequest): MockResponse {
            val base = server.url("/").toString().trimEnd('/')
            val path = request.url.encodedPath
            val pay = { extra: String -> MockResponse(body = """{"tag":"payRequest","callback":"$base/p/cb","minSendable":1000,"maxSendable":100000000,"metadata":"[[\"text/plain\",\"Mint fees: 1000,2000\"]$extra]","withdrawLink":"$base/w","commentAllowed":64}""") }
            return when {
                path == "/.well-known/lnurlw/_" -> MockResponse(body = """{"tag":"withdrawRequest","callback":"$base/w","minWithdrawable":0,"maxWithdrawable":1000,"mintPubkey":"034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"}""")
                path == "/.well-known/lnurlp/_" || path == "/.well-known/lnurlp/mint" -> pay("")
                request.method == "GET" && path.startsWith("/.well-known/lnurlp/") -> {
                    val cx1 = registered[path.substringAfterLast('/')] ?: return error("Unknown user.")
                    pay(if (publishCpub) """,[\"text/cpub\",\"$cx1:0\"]""" else "")
                }
                request.method == "POST" && path.startsWith("/p/") -> {
                    posts++
                    if (!registrationEnabled) return error("Not found")
                    val name = path.removePrefix("/p/")
                    val cx1 = request.url.queryParameter("cx1")!!
                    val proofBranch = registered[name] ?: cx1
                    if (!validProof(proofBranch, name, request.url.queryParameter("sig")!!)) return error("Invalid ownership signature.")
                    registered[name] = cx1
                    MockResponse(body = """{"status":"OK"}""")
                }
                request.method == "DELETE" && path.startsWith("/p/") -> {
                    deletes++
                    val name = path.removePrefix("/p/")
                    val cx1 = registered[name] ?: return MockResponse(code = 404, body = """{"status":"ERROR","reason":"Unknown user."}""")
                    if (!unregisterEnabled) return error("Not found")
                    if (!validProof(cx1, name, request.url.queryParameter("sig")!!, AddressProof.Action.Unregister)) return error("Invalid ownership signature.")
                    registered.remove(name)
                    MockResponse(body = """{"status":"OK"}""")
                }
                else -> MockResponse(code = 404)
            }
        }

        private fun validProof(cx1: String, name: String, sig: String, action: AddressProof.Action = AddressProof.Action.Register): Boolean {
            val pk0 = CashBranch.fromCx1(cx1)!!.notePubkey(NotePurpose.Wallet, 0)
            val digest = Crypto.sha256(AddressProof.message(action, "localhost", name).encodeToByteArray()).byteVector32()
            return Crypto.verifySignatureSchnorr(digest, ByteVector64(Hex.decode(sig)), XonlyPublicKey(pk0))
        }

        private fun error(reason: String) = MockResponse(body = """{"status":"ERROR","reason":"$reason"}""")
    }

    private fun withMint(publishCpub: Boolean = true, registrationEnabled: Boolean = true, test: suspend (FakeMint, String) -> Unit) = runBlocking<Unit> {
        MockWebServer().use { server ->
            val mint = FakeMint(server, publishCpub, registrationEnabled)
            server.dispatcher = mint
            server.start()
            test(mint, "localhost:${server.port}")
        }
    }

    private fun wallet(store: InMemoryWalletStore = InMemoryWalletStore(), seeds: InMemorySeedStore = InMemorySeedStore()) =
        Wallet(store, seeds, LnurlcashClient())

    /** A wallet whose single account "main" already has [words] and is active. */
    private suspend fun existing(words: List<String>, store: InMemoryWalletStore = InMemoryWalletStore()): Wallet {
        store.addAccount("main", "Main", null)
        return wallet(store, InMemorySeedStore(mapOf("main" to words)))
    }

    @Test
    fun `a free name is claimed and confirmed by its published cx1`() = withMint { mint, host ->
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val wallet = wallet(store, seeds)
        val result = assertIs<OnboardResult.Ready>(wallet.onboard(" Alice ", host))
        assertTrue(result.confirmed)
        assertEquals("alice@$host", result.profile.address)
        assertEquals(AddressStatus.Ours, wallet.addressStatus.first())
        val accountId = assertNotNull(store.currentAccountId())
        assertEquals("Main", store.account(accountId)?.name)
        assertEquals(12, assertNotNull(seeds.load(accountId)).size)
        assertEquals(1000L, store.mintMap[host]?.feeBaseMsat)
        assertEquals(1, mint.posts)
    }

    @Test
    fun `a name someone else holds is reported as taken with suggestions, without registering anything`() = withMint { mint, host ->
        mint.registered["alice"] = CashBranch.fromSeed(ByteArray(32) { 7 }, host).cx1()
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val result = assertIs<OnboardResult.UsernameTaken>(wallet(store, seeds).onboard("alice", host))
        assertTrue(seeds.accountIds().isEmpty(), "the unused recovery phrase is discarded")
        assertTrue(result.suggestions.isNotEmpty() && "alice" !in result.suggestions)
        assertEquals(0, mint.posts)
        assertNull(store.currentAccountId(), "no account is created")
    }

    @Test
    fun `a name this wallet already holds is recognised without registering again`() = withMint { mint, host ->
        mint.registered["alice"] = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), host).cx1()
        val result = assertIs<OnboardResult.Ready>(existing(words).onboard("alice", host))
        assertTrue(result.confirmed)
        assertEquals(0, mint.posts)
    }

    @Test
    fun `a mint that publishes no cx1 gives an unconfirmed address`() = withMint(publishCpub = false) { _, host ->
        val result = assertIs<OnboardResult.Ready>(wallet().onboard("alice", host))
        assertFalse(result.confirmed)
    }

    @Test
    fun `a mint that refuses registrations is reported, not retried under another name`() = withMint(registrationEnabled = false) { mint, host ->
        assertIs<OnboardResult.RegistrationFailed>(wallet().onboard("alice", host))
        assertEquals(1, mint.posts)
    }

    @Test
    fun `invalid names are rejected locally`() = withMint { mint, host ->
        assertIs<OnboardResult.UsernameInvalid>(wallet().onboard("a".repeat(33), host))
        assertIs<OnboardResult.UsernameInvalid>(wallet().onboard("al ice", host))
        assertEquals(0, mint.posts)
    }

    @Test
    fun `address checks tell free, taken and ours apart`() = withMint { mint, host ->
        val wallet = existing(words)
        mint.registered["bob"] = CashBranch.fromSeed(ByteArray(32) { 9 }, host).cx1()
        mint.registered["carol"] = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), host).cx1()
        assertEquals(AddressStatus.Available, wallet.checkAddress("alice", host))
        assertEquals(AddressStatus.Taken, wallet.checkAddress("bob", host))
        assertEquals(AddressStatus.Unconfirmed, wallet.checkAddress("mint", host), "reserved names publish no cx1")
        assertIs<OnboardResult.UsernameTaken>(wallet.onboard("mint", host), "and onboarding treats them as taken")
        assertEquals(AddressStatus.Ours, wallet.checkAddress("carol", host))
    }

    @Test
    fun `a second account gets its own phrase and address, and the first stays intact`() = withMint { mint, host ->
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val wallet = wallet(store, seeds)
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        val first = assertNotNull(store.currentAccountId())

        assertIs<OnboardResult.Ready>(wallet.onboard("bob", host, newAccountName = "Savings"))
        val second = assertNotNull(store.currentAccountId())
        assertTrue(first != second)
        assertEquals("Savings", store.account(second)?.name)
        assertTrue(seeds.load(first) != seeds.load(second), "separate recovery phrases")
        assertTrue(mint.registered["alice"] != mint.registered["bob"], "each address points at its own account")
        assertEquals("bob", wallet.profile.first()?.username)
        assertEquals(listOf("alice", "bob"), wallet.accounts.first().map { it.profile?.username })

        // A failed attempt at a third account leaves no trace.
        mint.registered["carol"] = mint.registered.getValue("alice")
        assertIs<OnboardResult.UsernameTaken>(wallet.onboard("carol", host, newAccountName = "Third"))
        assertEquals(2, wallet.accounts.first().size)
        assertEquals(setOf(first, second), seeds.accountIds())
        assertEquals(second, store.currentAccountId())
    }

    @Test
    fun `an account set up without an address gets its first one as primary, on its home mint`() = withMint { _, host ->
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), requireAddress = false)
        assertIs<CreateAccountResult.Created>(wallet.createAccount(host, "Main"))
        assertIs<OnboardResult.Ready>(wallet.addAddress("alice", limit = 1))
        assertEquals("alice@$host", wallet.profile.first()?.address)
        assertEquals(listOf(true), wallet.addresses.first().map { it.primary })
        assertEquals(OnboardResult.LimitReached(1), wallet.addAddress("bob", limit = 1))
    }

    @Test
    fun `more addresses can be added up to the limit, and the first stays primary`() = withMint { mint, host ->
        val store = InMemoryWalletStore()
        val wallet = wallet(store)
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        val posts = mint.posts

        assertEquals(OnboardResult.LimitReached(1), wallet.addAddress("bob", limit = 1), "the default limit is one address per mint")
        assertEquals(posts, mint.posts, "nothing is registered over the limit")

        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2))
        assertEquals(mint.registered["alice"], mint.registered["bob"], "both names point at this account's cx1")
        assertEquals(OnboardResult.LimitReached(2), wallet.addAddress("carol", limit = 2))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2), "an address the account already has is not counted twice")

        val entries = wallet.addresses.first()
        assertEquals(listOf("alice" to true, "bob" to false), entries.map { it.address.username to it.primary })
        assertEquals("alice", wallet.profile.first()?.username, "adding does not change the primary address")
        assertTrue(entries.all { it.status == AddressStatus.Ours })
    }

    @Test
    fun `an address on another mint uses that mint's keys of the same phrase, and is never the primary one`() = withMint { home, homeHost ->
        MockWebServer().use { server ->
            val other = FakeMint(server, publishCpub = true, registrationEnabled = true)
            server.dispatcher = other
            server.start()
            val otherHost = "localhost:${server.port}"
            val wallet = wallet()
            assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))

            val added = assertIs<OnboardResult.Ready>(wallet.addAddress("alice", limit = 1, host = otherHost), "the home mint's limit doesn't count there")
            assertTrue(added.confirmed)
            assertNotEquals(home.registered["alice"], other.registered["alice"], "each mint gets its own cx1 of the phrase")
            val entries = wallet.addresses.first()
            assertEquals(listOf("alice@$homeHost" to true, "alice@$otherHost" to false), entries.map { it.address.address to it.primary })
            assertEquals("alice@$homeHost", wallet.profile.first()?.address)
            assertEquals(homeHost, wallet.homeHost.first())
            assertFailsWith<IllegalStateException> { wallet.setPrimaryAddress(entries[1].address) }
            assertEquals(OnboardResult.LimitReached(1), wallet.addAddress("bob", limit = 1, host = otherHost), "the limit is per mint")
        }
    }

    /** Runs [test] with a second fake mint next to the one [withMint] gives. */
    private fun withTwoMints(test: suspend (home: FakeMint, homeHost: String, other: FakeMint, otherHost: String) -> Unit) =
        withMint { home, homeHost ->
            MockWebServer().use { server ->
                val other = FakeMint(server, publishCpub = true, registrationEnabled = true)
                server.dispatcher = other
                server.start()
                test(home, homeHost, other, "localhost:${server.port}")
            }
        }

    @Test
    fun `changing the home mint claims the name there as the primary address, and the old one stays`() = withTwoMints { home, homeHost, other, otherHost ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        assertTrue(wallet.mints.first().single { it.host == homeHost }.trusted)

        val moved = assertIs<OnboardResult.Ready>(wallet.changeHome(otherHost, "alice"))
        assertTrue(moved.confirmed)
        assertEquals(otherHost, wallet.homeHost.first())
        assertEquals("alice@$otherHost", wallet.profile.first()?.address)
        assertEquals(listOf("alice@$homeHost" to false, "alice@$otherHost" to true), wallet.addresses.first().map { it.address.address to it.primary })
        assertNotEquals(home.registered["alice"], other.registered["alice"], "the new home's own cx1 of the phrase")
        val mints = wallet.mints.first()
        assertEquals(TrustDefault.Home, mints.single { it.host == otherHost }.trustedByDefault)
        assertTrue(mints.single { it.host == homeHost }.trusted, "the old home stays trusted")

        // And back: the address there is already ours, so nothing is registered again.
        val posts = home.posts
        assertIs<OnboardResult.Ready>(wallet.changeHome(homeHost, "alice"))
        assertEquals(homeHost, wallet.homeHost.first())
        assertEquals("alice@$homeHost", wallet.profile.first()?.address)
        assertEquals(posts, home.posts)
    }

    @Test
    fun `the mints are the device's, each account's mints its own, and only an unused one can be removed`() = withTwoMints { _, homeHost, _, otherHost ->
        val store = InMemoryWalletStore()
        val wallet = wallet(store)
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        val main = checkNotNull(store.currentAccountId())
        assertIs<OnboardResult.Ready>(wallet.onboard("bob", otherHost, newAccountName = "Savings"))
        val savings = checkNotNull(store.currentAccountId())

        // Device-wide: both homes, with whose home they are; nothing about money.
        assertEquals(listOf(homeHost to listOf("Main"), otherHost to listOf("Savings")).sortedBy { it.first }, wallet.mints.first().map { it.host to it.homeOf }.sortedBy { it.first })
        // The active account's: only its own home.
        assertEquals(listOf(otherHost), wallet.accountMints.first().map { it.host })
        assertEquals(RemoveMintResult.InUse(MintUse.Home), wallet.removeMint(otherHost))

        // Savings moves home to the first mint; its address on the other one still ties it.
        assertIs<OnboardResult.Ready>(wallet.changeHome(homeHost, "bob"))
        assertEquals(listOf(homeHost, otherHost), wallet.accountMints.first().map { it.host }, "home first, then the old address")
        assertEquals(RemoveMintResult.InUse(MintUse.Address), wallet.removeMint(otherHost))
        store.deleteAddress(savings, "bob", otherHost)

        // A note of another account there, unspent, ties it; spent, it doesn't.
        val note = Note("q1", main, otherHost, NoteKind.KeyPath, 0, 1, 5_000, null, NoteState.Outstanding, 0)
        store.upsertNotes(listOf(note))
        assertEquals(RemoveMintResult.InUse(MintUse.Notes), wallet.removeMint(otherHost))
        assertFalse(wallet.mints.first().single { it.host == otherHost }.removable)
        store.upsertNotes(listOf(note.copy(state = NoteState.Spent)))

        // An operation there that hasn't settled ties it too.
        val open = JournalEntry("j1", main, OperationType.Mint, otherHost, "https://$otherHost/w", emptyList(), emptyList(), emptyList(), "", JournalStatus.Prepared, null, 0, 0)
        store.putJournal(open)
        assertEquals(RemoveMintResult.InUse(MintUse.OpenOperation), wallet.removeMint(otherHost))
        store.putJournal(open.copy(status = JournalStatus.Confirmed))

        assertTrue(wallet.mints.first().single { it.host == otherHost }.removable)
        val key = checkNotNull(store.mint(otherHost)?.mintPubkey)
        wallet.setMintTrusted(otherHost, true)
        assertEquals(RemoveMintResult.Removed, wallet.removeMint(otherHost))
        assertNull(store.mint(otherHost))
        assertEquals(listOf(homeHost), wallet.mints.first().map { it.host })
        assertEquals(NoteState.Spent, store.note("q1")?.state, "notes stay")

        // Added again: a first contact, untrusted until the user says otherwise.
        assertIs<ConnectResult.Connected>(wallet.addMint(otherHost))
        val again = wallet.mints.first().single { it.host == otherHost }
        assertEquals(key, again.pubkey)
        assertFalse(again.trusted)
    }

    @Test
    fun `an address added on another mint before is taken as it is when moving there`() = withTwoMints { _, homeHost, other, otherHost ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 1, host = otherHost))
        val posts = other.posts
        assertIs<OnboardResult.Ready>(wallet.changeHome(otherHost, "bob"))
        assertEquals(posts, other.posts, "nothing registered again")
        assertEquals("bob@$otherHost", wallet.profile.first()?.address)
        assertEquals(2, wallet.addresses.first().size)
    }

    @Test
    fun `a taken name or the same mint moves nothing`() = withTwoMints { _, homeHost, other, otherHost ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        other.registered["alice"] = CashBranch.fromSeed(ByteArray(32) { 7 }, otherHost).cx1()
        assertIs<OnboardResult.UsernameTaken>(wallet.changeHome(otherHost, "alice"))
        assertIs<OnboardResult.MintInvalid>(wallet.changeHome(homeHost, "alice"))
        assertEquals(homeHost, wallet.homeHost.first())
        assertEquals("alice@$homeHost", wallet.profile.first()?.address)
    }

    @Test
    fun `changing the home mint keeps an old home the user untrusted untrusted`() = withTwoMints { _, homeHost, _, otherHost ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        wallet.setMintTrusted(homeHost, false)
        assertIs<OnboardResult.Ready>(wallet.changeHome(otherHost, "alice"))
        assertFalse(wallet.mints.first().single { it.host == homeHost }.trusted)
    }

    @Test
    fun `an account without an address just changes its mint`() = withTwoMints { _, homeHost, _, otherHost ->
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), requireAddress = false)
        assertIs<CreateAccountResult.Created>(wallet.createAccount(homeHost, "Main"))
        assertIs<ConnectResult.Connected>(wallet.changeHome(otherHost))
        assertEquals(otherHost, wallet.homeHost.first())
        assertNull(wallet.profile.first())
        assertIs<ConnectResult.Failed>(wallet.changeHome(otherHost), "already home")
    }

    @Test
    fun `without a home address, an address on another mint still isn't primary`() = withMint { _, homeHost ->
        MockWebServer().use { server ->
            server.dispatcher = FakeMint(server, publishCpub = true, registrationEnabled = true)
            server.start()
            val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), requireAddress = false)
            assertIs<CreateAccountResult.Created>(wallet.createAccount(homeHost, "Main"))
            assertIs<OnboardResult.Ready>(wallet.addAddress("alice", limit = 1, host = "localhost:${server.port}"))
            assertNull(wallet.profile.first())
            assertEquals(homeHost, wallet.homeHost.first())
            assertEquals(listOf(false), wallet.addresses.first().map { it.primary })
        }
    }

    @Test
    fun `a name someone else holds is not added`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        mint.registered["bob"] = CashBranch.fromSeed(ByteArray(32) { 7 }, host).cx1()
        assertIs<OnboardResult.UsernameTaken>(wallet.addAddress("bob", limit = 5))
        assertEquals(1, wallet.addresses.first().size)
    }

    @Test
    fun `checking addresses tells which still point at this account`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 3))
        assertIs<OnboardResult.Ready>(wallet.addAddress("carol", limit = 3))
        mint.registered.remove("bob") // gone at the mint
        mint.registered["carol"] = CashBranch.fromSeed(ByteArray(32) { 7 }, host).cx1() // now someone else's
        wallet.checkAddresses()
        assertEquals(
            listOf(AddressStatus.Ours, AddressStatus.Available, AddressStatus.Taken),
            wallet.addresses.first().map { it.status },
        )
    }

    @Test
    fun `the primary address can be switched, and changing it keeps the old one registered`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2))
        val bob = wallet.addresses.first().single { it.address.username == "bob" }.address
        wallet.setPrimaryAddress(bob)
        assertEquals("bob", wallet.profile.first()?.username)
        assertEquals(listOf("alice" to false, "bob" to true), wallet.addresses.first().map { it.address.username to it.primary })

        // "Change primary address" registers a new name and makes it primary; the others stay.
        assertIs<OnboardResult.Ready>(wallet.onboard("carol", host))
        assertEquals("carol", wallet.profile.first()?.username)
        assertEquals(listOf("alice", "bob", "carol"), wallet.addresses.first().map { it.address.username })
        assertTrue(listOf("alice", "bob", "carol").all { mint.registered.containsKey(it) })
    }

    @Test
    fun `unregistering frees the name at the mint, but never the primary address`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2))
        val (alice, bob) = wallet.addresses.first().map { it.address }

        assertEquals(UnregisterResult.IsPrimary, wallet.unregisterAddress(alice))
        assertEquals(0, mint.deletes, "nothing is sent for the primary address")

        assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(bob))
        assertEquals(1, mint.deletes)
        assertFalse(mint.registered.containsKey("bob"), "the mint freed the name (the unregister proof verified)")
        assertEquals(listOf("alice"), wallet.addresses.first().map { it.address.username })
        assertEquals(AddressStatus.Available, wallet.checkAddress("bob", host))

        // Room for another address again.
        assertIs<OnboardResult.Ready>(wallet.addAddress("carol", limit = 2))
    }

    @Test
    fun `an address the mint keeps stays listed, and one already gone is just removed`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 3))
        assertIs<OnboardResult.Ready>(wallet.addAddress("carol", limit = 3))
        val entries = wallet.addresses.first().map { it.address }

        mint.unregisterEnabled = false
        assertIs<UnregisterResult.Failed>(wallet.unregisterAddress(entries[1]))
        assertEquals(3, wallet.addresses.first().size, "still registered, still listed")

        mint.registered.remove("carol") // freed elsewhere already
        assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(entries[2]))
        assertEquals(listOf("alice", "bob"), wallet.addresses.first().map { it.address.username })
    }

    @Test
    fun `deleting an account forgets its addresses`() = withMint { _, host ->
        val store = InMemoryWalletStore()
        val wallet = wallet(store)
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2))
        val id = assertNotNull(store.currentAccountId())
        wallet.deleteActiveAccount()
        assertTrue(store.addresses(id).first().isEmpty())
    }

    @Test
    fun `a host that is not an LNURLcash mint is rejected`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse(code = 404, body = "<html>Not Found</html>")
            }
            server.start()
            assertIs<OnboardResult.MintInvalid>(wallet().onboard("alice", "localhost:${server.port}"))
        }
    }
}
