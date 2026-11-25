package whitewallet.core.wallet

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.client.LnurlcashClient

class OnboardingTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    /** Behaves like lnurl-mint on LUD-26: a name is held by one share, whose holder alone is told `cx1 already used` for a PUT of it. */
    private class FakeMint(private val server: MockWebServer, private val registrationEnabled: Boolean = true) : Dispatcher() {
        val registered = ConcurrentHashMap<String, String>()
        var posts = 0
        /** Signed statements that this phone holds a name: each check of an address sends one per share. */
        var proofs = 0
        var deletes = 0
        /** When false, unregister requests are refused (the name stays registered). */
        var unregisterEnabled = true

        override fun dispatch(request: RecordedRequest): MockResponse {
            val base = server.url("/").toString().trimEnd('/')
            val path = request.url.encodedPath
            val pay = { extra: String ->
                MockResponse(
                    body =
                        """{"tag":"payRequest","callback":"$base/p/cb","minSendable":1000,"maxSendable":100000000,"metadata":"[[\"text/plain\",\"Mint fees: 1000,2000\"]$extra]","withdrawLink":"$base/w","commentAllowed":64}"""
                )
            }
            return when {
                path == "/.well-known/lnurlw/mint" ->
                    MockResponse(
                        body =
                            """{"tag":"withdrawRequest","callback":"$base/w","minWithdrawable":0,"maxWithdrawable":1000,"mintPubkey":"034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"}"""
                    )
                request.method == "PUT" && path.startsWith("/.well-known/lnurlp/") -> {
                    proofs++
                    val held = registered[path.substringAfterLast('/')] ?: return error("Unknown user.")
                    error(if (field(request, "cx1") == held) "cx1 already used" else "Invalid ownership signature.")
                }
                path == "/.well-known/lnurlp/mint" -> pay("")
                request.method == "GET" && path.startsWith("/.well-known/lnurlp/") -> {
                    if (registered[path.substringAfterLast('/')] == null) return error("Unknown user.")
                    pay("")
                }
                request.method == "POST" && path.startsWith("/.well-known/lnurlp/") -> {
                    posts++
                    if (!registrationEnabled) return error("Not found")
                    val name = path.substringAfterLast('/')
                    if (registered.containsKey(name)) return error("Username already registered.")
                    registered[name] = field(request, "cx1")
                    MockResponse(body = """{"status":"OK"}""")
                }
                request.method == "DELETE" && path.startsWith("/.well-known/lnurlp/") -> {
                    deletes++
                    val name = path.substringAfterLast('/')
                    if (!registered.containsKey(name))
                        return MockResponse(code = 404, body = """{"status":"ERROR","reason":"Unknown user."}""")
                    if (!unregisterEnabled) return error("Not found")
                    registered.remove(name)
                    MockResponse(body = """{"status":"OK"}""")
                }
                path == "/w" -> error("Unknown note.")
                else -> MockResponse(code = 404)
            }
        }

        private fun field(request: RecordedRequest, name: String): String =
            checkNotNull(request.body?.utf8()).split('&').first { it.startsWith("$name=") }.substringAfter('=')

        private fun error(reason: String) = MockResponse(body = """{"status":"ERROR","reason":"$reason"}""")
    }

    private fun withMint(registrationEnabled: Boolean = true, test: suspend (FakeMint, String) -> Unit) =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                val mint = FakeMint(server, registrationEnabled)
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
    fun `a free name is claimed and confirmed by the mint`() = withMint { mint, host ->
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val wallet = wallet(store, seeds)
        val result = assertIs<OnboardResult.Ready>(wallet.onboard(" Alice ", host))
        assertTrue(result.confirmed)
        assertEquals("alice@$host", result.address.address)
        assertEquals(AddressStatus.Ours, wallet.addressStatus.first())
        val accountId = assertNotNull(store.currentAccountId())
        assertEquals("Main", store.account(accountId)?.name)
        assertEquals(12, assertNotNull(seeds.load(accountId)).size)
        assertEquals(1000L, store.mint(host)?.feeBaseMsat)
        assertEquals(1, mint.posts)
    }

    @Test
    fun `a name someone else holds is reported as taken with suggestions, without registering anything`() = withMint { mint, host ->
        mint.registered["alice"] = SOMEONE_ELSE
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val result = assertIs<OnboardResult.UsernameTaken>(wallet(store, seeds).onboard("alice", host))
        assertTrue(seeds.walletIds().isEmpty(), "the unused recovery phrase is discarded")
        assertTrue(result.suggestions.isNotEmpty() && "alice" !in result.suggestions)
        assertEquals(0, mint.posts)
        assertNull(store.currentAccountId(), "no account is created")
    }

    @Test
    fun `a name this wallet already holds is recognised without registering again`() = withMint { mint, host ->
        mint.registered["alice"] = testKeys(words, host).share(0).cx1()
        val result = assertIs<OnboardResult.Ready>(existing(words).onboard("alice", host))
        assertTrue(result.confirmed)
        assertEquals(0, mint.posts)
    }

    @Test
    fun `a mint that refuses registrations is reported, not retried under another name`() =
        withMint(registrationEnabled = false) { mint, host ->
            val store = InMemoryWalletStore()
            assertIs<OnboardResult.RegistrationFailed>(wallet(store).onboard("alice", host))
            assertEquals(1, mint.posts)
            // The account was saved before its mint and counters, and goes again with them.
            assertEquals(emptyList(), store.accounts.first())
            assertEquals(emptyMap(), store.mintMap.value)
            assertEquals(emptyMap(), store.keyCounters)
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
        mint.registered["bob"] = SOMEONE_ELSE
        mint.registered["carol"] = testKeys(words, host).share(0).cx1()
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
        assertEquals("bob", wallet.primaryAddress.first()?.username)
        assertEquals(listOf("alice", "bob"), wallet.accounts.first().map { store.currentPrimaryAddress(it.account.id)?.username })

        // A failed attempt at a third account leaves no trace.
        mint.registered["carol"] = mint.registered.getValue("alice")
        assertIs<OnboardResult.UsernameTaken>(wallet.onboard("carol", host, newAccountName = "Third"))
        assertEquals(2, wallet.accounts.first().size)
        assertEquals(setOf(first, second), seeds.walletIds())
        assertEquals(second, store.currentAccountId())
    }

    @Test
    fun `an account set up without an address gets its first one as primary, on its home mint`() = withMint { _, host ->
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())
        assertIs<CreateAccountResult.Created>(wallet.createAccount(host, "Main"))
        assertIs<OnboardResult.Ready>(wallet.addAddress("alice", limit = 1))
        assertEquals("alice@$host", wallet.primaryAddress.first()?.address)
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
        assertNotEquals(mint.registered["alice"], mint.registered["bob"], "each name has a share of its own")
        assertEquals(OnboardResult.LimitReached(2), wallet.addAddress("carol", limit = 2))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2), "an address the account already has is not counted twice")

        val entries = wallet.addresses.first()
        assertEquals(listOf("alice" to true, "bob" to false), entries.map { it.address.username to it.primary })
        assertEquals("alice", wallet.primaryAddress.first()?.username, "adding does not change the primary address")
        assertTrue(entries.all { it.status == AddressStatus.Ours })
    }

    @Test
    fun `an address on another mint uses that mint's keys of the same phrase, and may be made the primary one`() =
        withMint { home, homeHost ->
            MockWebServer().use { server ->
                val other = FakeMint(server)
                server.dispatcher = other
                server.start()
                val otherHost = "localhost:${server.port}"
                val wallet = wallet()
                assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))

                val added =
                    assertIs<OnboardResult.Ready>(
                        wallet.addAddress("alice", limit = 1, host = otherHost),
                        "the home mint's limit doesn't count there",
                    )
                assertTrue(added.confirmed)
                assertNotEquals(home.registered["alice"], other.registered["alice"], "each mint gets its own cx1 of the phrase")
                val entries = wallet.addresses.first()
                assertEquals(
                    listOf("alice@$homeHost" to true, "alice@$otherHost" to false),
                    entries.map { it.address.address to it.primary },
                )
                assertEquals("alice@$homeHost", wallet.primaryAddress.first()?.address)
                assertEquals(homeHost, wallet.homeHost.first())
                wallet.setPrimaryAddress(entries[1].address)
                assertEquals("alice@$otherHost", wallet.primaryAddress.first()?.address)
                assertEquals(homeHost, wallet.homeHost.first(), "the home mint stays")
                assertEquals(OnboardResult.LimitReached(1), wallet.addAddress("bob", limit = 1, host = otherHost), "the limit is per mint")
            }
        }

    /** Runs [test] with a second fake mint next to the one [withMint] gives. */
    private fun withTwoMints(test: suspend (home: FakeMint, homeHost: String, other: FakeMint, otherHost: String) -> Unit) =
        withMint { home, homeHost ->
            MockWebServer().use { server ->
                val other = FakeMint(server)
                server.dispatcher = other
                server.start()
                test(home, homeHost, other, "localhost:${server.port}")
            }
        }

    @Test
    fun `changing the home mint is only a setting, addresses stay, and the old home stays trusted`() =
        withTwoMints { home, homeHost, other, otherHost ->
            val wallet = wallet()
            assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
            assertTrue(wallet.mints.first().single { it.host == homeHost }.trusted)

            assertIs<ConnectResult.Connected>(wallet.setHomeMint(otherHost).result)
            assertEquals(otherHost, wallet.homeHost.first())
            assertEquals(0, other.posts, "nothing is registered there")
            assertEquals("alice@$homeHost", wallet.primaryAddress.first()?.address, "the primary address stays where it is")
            val mints = wallet.mints.first()
            assertEquals(TrustDefault.Home, mints.single { it.host == otherHost }.trustedByDefault)
            assertTrue(mints.single { it.host == homeHost }.trusted, "the old home stays trusted")

            // And back.
            val posts = home.posts
            assertIs<ConnectResult.Connected>(wallet.setHomeMint(homeHost).result)
            assertEquals(homeHost, wallet.homeHost.first())
            assertEquals(posts, home.posts)
        }

    @Test
    fun `a forgotten home mint stays a mint and a trusted one, and can be made home again`() = withMint { _, homeHost ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))

        wallet.forgetHomeMint()
        assertEquals(null, wallet.homeHost.first())
        assertTrue(wallet.mints.first().single { it.host == homeHost }.trusted)
        assertEquals("alice@$homeHost", wallet.primaryAddress.first()?.address)

        assertIs<ConnectResult.Connected>(wallet.setHomeMint(homeHost).result)
        assertEquals(homeHost, wallet.homeHost.first())
    }

    @Test
    fun `each wallet has its own mints, each account its own of them, and only an unused one can be removed`() =
        withTwoMints { _, homeHost, _, otherHost ->
            val store = InMemoryWalletStore()
            val wallet = wallet(store)
            assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
            val main = checkNotNull(store.currentAccountId())
            assertIs<OnboardResult.Ready>(wallet.onboard("bob", otherHost, newAccountName = "Savings"))
            val savings = checkNotNull(store.currentAccountId())

            // The active wallet's: its home; nothing about money. Main's wallet is another.
            assertEquals(listOf(otherHost to true), wallet.mints.first().map { it.host to it.isHome })
            // The active account's: only its own home.
            assertEquals(listOf(otherHost), wallet.accountMints.first().map { it.host })
            assertEquals(RemoveMintResult.InUse(MintUse.Address), wallet.removeMint(otherHost), "its home, and its address is there")

            // Savings' wallet makes the first mint its home; its address on the other one still ties it.
            assertIs<ConnectResult.Connected>(wallet.setHomeMint(homeHost).result)
            assertEquals(listOf(homeHost, otherHost), wallet.accountMints.first().map { it.host }, "home first, then the old address")
            assertEquals(RemoveMintResult.InUse(MintUse.Address), wallet.removeMint(otherHost))
            store.deleteAddress(savings, "bob", otherHost)

            // A note there of another wallet's account ties nothing; one of this wallet's, unspent, does; spent, it doesn't.
            store.upsertNotes(listOf(Note("q0", main, otherHost, NoteKind.KeyPath, 0, 1, 5_000, null, NoteState.Outstanding, 0)))
            assertTrue(wallet.mints.first().single { it.host == otherHost }.removable)
            val note = Note("q1", savings, otherHost, NoteKind.KeyPath, 0, 1, 5_000, null, NoteState.Outstanding, 0)
            store.upsertNotes(listOf(note))
            assertEquals(RemoveMintResult.InUse(MintUse.Notes), wallet.removeMint(otherHost))
            assertFalse(wallet.mints.first().single { it.host == otherHost }.removable)
            store.upsertNotes(listOf(note.copy(state = NoteState.Spent)))

            // An operation there that hasn't settled ties it too, only this wallet's.
            val open =
                JournalEntry(
                    "j1",
                    savings,
                    OperationType.Mint,
                    otherHost,
                    "https://$otherHost/w",
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    "",
                    JournalStatus.Prepared,
                    null,
                    0,
                    0,
                )
            store.putJournal(open)
            store.putJournal(open.copy(id = "j0", accountId = main))
            assertEquals(RemoveMintResult.InUse(MintUse.OpenOperation), wallet.removeMint(otherHost))
            store.putJournal(open.copy(status = JournalStatus.Confirmed))

            val key = checkNotNull(store.mint(otherHost)?.mintPubkey)
            wallet.setMintTrusted(otherHost, true)
            assertEquals(RemoveMintResult.Removed, wallet.removeMint(otherHost))
            assertNull(store.mint(otherHost))
            assertEquals(listOf(homeHost), wallet.mints.first().map { it.host })
            assertEquals(NoteState.Spent, store.note("q1")?.state, "notes stay")

            // Main's wallet has its home, and the other mint only for the note it holds there: never connected, it has no key there.
            wallet.switchAccount(main)
            assertEquals(
                listOf(Triple(homeHost, true, true), Triple(otherHost, false, false)),
                wallet.mints.first().map { Triple(it.host, it.isHome, it.pubkey != null) },
            )
            wallet.switchAccount(savings)

            // Added again: a first contact, untrusted until the user says otherwise.
            assertIs<ConnectResult.Connected>(wallet.addMint(otherHost))
            val again = wallet.mints.first().single { it.host == otherHost }
            assertEquals(key, again.pubkey)
            assertFalse(again.trusted)
        }

    @Test
    fun `the home mint can't be made home again`() = withTwoMints { _, homeHost, _, _ ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        assertIs<ConnectResult.Failed>(wallet.setHomeMint(homeHost).result)
        assertEquals(homeHost, wallet.homeHost.first())
    }

    @Test
    fun `changing the home mint keeps an old home the user untrusted untrusted`() = withTwoMints { _, homeHost, _, otherHost ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", homeHost))
        wallet.setMintTrusted(homeHost, false)
        assertIs<ConnectResult.Connected>(wallet.setHomeMint(otherHost).result)
        assertFalse(wallet.mints.first().single { it.host == homeHost }.trusted)
    }

    @Test
    fun `an account without an address changes its home too`() = withTwoMints { _, homeHost, _, otherHost ->
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        assertIs<CreateAccountResult.Created>(wallet.createAccount(homeHost, "Main"))
        assertIs<ConnectResult.Connected>(wallet.setHomeMint(otherHost).result)
        assertEquals(otherHost, wallet.homeHost.first())
        assertNull(wallet.primaryAddress.first())
        assertIs<ConnectResult.Failed>(wallet.setHomeMint(otherHost).result, "already home")
    }

    @Test
    fun `wallets are listed oldest first with their accounts, and the active wallet and account can be renamed`() = runBlocking {
        val store = InMemoryWalletStore()
        var clock = 1L
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock++ })
        val main = assertIs<CreateAccountResult.Created>(wallet.createAccount(null, "Main")).accountId
        store.useAccount(main)
        val savings = assertIs<CreateAccountResult.Created>(wallet.createAccount(null, "Savings", inActiveWallet = true)).accountId
        val work = assertIs<CreateAccountResult.Created>(wallet.createAccount(null, "Work")).accountId
        val groups = wallet.walletGroups.first()
        assertEquals(
            listOf(1 to listOf(main, savings), 2 to listOf(work)),
            groups.map { g -> g.number to g.accounts.map { it.account.id } },
        )
        assertEquals(listOf<String?>(null, null), groups.map { it.wallet.name }, "a wallet has no name until the user gives it one")

        // Only the wallet and the account in use are renamed.
        wallet.renameWallet("  Business ")
        wallet.renameAccount(" Shop ")
        assertEquals(listOf(null, "Business"), wallet.walletGroups.first().map { it.wallet.name })
        assertEquals(listOf("Main", "Savings", "Shop"), listOf(main, savings, work).map { store.account(it)?.name })
        assertFailsWith<IllegalArgumentException> { wallet.renameAccount("  ") }
        // An empty wallet name takes the name away: it shows its number again.
        wallet.renameWallet(" ")
        assertEquals(listOf<String?>(null, null), wallet.walletGroups.first().map { it.wallet.name })
        assertEquals(2, wallet.walletGroups.first().single { it.wallet.id == store.account(work)?.walletId }.number)
    }

    @Test
    fun `a wallet set up without a mint can't receive, and its first mint becomes its accounts' home`() =
        withTwoMints { _, homeHost, _, otherHost ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())
            val main = assertIs<CreateAccountResult.Created>(wallet.createAccount(null, "Main")).accountId
            store.useAccount(main)
            val savings = assertIs<CreateAccountResult.Created>(wallet.createAccount(null, "Savings", inActiveWallet = true)).accountId
            assertNull(store.homeOf(main))
            assertFalse(wallet.hasMint.first())
            assertEquals(InvoiceRequest.Unreachable, wallet.requestInvoice(1_000_000))
            assertEquals(emptyList(), wallet.mints.first())

            assertIs<ConnectResult.Connected>(wallet.addMint(homeHost))
            assertTrue(wallet.hasMint.first())
            assertEquals(homeHost, store.homeOf(main))
            assertEquals(homeHost, store.homeOf(savings))

            // A second mint is only added; the home stays.
            assertIs<ConnectResult.Connected>(wallet.addMint(otherHost))
            assertEquals(homeHost, store.homeOf(savings))

            // Each account's home is its own: Savings moves to the other mint, Main stays.
            assertIs<ConnectResult.Connected>(wallet.setHomeMint(otherHost).result)
            assertEquals(otherHost, wallet.homeHost.first())
            assertEquals(otherHost, store.homeOf(savings))
            assertEquals(homeHost, store.homeOf(main))
            assertTrue(wallet.mints.first().all { it.trusted }, "every account's home is trusted by default")
            // A new account starts with the home of the account in use.
            store.useAccount(savings)
            val third = assertIs<CreateAccountResult.Created>(wallet.createAccount(null, "Third", inActiveWallet = true)).accountId
            assertEquals(otherHost, store.homeOf(third))

            // A home may be removed while nothing is there: the accounts that had it have none until one is picked.
            assertEquals(RemoveMintResult.Removed, wallet.removeMint(homeHost))
            assertNull(store.homeOf(main))
            assertEquals(otherHost, store.homeOf(savings))
            assertTrue(wallet.hasMint.first())
            // The account in use's own home too: it has none, nor the others that had it.
            assertEquals(RemoveMintResult.Removed, wallet.removeMint(otherHost))
            assertNull(store.homeOf(savings))
            assertNull(store.homeOf(third))
            assertNull(wallet.homeHost.first())
        }

    @Test
    fun `an account's first address is its primary one, on whichever mint, and the home stays`() = withMint { _, homeHost ->
        MockWebServer().use { server ->
            server.dispatcher = FakeMint(server)
            server.start()
            val otherHost = "localhost:${server.port}"
            val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertIs<CreateAccountResult.Created>(wallet.createAccount(homeHost, "Main"))
            assertIs<OnboardResult.Ready>(wallet.addAddress("alice", limit = 1, host = otherHost))
            assertEquals("alice@$otherHost", wallet.primaryAddress.first()?.address)
            assertEquals(homeHost, wallet.homeHost.first())

            // A second one is added beside it.
            assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 1))
            assertEquals(listOf("alice" to true, "bob" to false), wallet.addresses.first().map { it.address.username to it.primary })
        }
    }

    @Test
    fun `a name someone else holds is not added`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        mint.registered["bob"] = SOMEONE_ELSE
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
        mint.registered["carol"] = SOMEONE_ELSE // now someone else's
        wallet.checkAddresses()
        assertEquals(
            listOf(AddressStatus.Ours, AddressStatus.Available, AddressStatus.Taken),
            wallet.addresses.first().map { it.status },
        )
    }

    @Test
    fun `a refresh never checks an address`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        val before = mint.proofs
        wallet.refresh()
        assertEquals(before, mint.proofs, "a check tells the mint, signed, that this phone holds the name")
    }

    @Test
    fun `the address shown is checked again only once the check interval is over, also after a restart`() = withMint { mint, host ->
        var clock = 1_000L
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val checks = AddressChecks.InMemory()
        val wallet = Wallet(store, seeds, LnurlcashClient(), addressChecks = checks, now = { clock })
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        val claimed = mint.proofs
        wallet.checkShownAddress("alice@$host")
        assertEquals(claimed, mint.proofs, "just confirmed when it was claimed")
        val restarted = Wallet(store, seeds, LnurlcashClient(), addressChecks = checks, now = { clock })
        assertEquals(AddressStatus.Ours, restarted.addressStatus.first(), "the answer is kept")
        clock += WalletSettings().addressCheckIntervalMillis - 1
        restarted.checkShownAddress("alice@$host")
        assertEquals(claimed, mint.proofs)
        mint.registered["alice"] = SOMEONE_ELSE
        clock += 1
        restarted.checkShownAddress("alice@$host")
        assertTrue(mint.proofs > claimed)
        assertEquals(AddressStatus.Taken, restarted.addressStatus.first())
    }

    @Test
    fun `a check that fails leaves the last answer`() = withMint { _, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        val alice = wallet.addresses.first().single().address
        wallet.setAddressStatus(wallet.activeId(), alice.address, AddressStatus.CheckFailed("no answer"))
        assertEquals(AddressStatus.Ours, wallet.addressStatus.first())
    }

    @Test
    fun `the primary address can be switched, and changing it keeps the old one registered`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2))
        val bob = wallet.addresses.first().single { it.address.username == "bob" }.address
        wallet.setPrimaryAddress(bob)
        assertEquals("bob", wallet.primaryAddress.first()?.username)
        assertEquals(listOf("alice" to false, "bob" to true), wallet.addresses.first().map { it.address.username to it.primary })

        // "Change primary address" registers a new name and makes it primary; the others stay.
        assertIs<OnboardResult.Ready>(wallet.onboard("carol", host))
        assertEquals("carol", wallet.primaryAddress.first()?.username)
        assertEquals(listOf("alice", "bob", "carol"), wallet.addresses.first().map { it.address.username })
        assertTrue(listOf("alice", "bob", "carol").all { mint.registered.containsKey(it) })
    }

    @Test
    fun `unregistering frees the name at the mint, the primary one too, which leaves none primary`() = withMint { mint, host ->
        val wallet = wallet()
        assertIs<OnboardResult.Ready>(wallet.onboard("alice", host))
        assertIs<OnboardResult.Ready>(wallet.addAddress("bob", limit = 2))
        val (alice, bob) = wallet.addresses.first().map { it.address }

        assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(bob))
        assertEquals(1, mint.deletes)
        assertFalse(mint.registered.containsKey("bob"), "the mint freed the name (the unregister proof verified)")
        assertEquals(listOf("alice"), wallet.addresses.first().map { it.address.username })
        assertEquals(AddressStatus.Available, wallet.checkAddress("bob", host))

        assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(alice))
        assertNull(wallet.primaryAddress.first())
        assertEquals(emptyList(), wallet.addresses.first())

        // Room for another address again; the next one is primary.
        assertIs<OnboardResult.Ready>(wallet.addAddress("carol", limit = 2))
        assertEquals("carol", wallet.primaryAddress.first()?.username)
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
    fun `a host that is not an LNURLcash mint is rejected`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) = MockResponse(code = 404, body = "<html>Not Found</html>")
                    }
                server.start()
                assertIs<OnboardResult.MintInvalid>(wallet().onboard("alice", "localhost:${server.port}"))
            }
        }

    private companion object {
        /** A share of another wallet: the mint only compares it. */
        const val SOMEONE_ELSE = "cx1someoneelse"
    }
}
