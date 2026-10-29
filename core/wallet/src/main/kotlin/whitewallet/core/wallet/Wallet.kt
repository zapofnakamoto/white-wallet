package whitewallet.core.wallet

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PublicKey
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.Activity
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.brand.NoteStrategy
import whitewallet.brand.ReleaseStage
import whitewallet.core.model.OperationType
import whitewallet.core.model.Profile
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.Mint
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * The wallet's entry point. It holds several accounts, each with its own recovery phrase; everything except
 * account management acts on the *active* account. Operations that touch notes run under one lock, so counters,
 * the journal and the note table are never written concurrently, and the active account cannot change mid-operation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Wallet(
    internal val store: WalletStore,
    internal val seeds: SeedStore,
    internal val client: LnurlcashClient,
    internal val random: SecureRandom = SecureRandom(),
    internal val now: () -> Long = System::currentTimeMillis,
    private val policies: NotePolicies = NotePolicies.InMemory(),
    /** The note strategies this build offers (the brand's configuration and code). */
    private val strategies: NoteStrategies = NoteStrategies.builtIn(),
    /** Whether "collect at home mint" is offered, on by default, and its default fee cap (brand configuration). */
    val collectDefaults: CollectDefaults = CollectDefaults(),
    /** How many accounts this wallet may hold (brand `maxAccounts`); [AppDefaults.UNLIMITED] for no limit. */
    val maxAccounts: Int = AppDefaults.UNLIMITED,
    /**
     * Whether every account needs a Lightning address (brand `lightningAddress=required`). Without, an account is set up with
     * just its mint ([createAccount]) and receives by invoice ([requestInvoice]) until it registers one.
     */
    val requireAddress: Boolean = true,
    /** The user's trust choices for mints (see [mints]). */
    private val trust: MintTrustStore = MintTrustStore.InMemory(),
    /** Mints the brand ships as trusted, each with its key (brand `trustedMints`). */
    private val brandTrusted: List<MintKey> = emptyList(),
) {
    private val lock = Mutex()

    /** How long [pay] waits for a melt's payment to settle before reporting it as pending. */
    internal var payWaitMillis = 30_000L
    private val branches = ConcurrentHashMap<Pair<String, String>, CashBranch>()
    /** The last check of each address, by (account id, address). In memory; see [refresh] and [checkAddresses]. */
    private val addressStatuses = MutableStateFlow<Map<Pair<String, String>, AddressStatus>>(emptyMap())

    private val onboarding = Onboarding(this)
    private val scanner = AddressScanner(this)
    private val claimer = NoteClaimer(this)
    private val reconciler = Reconciler(this)
    private val addressCheck = AddressCheck(this)
    private val mover = FundsMover(this)
    private val inspector = KeyInspector(this)
    private val rotator = NoteRotator(this)
    private val discovery = IndexDiscovery(this)
    private val indexVerifiedAt = MutableStateFlow<Map<Triple<String, String, Int>, Long>>(emptyMap())
    private val policyEngine = NotePolicyEngine(this)
    private val restorer = Restore(this)
    private val payer = Payer(this)
    private val sender = NoteSender(this)
    private val noteManager = NoteManager(this)
    private val moneyMove = MoneyMove(this)
    private val invoices = InvoiceReceiver(this)
    private val mintInspector = MintInspector(this)
    private val _lastPolicyRun = MutableStateFlow<Map<String, PolicyRun>>(emptyMap())

    /**
     * Lookups that got no answer this session. Not saved: they say nothing about the key, so the last real answer
     * stays in the store and shows again after a restart.
     */
    private val failedProbes = MutableStateFlow<Map<KeyId, KeyProbe.Failed>>(emptyMap())

    /**
     * The mint's last answer for each of the active account's looked-up keys (scans, rechecks, rotates), for the
     * developer key page. Saved, so it survives restarts.
     */
    val keyProbes: Flow<Map<KeyId, KeyProbe>> = ofActive(emptyMap(), store::keyChecks)

    val accounts: Flow<List<AccountSummary>> get() = store.accounts
    val activeAccount: Flow<AccountSummary?> = combine(store.accounts, store.activeAccountId) { list, id -> list.firstOrNull { it.account.id == id } }

    val profile: Flow<Profile?> = ofActive(null, store::profile)

    /** The active account's home mint; null before there is an account. */
    val homeHost: Flow<String?> = activeAccount.map { it?.account?.host }.distinctUntilChanged()
    val notes: Flow<List<Note>> = ofActive(emptyList(), store::notes)
    val balanceMsat: Flow<Long> = notes.map { notes -> notes.filter { it.state == NoteState.Outstanding }.sumOf { it.amountMsat } }
    val backupVerified: Flow<Boolean> = ofActive(false, seeds::backupVerified)

    /** The last known status of the active account's primary address; null until checked. Refreshed by [refresh]. */
    val addressStatus: Flow<AddressStatus?> = combine(store.activeAccountId, profile, addressStatuses) { id, primary, statuses ->
        if (id == null || primary == null) null else statuses[id to primary.address]
    }

    /** Every Lightning address of the active account, oldest first, with its last check. */
    val addresses: Flow<List<AddressEntry>> = ofActive(emptyList()) { id ->
        combine(store.addresses(id), store.profile(id), addressStatuses) { list, primary, statuses ->
            list.map { AddressEntry(it, primary?.username == it.username && primary.host == it.host, statuses[id to it.address]) }
        }
    }

    /** The active account's auto-rotate setting. */
    val autoRotate: Flow<Boolean> = ofActive(false, policies::autoRotate)

    /** The active account's history, newest first (see [Activity]). */
    val activity: Flow<List<Activity>> = ofActive(emptyList(), store::activities)

    /** Whether another account may be created or restored ([maxAccounts]). */
    val canAddAccount: Flow<Boolean> = store.accounts.map { it.size < maxAccounts }

    /** The active account's note strategy: its choice, or the brand's default. */
    val noteStrategy: Flow<NoteStrategy> = ofActive(strategies.default) { id -> policies.strategy(id).map(strategies::resolve) }

    /** The note strategies a user can choose from, in the brand's order. */
    val noteStrategies: List<NoteStrategy> get() = strategies.offered

    /** Whether the active account moves notes from other mints to its home mint (see [CollectDefaults.active]). */
    val collectAtHome: Flow<Boolean> = ofActive(false) { id -> policies.collect(id).map(collectDefaults::active) }

    /** The active account's fee cap for those moves, in percent. */
    val collectFeeCap: Flow<Int> = ofActive(collectDefaults.feeCapPercent, ::collectFeeCap)

    /** [accountId]'s fee cap for collecting, in percent: its own setting, or the brand's default. */
    private fun collectFeeCap(accountId: String): Flow<Int> = policies.collectFeeCap(accountId).map { it ?: collectDefaults.feeCapPercent }

    /** Switches collecting at the home mint on or off for the active account and applies it right away. */
    suspend fun setCollectAtHome(enabled: Boolean) = lock.withLock {
        check(collectDefaults.offered) { "collecting at the home mint is not offered" }
        val id = activeId()
        policies.setCollect(id, enabled)
        applyPolicies(id)
    }

    /** Sets the active account's fee cap for collecting, in percent, and applies it right away. */
    suspend fun setCollectFeeCap(percent: Int) = lock.withLock {
        require(percent in 0..100) { "fee cap must be 0 to 100 percent" }
        val id = activeId()
        policies.setCollectFeeCap(id, percent)
        applyPolicies(id)
    }

    /** Per (account, mint): until when collecting leaves that mint alone after a failed move. This session only. */
    private val collectBlocks = MutableStateFlow<Map<Pair<String, String>, Long>>(emptyMap())
    internal fun collectBlockedUntil(accountId: String, host: String): Long = collectBlocks.value[accountId to host] ?: 0
    internal fun blockCollect(accountId: String, host: String, untilMillis: Long) = collectBlocks.update { it + ((accountId to host) to untilMillis) }

    /** The last time the active account's note policies changed something, if they have this session. */
    val lastPolicyRun: Flow<PolicyRun?> = combine(store.activeAccountId, _lastPolicyRun) { id, runs -> runs[id] }

    /** Saves the active account's auto-rotate setting and applies it right away. */
    suspend fun setAutoRotate(enabled: Boolean) = lock.withLock {
        val id = activeId()
        policies.setAutoRotate(id, enabled)
        applyPolicies(id)
    }

    /** Saves the active account's note strategy (one of [noteStrategies], by id) and applies it right away. */
    suspend fun setNoteStrategy(strategyId: String) = lock.withLock {
        require(strategies.isOffered(strategyId)) { "note strategy '$strategyId' is not offered" }
        val id = activeId()
        policies.setStrategy(id, strategyId)
        applyPolicies(id)
    }

    suspend fun isSetUp(): Boolean = store.currentAccountId()?.let { !requireAddress || store.currentProfile(it) != null } ?: false

    /**
     * The active account exists but has no address yet although this app requires one: it was restored and the
     * address step wasn't finished.
     */
    suspend fun needsAddress(): Boolean = requireAddress && store.currentAccountId()?.let { store.currentProfile(it) == null } ?: false

    /**
     * Creates an account on the mint at [host] without a Lightning address, with a fresh recovery phrase, and makes
     * it active. Only where addresses aren't required ([requireAddress]); otherwise accounts start with [onboard].
     */
    suspend fun createAccount(host: String, name: String): CreateAccountResult = lock.withLock {
        check(!requireAddress) { "this app requires an address; use onboard" }
        onboarding.createAccount(host, name)
    }

    /**
     * Recovers an account from its recovery phrase on [host] and makes it active (see [Restore]). The account has no
     * address afterwards; give it one with [onboard] (no new account name), which accepts an address it already owns.
     */
    suspend fun restore(phrase: String, host: String, accountName: String): RestoreResult =
        lock.withLock { restorer.restore(phrase, host, accountName) }

    /** The active account's BIP-39 recovery phrase. Only for the backup screen; never log or persist it elsewhere. */
    suspend fun recoveryPhrase(): List<String> = checkNotNull(seeds.load(activeId())) { "account has no seed" }

    suspend fun markBackupVerified() = seeds.setBackupVerified(activeId())

    /**
     * Claims [username] on [host] and confirms the claim. With [newAccountName], creates a new account with its own
     * recovery phrase (kept only if the claim succeeds) and makes it active; without, changes the active account's
     * address. The very first call always creates an account.
     */
    suspend fun onboard(username: String, host: String, newAccountName: String? = null): OnboardResult =
        lock.withLock { onboarding.onboard(username, host, newAccountName) }

    /** Validates and stores a mint (and pins its key) without registering a username. */
    suspend fun connectMint(host: String): ConnectResult = lock.withLock { onboarding.connectMint(host) }

    /**
     * Checks whether [username]@[host] is free, taken, or already the active account's. Pass [forNewAccount] when
     * choosing a name for an account that does not exist yet, so nothing counts as ours. Read-only.
     */
    suspend fun checkAddress(username: String, host: String, forNewAccount: Boolean = false): AddressStatus =
        addressCheck.check(username, host, if (forNewAccount) null else store.currentAccountId())

    /**
     * Registers another address for the active account: on its home mint, or on [host] (same recovery phrase, that
     * mint's own keys; never the primary address). Refused with
     * [OnboardResult.LimitReached] once the account holds [limit] addresses there.
     */
    suspend fun addAddress(username: String, limit: Int, host: String? = null): OnboardResult =
        lock.withLock { onboarding.addAddress(username, limit, host) }

    /** Checks every address of the active account, or only [address], against its mint. */
    suspend fun checkAddresses(address: RegisteredAddress? = null) {
        val id = activeId()
        for (a in address?.let(::listOf) ?: store.addresses(id).first()) {
            setAddressStatus(id, a.address, addressCheck.check(a.username, a.host, id))
        }
    }

    /** Makes one of the active account's addresses the primary one (shown on Home and Receive). */
    suspend fun setPrimaryAddress(address: RegisteredAddress) = lock.withLock {
        val id = activeId()
        check(store.addresses(id).first().any { it.username == address.username && it.host == address.host }) { "not an address of this account" }
        check(address.host == store.account(id)?.host) { "the primary address is on the home mint; use changeHome" }
        store.saveProfile(id, Profile(address.username, address.host))
    }

    /**
     * Moves the active account's home to the mint at [host], with [username] there as its primary address: claimed like
     * [onboard] does, so a name the account already holds there is taken as it is. From then on invoices are minted
     * there. Money stays where it is: move it with [moveMoney] (an account that collects at its home mint also has it
     * moved by its next refresh). The old primary address stays registered, and the old home stays trusted if it was.
     */
    suspend fun changeHome(host: String, username: String): OnboardResult = changeHome(host, username, emptyList()).result

    /** [changeHome] for an account without a Lightning address ([requireAddress] `false`): only the mint changes. */
    suspend fun changeHome(host: String): ConnectResult = changeHome(host, emptyList()).result

    /**
     * [changeHome], then [moveMoney] of [lines] to the new home, with nothing in between: a refresh slipping in would
     * collect the money itself (when the account collects at its home mint) and leave the move nothing to do. Nothing
     * moves unless the change succeeds.
     */
    suspend fun changeHome(host: String, username: String, lines: List<MovePreview.Line>): HomeChanged<OnboardResult> = lock.withLock {
        val (id, keepTrusted) = leavingHome(host) ?: return@withLock HomeChanged(OnboardResult.MintInvalid(ALREADY_HOME))
        val result = onboarding.onboard(username, host, newAccountName = null)
        if (result !is OnboardResult.Ready) return@withLock HomeChanged(result)
        keepTrusted?.let { trust.setTrusted(it, true) }
        HomeChanged(result, moneyMove.move(id, result.profile.host, lines))
    }

    /** [changeHome] with [lines] for an account without a Lightning address. */
    suspend fun changeHome(host: String, lines: List<MovePreview.Line>): HomeChanged<ConnectResult> = lock.withLock {
        val (id, keepTrusted) = leavingHome(host) ?: return@withLock HomeChanged(ConnectResult.Failed(OnboardResult.MintInvalid(ALREADY_HOME)))
        check(store.currentProfile(id) == null) { "the account has an address; pass the name to use on the new home" }
        val result = onboarding.connectMint(host)
        if (result !is ConnectResult.Connected) return@withLock HomeChanged(result)
        store.saveAccount(checkNotNull(store.account(id)).copy(host = result.mint.host))
        keepTrusted?.let { trust.setTrusted(it, true) }
        HomeChanged(result, moneyMove.move(id, result.mint.host, lines))
    }

    /**
     * What moving the active account's money to [toHost] would do: per mint holding it (only [fromHost], if given),
     * what arrives and the fee, or why it can't move. Null if [toHost] is not a valid host. Connects to [toHost]
     * (pinning its key) to ask for the quotes; nothing is paid.
     */
    suspend fun previewMove(toHost: String, fromHost: String? = null): MovePreview? {
        val id = activeId()
        val h = Onboarding.normalizeHost(toHost) ?: return null
        return moneyMove.preview(id, h, fromHost, collectFeeCap(id).first())
    }

    /**
     * Moves the money of [lines] (from [previewMove], as the user confirmed them) to [toHost]: per mint, its notes
     * combined into one and paid over Lightning, only at the confirmed fee or less. Says per mint what happened.
     */
    suspend fun moveMoney(toHost: String, lines: List<MovePreview.Line>): List<MoneyMoved> = lock.withLock {
        val h = requireNotNull(Onboarding.normalizeHost(toHost)) { "not a valid host" }
        moneyMove.move(activeId(), h, lines)
    }

    /**
     * The active account and its home mint's trusted key, about to be left for [host]; null if [host] already is the
     * home. Its trust is read now: once the account has moved, the home default no longer covers it.
     */
    private suspend fun leavingHome(host: String): Pair<String, MintKey?>? {
        val id = activeId()
        val home = checkNotNull(store.account(id)) { "no active account" }.host
        if (Onboarding.normalizeHost(host) == home) return null
        return id to store.mint(home)?.mintPubkey?.let { MintKey(home, it) }?.takeIf { isTrusted(it) }
    }

    /** Frees one of the active account's addresses at its mint. Never the primary one. */
    suspend fun unregisterAddress(address: RegisteredAddress): UnregisterResult = lock.withLock { onboarding.unregister(activeId(), address) }

    suspend fun switchAccount(accountId: String) = lock.withLock {
        checkNotNull(store.account(accountId)) { "unknown account" }
        store.setActiveAccount(accountId)
    }

    /** Reads what the user entered or scanned: an invoice, a Lightning address or a pay link (see [Payer]). */
    suspend fun previewPayment(input: String): PayPreview = payer.preview(input)

    /** Whether [amountMsat] can be paid to [preview]'s destination right now, and the fee. */
    suspend fun quotePayment(preview: PayPreview.Ready, amountMsat: Long): PayQuote = payer.quote(preview, amountMsat)

    /** Pays [amountMsat] to [preview]'s destination from the active account. [comment] goes to pay links that accept one. */
    suspend fun pay(preview: PayPreview.Ready, amountMsat: Long, comment: String? = null): PayResult =
        lock.withLock { payer.pay(preview, amountMsat, comment) }

    /**
     * The name a new account on [host] suggests first: derived from the recovery phrase it is about to get, so a
     * restore finds the address again. Null for an invalid host.
     */
    suspend fun defaultUsername(host: String): String? {
        val h = Onboarding.normalizeHost(host) ?: return null
        val prepared = prepared()
        prepared.names[h]?.let { return it }
        val words = prepared.words.toList()
        return withContext(Dispatchers.Default) {
            val seed = RecoveryPhrase.seed(words)
            try { DefaultAddress.names(CashBranch.fromSeed(seed, h)).first() } finally { seed.fill(0) }
        }.also { prepared.names[h] = it }
    }

    /**
     * After a restore: the seed-derived address on [host] that is still registered to the active account, if any.
     * Null if none is (it was never taken, or was unregistered) or the mint can't be reached.
     */
    suspend fun recoverableUsername(host: String): String? {
        val accountId = activeId()
        val h = Onboarding.normalizeHost(host) ?: return null
        val branch = branchIfSeeded(accountId, h) ?: return null
        return DefaultAddress.names(branch).firstOrNull { addressCheck.check(it, h, accountId) == AddressStatus.Ours }
    }

    /** The active account's addresses a restore can't find by itself (not seed-derived): to be written down. */
    suspend fun addressesToWriteDown(): List<String> {
        val accountId = activeId()
        return store.addresses(accountId).first().filter { address ->
            val branch = branchIfSeeded(accountId, address.host) ?: return@filter true
            address.username !in DefaultAddress.names(branch)
        }.map { it.address }
    }

    /** A mint as the details page shows it, checked live (this also refreshes its stored fees and limits). */
    suspend fun mintDetails(host: String): MintDetails = mintInspector.details(host)

    /**
     * The active account's watch-only key (`cx1`) on [host]: it lets someone see the account's notes there and pay
     * it directly, but never spend. Null if the account has no recovery phrase on this device.
     */
    suspend fun watchOnlyKey(host: String): String? = branchIfSeeded(activeId(), host)?.cx1()

    /** The most one note can be sent as, from the active account's notes. */
    suspend fun sendableNoteMsat(): Long = sender.availableMsat()

    /** What sending [amountMsat] as a note would cost, or why it can't be made. */
    suspend fun quoteNote(amountMsat: Long): PayQuote = sender.quote(amountMsat)

    /** Makes a note worth exactly [amountMsat] and hands it out as a link (out of the balance until taken back). */
    suspend fun createNote(amountMsat: Long): CreateNoteResult = lock.withLock { sender.create(amountMsat) }

    /** A sent note of the active account, with its link while nobody has redeemed it. */
    suspend fun sharedNote(id: String): SharedNote? = sender.sharedNote(id)

    /** Takes back a sent note nobody has redeemed yet. */
    suspend fun reclaimNote(id: String): ReclaimResult = lock.withLock {
        val accountId = activeId()
        sender.reclaim(id).also { applyPolicies(accountId) }
    }

    /** Every account's notes (spent ones too), for what concerns the whole device. */
    private val allNotes: Flow<List<Note>> = ofEveryAccount(store::notes)

    /** Every account's Lightning addresses. */
    private val allAddresses: Flow<List<RegisteredAddress>> = ofEveryAccount(store::addresses)

    /**
     * Every mint the wallet has used (connected to, or holding any account's notes), on this device and for all
     * accounts, until removed ([removeMint]): its key, whether it's trusted and why, whose home it is, and whether it can
     * be removed. Homes first, then by host. Trust follows [isTrusted]. Nothing about one account's money: see
     * [accountMints].
     */
    val mints: Flow<List<MintInfo>> = combine(store.mints, store.accounts, allNotes, allAddresses, combine(trust.trusted, trust.untrusted, ::Pair)) { mints, accounts, notes, addresses, (trusted, untrusted) ->
        val homes = accounts.map { it.account.host }.toSet()
        val keys = mints.associate { it.host to it.mintPubkey }
        val held = notes.filter { it.state != NoteState.Spent }
        (keys.keys + held.map { it.host }).distinct().map { host ->
            val key = keys[host]
            val default = key?.let { trustDefault(MintKey(host, it), homes) }
            val homeOf = accounts.filter { it.account.host == host }.map { it.account.name }
            MintInfo(
                host, key, trusted = key != null && isTrusted(MintKey(host, key), default, trusted, untrusted), trustedByDefault = default,
                homeOf = homeOf, removable = homeOf.isEmpty() && held.none { it.host == host } && addresses.none { it.host == host },
            )
        }.sortedWith(compareBy({ it.homeOf.isEmpty() }, { it.host }))
    }

    /**
     * The active account's mints: its home, then every mint where it has money or a Lightning address, by host; with its
     * money there. What the account can do there (change home, move money) starts from here.
     */
    val accountMints: Flow<List<AccountMint>> = combine(homeHost, notes, ofActive(emptyList(), store::addresses), trustedHosts()) { home, notes, addresses, trusted ->
        val live = notes.filter { it.state == NoteState.Outstanding }
        (listOfNotNull(home) + live.map { it.host } + addresses.map { it.host }).distinct().map { host ->
            AccountMint(
                host, isHome = host == home, balanceMsat = live.filter { it.host == host }.sumOf { it.amountMsat },
                noteCount = live.count { it.host == host }, addresses = addresses.count { it.host == host }, trusted = host in trusted,
            )
        }.sortedWith(compareBy({ !it.isHome }, { it.host }))
    }

    /**
     * Forgets the mint at [host]: its pinned signing key and the user's trust choice for it, so adding it again is a
     * first contact (as after a restore). Only if no account has its home, a note that isn't spent, an address or an
     * unsettled operation there ([RemoveMintResult.InUse] says which); notes and history are never touched.
     */
    suspend fun removeMint(host: String): RemoveMintResult = lock.withLock {
        val h = Onboarding.normalizeHost(host) ?: host
        val accounts = store.accounts.first()
        val use = when {
            accounts.any { it.account.host == h } -> MintUse.Home
            accounts.any { a -> store.notes(a.account.id).first().any { it.host == h && it.state != NoteState.Spent } } -> MintUse.Notes
            accounts.any { a -> store.addresses(a.account.id).first().any { it.host == h } } -> MintUse.Address
            store.journal(UNSETTLED).any { it.host == h } -> MintUse.OpenOperation
            else -> null
        }
        if (use != null) return@withLock RemoveMintResult.InUse(use)
        store.mint(h)?.mintPubkey?.let { trust.forget(MintKey(h, it)) }
        store.removeMint(h)
        RemoveMintResult.Removed
    }

    /** Hosts of trusted mints, for highlighting notes. */
    val trustedHosts: Flow<Set<String>> get() = trustedHosts()

    private fun trustedHosts(): Flow<Set<String>> = mints.map { list -> list.filter { it.trusted }.map { it.host }.toSet() }.distinctUntilChanged()

    /** Trusts or untrusts [host] with the key the wallet pinned for it. A mint without a pinned key can't be trusted. */
    suspend fun setMintTrusted(host: String, trusted: Boolean) {
        val key = checkNotNull(store.mint(host)?.mintPubkey) { "no signing key known for $host" }
        trust.setTrusted(MintKey(host, key), trusted)
    }

    /**
     * Keeps using [host] with the signing key [key] it now presents in place of the pinned one, once the user has seen
     * the change. Only that key: if the mint presents yet another one (or none), nothing changes and false is
     * returned. A mint the user trusted stays trusted with the new key, and the notes held there get the mint's
     * certificates under the new key (see [recertify]).
     */
    suspend fun acceptMintKey(host: String, key: String): Boolean = lock.withLock {
        val mint = store.mint(host) ?: return false
        val presented = try {
            client.withdrawDiscovery(host).mintPubkey
        } catch (e: Exception) {
            return false
        }
        if (presented == null || presented.toHex() != key) return false
        val old = mint.mintPubkey
        store.saveMint(mint.copy(mintPubkey = key))
        if (old != null && MintKey(host, old) in trust.trusted.first()) trust.setTrusted(MintKey(host, key), true)
        recertify(mint, presented)
        true
    }

    /**
     * Replaces the certificates of every account's notes on [mint], signed with its old key, by the mint's current
     * ones under [key]: a note shared later carries a certificate its recipient can verify. A note whose certificate
     * doesn't verify is left without one; one the mint doesn't answer for (spent, or no answer) keeps its old one.
     */
    private suspend fun recertify(mint: Mint, key: PublicKey) {
        val held = store.accounts.first().flatMap { store.notes(it.account.id).first() }
            .filter { it.host == mint.host && it.kind == NoteKind.KeyPath && it.state != NoteState.Spent }
        val updated = held.mapNotNull { note ->
            val q = ByteVector32(note.id)
            val info = runCatching { client.lookup(mint.withdrawUrl, q) }.getOrNull() ?: return@mapNotNull null
            val certificate = info.certificate?.takeIf { Certificates.verify(it, q, key) && it.amountMsat == info.maxWithdrawableMsat }
            note.copy(certificate = certificate?.encode(), updatedAtMillis = now())
        }
        if (updated.isNotEmpty()) store.upsertNotes(updated)
    }

    /** Connects to a mint the user names (pinning its key), so it can be trusted before any note from it arrives. */
    suspend fun addMint(host: String): ConnectResult = connectMint(host)

    /** Whether [mint] (this host with this key) is trusted. */
    internal suspend fun isTrusted(mint: MintKey): Boolean {
        val homes = store.accounts.first().map { it.account.host }.toSet()
        return isTrusted(mint, trustDefault(mint, homes), trust.trusted.first(), trust.untrusted.first())
    }

    /** The home mint is trusted by default with the key pinned for it; a brand's mint only with the key it ships. */
    private suspend fun trustDefault(mint: MintKey, homes: Set<String>): TrustDefault? = when {
        mint.host in homes && store.mint(mint.host)?.mintPubkey == mint.pubkey -> TrustDefault.Home
        mint in brandTrusted -> TrustDefault.Brand
        else -> null
    }

    /** Hands out the active account's note [id] whole, as a link (see [NoteSender.share]). */
    suspend fun shareNote(id: String): ShareNoteResult = lock.withLock { sender.share(id) }

    /** Asks the mint about the active account's outstanding note [id] and follows its answer (see [NoteCheck]). */
    suspend fun recheckNote(id: String): NoteCheck = lock.withLock { recheck(id) }

    /**
     * Refreshes (as on Home), then asks the mints about every outstanding note of the active account. A mint
     * that doesn't answer is skipped for the rest of the run.
     */
    suspend fun recheckNotes(): NotesCheck {
        refresh()
        return lock.withLock {
            var checked = 0
            var gone = 0
            val unreachable = mutableListOf<String>()
            val notes = store.notes(activeId()).first().filter(Note::isRecheckable)
            for (note in notes) {
                if (note.host in unreachable) continue
                when (recheck(note.id)) {
                    is NoteCheck.Ok -> checked++
                    NoteCheck.Spent, NoteCheck.Unknown -> { checked++; gone++ }
                    NoteCheck.Unreachable, is NoteCheck.KeyChanged -> unreachable += note.host
                    NoteCheck.NotFound -> Unit
                }
            }
            NotesCheck(checked, gone, unreachable)
        }
    }

    private suspend fun recheck(id: String): NoteCheck {
        val note = store.note(id)?.takeIf { it.accountId == activeId() && it.isRecheckable() } ?: return NoteCheck.NotFound
        if (store.mint(note.host) == null) return NoteCheck.Unreachable
        val probe = try {
            inspector.recheck(KeyId(note.accountId, note.host, note.purpose!!, note.index!!))
        } catch (e: MintKeyChangedException) {
            return NoteCheck.KeyChanged(note.host)
        }
        return when (probe) {
            is KeyProbe.Outstanding -> NoteCheck.Ok(probe.amountMsat, probe.certified)
            is KeyProbe.Spent -> NoteCheck.Spent
            is KeyProbe.Unused -> NoteCheck.Unknown
            is KeyProbe.Refused, is KeyProbe.Failed -> NoteCheck.Unreachable
        }
    }

    /** Renews the active account's note [id]: rotated onto a fresh wallet key, so any copy of the old one is worthless. */
    suspend fun rotateNote(id: String): RotateResult = lock.withLock {
        val note = store.note(id)?.takeIf { it.accountId == activeId() && it.isRecheckable() }
            ?: return@withLock RotateResult.NotRotatable("no outstanding note $id")
        rotator.rotate(note.accountId, note.host, note.purpose!!, note.index!!)
    }

    /** Checks whether sent note [id] has been redeemed (on top of the check every refresh does). */
    suspend fun checkSharedNote(id: String): SharedNote? = lock.withLock {
        sender.reconcile(activeId())
        sender.sharedNote(id)
    }

    /** Merges the active account's notes [ids] (on one mint) into one. */
    suspend fun combineNotes(ids: Collection<String>): NoteActionResult = lock.withLock { noteManager.combine(ids) }

    /** The amounts note [id] can be split into, or null if it is too small to split. */
    suspend fun splitLimits(id: String): SplitLimits? = noteManager.splitLimits(id)

    /** Splits note [id] into one worth [amountMsat] and the change (minus the mint's split fee). */
    suspend fun splitNote(id: String, amountMsat: Long): NoteActionResult = lock.withLock { noteManager.split(id, amountMsat) }

    /** What moving notes [ids] to the mint at [toHost] would send and deliver. */
    suspend fun quoteMintMove(ids: Collection<String>, toHost: String): MintMoveQuote = noteManager.quoteMove(ids, toHost)

    /** Moves notes [ids] to the mint at [toHost]: melted here, minted there to a key of this account. */
    suspend fun moveToMint(ids: Collection<String>, toHost: String): MintMoveResult = lock.withLock { noteManager.move(ids, toHost) }

    /** Settles unknown outcomes, then checks every account's address and scans for new notes. */
    suspend fun refresh(): RefreshResult = lock.withLock {
        reconciler.reconcileAll()
        val active = store.currentAccountId()
        var result = RefreshResult(0, 0)
        for (summary in store.accounts.first()) {
            val id = summary.account.id
            // Without an address there's nothing to check or scan; notes arrive by claim or invoice.
            val profile = summary.profile
            if (profile == null && requireAddress) continue
            if (profile != null) {
                addressCheck.check(profile.username, profile.host, id).let { status ->
                    if (status !is AddressStatus.CheckFailed) setAddressStatus(id, profile.address, status)
                }
                val scanned = scanner.scan(id, profile.host)
                if (id == active) result = scanned
            }
            // Addresses on other mints (same recovery phrase, that mint's own keys) are paid there.
            for (host in store.addresses(id).first().map { it.host }.distinct() - setOfNotNull(profile?.host)) {
                val scanned = scanner.scan(id, host)
                if (id == active) result = RefreshResult(result.newNotes + scanned.newNotes, result.receivedMsat + scanned.receivedMsat)
            }
            sender.reconcile(id)
            applyPolicies(id)
        }
        result
    }

    /**
     * An invoice for [amountMsat] that mints a note to the active account on its home mint once paid (see
     * [InvoiceReceiver]); the way to receive without a Lightning address. Follow it with [invoiceStatus].
     */
    suspend fun requestInvoice(amountMsat: Long): InvoiceRequest = lock.withLock { invoices.request(amountMsat) }

    /** Looks up whether invoice [id] was paid; a paid one's note is in the wallet afterwards. */
    suspend fun invoiceStatus(id: String): InvoiceStatus = lock.withLock {
        invoices.status(id).also { if (it is InvoiceStatus.Paid) applyPolicies(activeId()) }
    }

    suspend fun previewClaim(input: String): ClaimPreview = claimer.preview(input, activeId())

    suspend fun claim(preview: ClaimPreview.Ready): ClaimResult = lock.withLock {
        val id = activeId()
        claimer.claim(preview, id).also { applyPolicies(id) }
    }

    /** Moves [amountMsat] (or everything, if null) from the active account to [toAccountId]. */
    suspend fun moveFunds(toAccountId: String, amountMsat: Long?): MoveResult = lock.withLock {
        val id = activeId()
        mover.move(id, toAccountId, amountMsat).also {
            applyPolicies(id)
            applyPolicies(toAccountId)
        }
    }

    /**
     * Deletes the active account from this device: its seed, notes, journal and settings. Its funds stay at the
     * mint and can only be recovered with its recovery phrase. Another account becomes active if there is one.
     * Returns true if one did.
     */
    suspend fun deleteActiveAccount(): Boolean = lock.withLock { deleteActive(prefer = null) }

    /**
     * Undoes a restore whose address step isn't done (see [needsAddress]): the restored account is removed from this
     * device, and the account that was active before it (if still known) is active again. Nothing changes at the mint:
     * the money stays under the recovery phrase and can be restored again. Returns whether an account remains.
     */
    suspend fun abortRestore(): Boolean = lock.withLock {
        check(store.currentAccountId()?.let { store.currentProfile(it) == null } == true) { "no restore in progress" }
        deleteActive(prefer = activeBeforeRestore).also { activeBeforeRestore = null }
    }

    /** The account that was active when a restore started; kept in memory only (after a restart, any account is fine). */
    internal var activeBeforeRestore: String? = null

    private suspend fun deleteActive(prefer: String?): Boolean {
        val id = activeId()
        store.deleteAccount(id)
        seeds.clear(id)
        policies.clear(id)
        _lastPolicyRun.update { it - id }
        collectBlocks.update { blocks -> blocks.filterKeys { it.first != id } }
        branches.keys.removeAll { it.first == id }
        addressStatuses.update { statuses -> statuses.filterKeys { it.first != id } }
        failedProbes.update { probes -> probes.filterKeys { it.accountId != id } }
        val remaining = store.accounts.first().map { it.account.id }
        val next = prefer?.takeIf { it in remaining } ?: remaining.firstOrNull()
        store.setActiveAccount(next)
        return next != null
    }

    // ---- Developer tools over the active account's derived keys

    suspend fun keyHosts(): List<String> = inspector.hosts(activeId())

    suspend fun keyIndexes(host: String): KeyIndexes = inspector.indexes(activeId(), host)

    /** Keys 0 until index + [gap] of [purpose] on [host]. */
    suspend fun derivedKeys(host: String, purpose: Int, gap: Int = AddressScanner.GAP_LIMIT): List<DerivedKey> =
        activeId().let { id -> inspector.keys(id, host, purpose, gap, store.keyChecks(id).first(), failedProbes.value) }

    /** Looks up keys index until index + [gap], importing notes and moving the index past any used key found. */
    suspend fun scanKeysAhead(host: String, purpose: Int, gap: Int = AddressScanner.GAP_LIMIT): KeyScanResult =
        lock.withLock { inspector.scanAhead(activeId(), host, purpose, gap) }

    suspend fun recheckKey(host: String, purpose: Int, index: Long): KeyProbe =
        lock.withLock { inspector.recheck(KeyId(activeId(), host, purpose, index)) }

    /** Finds the right index for [purpose] on [host] (lookups past the highest used key, gap limit 20) and sets it. */
    suspend fun findKeyIndex(host: String, purpose: Int, gap: Int = AddressScanner.GAP_LIMIT): DiscoveryResult =
        lock.withLock { discovery.discover(activeId(), host, purpose, gap) }

    /** Sets an index by hand; refused below the highest key the wallet knows is used (+1). */
    suspend fun setKeyIndex(host: String, purpose: Int, index: Long): SetIndexResult = lock.withLock {
        val id = activeId()
        val floor = discovery.highestKnownUsed(id, host, purpose) + 1
        if (index < floor) return@withLock SetIndexResult.BelowFloor(floor)
        if (purpose == NotePurpose.LightningAddress.value) store.setAddressScanFrom(id, host, index) else store.setIndex(id, host, purpose, index)
        SetIndexResult.Applied(index)
    }

    /** Looks up keys 0 until index + [gap] of [purpose] on [host] and syncs the wallet with the answers. */
    suspend fun recheckAllKeys(host: String, purpose: Int, gap: Int = AddressScanner.GAP_LIMIT): KeyScanResult =
        lock.withLock { inspector.recheckAll(activeId(), host, purpose, gap) }

    suspend fun keyIndexHealth(host: String, purpose: Int): IndexHealth {
        val id = activeId()
        val next = keyIndexes(host).of(purpose)
        return IndexHealth(next, discovery.highestKnownUsed(id, host, purpose), indexVerifiedAt.value[Triple(id, host, purpose)])
    }

    internal fun markIndexVerified(accountId: String, host: String, purpose: Int) =
        indexVerifiedAt.update { it + (Triple(accountId, host, purpose) to now()) }

    /** The active account's indexes on [host], updated on every change (from any screen or background refresh). */
    fun keyIndexesFlow(host: String): Flow<KeyIndexes> = ofActive(KeyIndexes(0, 0, 0)) { id -> store.indexesFlow(id, host) }.distinctUntilChanged()

    /**
     * Emits whenever anything the developer key page shows for [host] may have changed: the active account's notes,
     * lookup results, indexes, policy runs or verification times. Lets that page stay in sync without polling.
     */
    fun keyViewChanges(host: String): Flow<Unit> = merge(
        notes.map { },
        keyProbes.map { },
        failedProbes.map { },
        keyIndexesFlow(host).map { },
        lastPolicyRun.map { },
        indexVerifiedAt.map { },
    )

    /** Rotates the active account's note at ([host], [purpose], [index]) onto the next wallet key (purpose 0). */
    suspend fun rotateKey(host: String, purpose: Int, index: Long): RotateResult =
        lock.withLock { rotator.rotate(activeId(), host, purpose, index) }

    /** Rotates every outstanding note of [purpose] on [host] onto new wallet keys, one at a time. */
    suspend fun rotateAllKeys(host: String, purpose: Int): RotateAllResult = lock.withLock { rotator.rotateAll(activeId(), host, purpose) }

    /** The wallet key (purpose 0) index the next rotate would use; a preview, not a reservation. */
    suspend fun nextWalletIndex(host: String): Long = store.currentIndex(activeId(), host, NotePurpose.Wallet.value)

    /** Saves a lookup's answer; a lookup without one is only remembered for this session. */
    internal suspend fun recordProbe(id: KeyId, probe: KeyProbe) {
        if (probe is KeyProbe.Failed) {
            failedProbes.update { it + (id to probe) }
        } else {
            store.putKeyCheck(id, probe)
            failedProbes.update { it - id }
        }
    }

    suspend fun unresolvedOperations(): Int = store.journal(UNSETTLED)
        // An invoice waiting to be paid isn't stuck (see [requestInvoice]).
        .count { !(it.type == OperationType.Mint && it.detail == null) }

    /** Applies [accountId]'s note policies; must be called under the lock. */
    private suspend fun applyPolicies(accountId: String) {
        val collect = collectDefaults.takeIf { it.active(policies.collect(accountId).first()) }?.let {
            store.account(accountId)?.host?.let { home -> CollectPolicy(home, collectFeeCap(accountId).first()) }
        }
        val run = policyEngine.apply(accountId, strategies.resolve(policies.strategy(accountId).first()), policies.autoRotate(accountId).first(), collect)
        if (run.didSomething) _lastPolicyRun.update { it + (accountId to run) }
    }

    /** [ofAccount] of whichever account is active, following switches; [none] while there is no account. */
    private fun <T> ofActive(none: T, ofAccount: (String) -> Flow<T>): Flow<T> =
        store.activeAccountId.flatMapLatest { id -> id?.let(ofAccount) ?: flowOf(none) }

    /** [ofAccount] of every account, joined, following accounts being added and removed. */
    private fun <T> ofEveryAccount(ofAccount: (String) -> Flow<List<T>>): Flow<List<T>> = store.accounts.flatMapLatest { accounts ->
        if (accounts.isEmpty()) flowOf(emptyList()) else combine(accounts.map { ofAccount(it.account.id) }) { lists -> lists.flatMap { it.toList() } }
    }

    internal suspend fun activeId(): String = checkNotNull(store.currentAccountId()) { "no active account" }

    /**
     * The account's branch on [host], kept for the life of the process. The seed it comes from is not: it can derive
     * the keys of every mint, so it is made again for each new branch and zeroed straight after.
     */
    internal suspend fun branch(accountId: String, host: String): CashBranch {
        branches[accountId to host]?.let { return it }
        val words = checkNotNull(seeds.load(accountId)) { "account has no seed" }
        val seed = RecoveryPhrase.seed(words)
        return try { branches.getOrPut(accountId to host) { CashBranch.fromSeed(seed, host) } } finally { seed.fill(0) }
    }

    /** The branch, or null if the account has no seed (so nothing can be ours). */
    internal suspend fun branchIfSeeded(accountId: String?, host: String): CashBranch? =
        if (accountId == null || (branches[accountId to host] == null && seeds.load(accountId) == null)) null else branch(accountId, host)

    internal fun setAddressStatus(accountId: String, address: String, status: AddressStatus?) = addressStatuses.update {
        if (status == null) it - (accountId to address) else it + ((accountId to address) to status)
    }

    /**
     * The recovery phrase the next new account gets, made ahead so onboarding can suggest its seed-derived name
     * ([DefaultAddress]). Held in memory only until an account is created with it or [forgetPreparedPhrase] is called.
     * Its entries are the wordlist's own strings, so emptying the list leaves nothing of the phrase behind.
     */
    @Volatile
    private var prepared: PreparedPhrase? = null

    /**
     * A phrase made ahead and the names already derived from it, by host: deriving one runs PBKDF2 and BIP-32, which
     * leave copies behind that can't be zeroed, so each mint's name is derived once. The names are public.
     */
    private class PreparedPhrase(val words: MutableList<String>) {
        val names = ConcurrentHashMap<String, String>()
    }

    private fun prepared(): PreparedPhrase = prepared ?: run {
        val entropy = ByteArray(16).also(random::nextBytes)
        try { PreparedPhrase(MnemonicCode.toMnemonics(entropy).toMutableList()).also { prepared = it } } finally { entropy.fill(0) }
    }

    /**
     * Drops the phrase made ahead for the next new account. Call it when onboarding is left: a later one gets a new
     * phrase (and so other suggested names).
     */
    fun forgetPreparedPhrase() {
        prepared?.words?.clear()
        prepared = null
    }

    /** Stores the prepared recovery phrase (or a fresh one) for [accountId]. */
    internal suspend fun createSeed(accountId: String) {
        // A copy: the store may keep the list it is given, and the prepared one is emptied next.
        seeds.save(accountId, prepared().words.toList())
        forgetPreparedPhrase()
    }

    /** Drops [accountId]'s phrase; with [keepForRetry] it becomes the prepared one again, so its names stay the same. */
    internal suspend fun discardSeed(accountId: String, keepForRetry: Boolean = false) {
        if (keepForRetry) seeds.load(accountId)?.let { prepared = PreparedPhrase(it.toMutableList()) }
        seeds.clear(accountId)
        branches.keys.removeAll { it.first == accountId }
    }

    private companion object {
        const val ALREADY_HOME = "this is already the home mint"

        /** Journal entries whose outcome isn't settled yet. */
        val UNSETTLED = setOf(JournalStatus.Prepared, JournalStatus.Unknown)
    }
}

data class RefreshResult(val newNotes: Int, val receivedMsat: Long)

/** What the mint said about one note when asked again ([Wallet.recheckNote]). */
sealed interface NoteCheck {
    /** Still there and still ours; [amountMsat] as the mint reports it. */
    data class Ok(val amountMsat: Long, val certified: Boolean) : NoteCheck

    /** Spent at the mint (e.g. by this recovery phrase on another phone); now out of the balance. */
    data object Spent : NoteCheck

    /** The mint has no note at this key. */
    data object Unknown : NoteCheck

    /** No usable answer; nothing changed. */
    data object Unreachable : NoteCheck

    /** The mint signs with a different key than before; operations on it are stopped. */
    data class KeyChanged(val host: String) : NoteCheck

    /** Not an outstanding note of the active account with a derived key. */
    data object NotFound : NoteCheck
}

/** What [Wallet.recheckNotes] found: notes still there, notes gone, and mints that didn't answer. */
data class NotesCheck(val checked: Int, val gone: Int, val unreachable: List<String>)

/** Notes a lookup can confirm and a rotate can renew: outstanding, at a key derived from the seed. */
internal fun Note.isRecheckable(): Boolean = state == NoteState.Outstanding && kind == NoteKind.KeyPath && purpose != null && index != null
