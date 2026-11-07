package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PublicKey
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import whitewallet.brand.NoteStrategy
import whitewallet.brand.WalletTask
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.Profile
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.isShared
import whitewallet.core.wallet.legacy.Legacy
import whitewallet.core.wallet.legacy.LegacyKeys
import whitewallet.lnurlcash.ServiceRoot
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.spendDomain

/**
 * The wallet's entry point. It holds several accounts, each with its own recovery phrase; everything except account management acts on the
 * *active* account. Operations that touch notes run under one lock, so counters, the journal and the note table are never written
 * concurrently, and the active account cannot change mid-operation.
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
    /** How many accounts one recovery phrase may hold (brand `accounts.max`); [AppDefaults.UNLIMITED] for no limit. */
    val maxAccounts: Int = AppDefaults.UNLIMITED,
    /** How many recovery phrases the app may hold (brand `wallets.max`); [AppDefaults.UNLIMITED] for no limit. */
    val maxWallets: Int = AppDefaults.UNLIMITED,
    /**
     * Whether every account needs a Lightning address (brand `addresses.lightning=required`). Without, an account is set up with just its
     * mint ([createAccount]) and receives by invoice ([requestInvoice]) until it registers one.
     */
    val requireAddress: Boolean = true,
    /** The most an address fee may be (brand `addresses.fee.max`), or null for whatever the mint advertises. */
    internal val maxAddressFeeMsat: Long? = null,
    /** The user's trust choices for mints (see [mints]). */
    private val trust: MintTrustStore = MintTrustStore.InMemory(),
    /** Mints the brand ships as trusted, each with its key (brand `mints.trusted`). */
    private val brandTrusted: List<MintKey> = emptyList(),
    /**
     * Asks for a refresh as soon as the device is online again: a received note is kept waiting for its mint (the app schedules a sync that
     * needs a network).
     */
    private val onWaitingForNetwork: () -> Unit = {},
    /** How long [pay] waits for a melt's payment to settle before reporting it as pending (brand `sync.paymentWait`). */
    payWaitMillis: Long = 30_000L,
    /** How long a received note its mint refuses is tried again before it counts as refused (brand `notes.received.giveUpAfter`). */
    internal val receivedGiveUpMillis: Long = 7 * 24 * 60 * 60_000L,
    /** How the wallet behaves: wallet.properties, not the brand. */
    internal val settings: WalletSettings = WalletSettings(),
) {
    private val lock = Mutex()

    /** See the constructor; tests shorten it. */
    internal var payWaitMillis = payWaitMillis
    private val keySets = ConcurrentHashMap<Pair<String, String>, AccountKeys>()
    private val dialects = ConcurrentHashMap<String, MintDialect>()
    /** The last check of each address, by (account id, address). In memory; see [refresh] and [checkAddresses]. */
    private val addressStatuses = MutableStateFlow<Map<Pair<String, String>, AddressStatus>>(emptyMap())

    private val onboarding = Onboarding(this)
    private val scanner = AddressScanner(this)
    private val claimer = NoteClaimer(this)
    private val received = ReceivedNotes(this)
    private val reconciler = Reconciler(this)
    private val addressCheck = AddressCheck(this)
    private val mover = FundsMover(this)
    private val inspector = KeyInspector(this)
    private val rotator = NoteRotator(this)
    internal val legacy = Legacy(this, scanner, addressCheck, rotator)
    private val discovery = IndexDiscovery(this)
    private val indexVerifiedAt = MutableStateFlow<Map<Triple<String, String, KeySet>, Long>>(emptyMap())
    private val policyEngine = NotePolicyEngine(this)
    private val restorer = Restore(this)
    private val payer = Payer(this)
    private val sender = NoteSender(this)
    private val noteManager = NoteManager(this)
    private val moneyMove = MoneyMove(this)
    private val invoices = InvoiceReceiver(this)
    private val mintInspector = MintInspector(this)
    private val _lastPolicyRun = MutableStateFlow<Map<String, PolicyRun>>(emptyMap())
    private val unknownNotes = UnknownNotes(this)

    /** When each unknown note was last asked about, since the app started (see [UnknownNotes.recheckDue]). */
    internal val unknownCheckedAt = mutableMapOf<String, Long>()

    /**
     * Lookups that got no answer this session. Not saved: they say nothing about the key, so the last real answer stays in the store and
     * shows again after a restart.
     */
    private val failedProbes = MutableStateFlow<Map<KeyId, KeyProbe.Failed>>(emptyMap())

    /**
     * The mint's last answer for each of the active account's looked-up keys (scans, rechecks, rotates), for the developer key page. Saved,
     * so it survives restarts.
     */
    val keyProbes: Flow<Map<KeyId, KeyProbe>> = ofActive(emptyMap(), store::keyChecks)

    val accounts: Flow<List<AccountSummary>>
        get() = store.accounts

    val activeAccount: Flow<AccountSummary?> =
        combine(store.accounts, store.activeAccountId) { list, id -> list.firstOrNull { it.account.id == id } }

    val profile: Flow<Profile?> = ofActive(null, store::profile)

    /** The active account's home mint; null before there is an account. */
    val homeHost: Flow<String?> = activeAccount.map { it?.account?.host }.distinctUntilChanged()
    val notes: Flow<List<Note>> = ofActive(emptyList(), store::notes)
    val balanceMsat: Flow<Long> = notes.map { notes -> notes.filter { it.state == NoteState.Outstanding }.sumOf { it.amountMsat } }

    /** The active account's received notes (see [ReceivedNote]), newest first: kept, claimed, or no longer claimable. */
    val receivedNotes: Flow<List<ReceivedNote>> = ofActive(emptyList()) { id -> store.receivedNotes(id) }

    /**
     * What the active account's received notes not claimed yet are worth: not in [balanceMsat] until claimed. A note whose mint hasn't said
     * yet counts what its link says.
     */
    val incomingMsat: Flow<Long> = receivedNotes.map { notes ->
        notes.filter { it.isPending }.sumOf { it.amountMsat ?: it.declaredMsat ?: 0 }
    }
    val backupVerified: Flow<Boolean> = ofActive(false) { id -> flow { emitAll(seeds.backupVerified(walletOf(id))) } }

    /** The last known status of the active account's primary address; null until checked. Refreshed by [refresh]. */
    val addressStatus: Flow<AddressStatus?> =
        combine(store.activeAccountId, profile, addressStatuses) { id, primary, statuses ->
            if (id == null || primary == null) null else statuses[id to primary.address]
        }

    /** Every Lightning address of the active account, oldest first, with its last check. */
    val addresses: Flow<List<AddressEntry>> =
        ofActive(emptyList()) { id ->
            combine(store.addresses(id), store.profile(id), addressStatuses) { list, primary, statuses ->
                list.map { AddressEntry(it, primary?.username == it.username && primary.host == it.host, statuses[id to it.address]) }
            }
        }

    /** The active account's auto-rotate setting: whether notes on Lightning address keys (purpose 2) move to wallet keys. */
    val autoRotate: Flow<Boolean> = ofActive(false, policies::autoRotate)

    /** The active account's history, newest first (see [Activity]). */
    val activity: Flow<List<Activity>> = ofActive(emptyList(), store::activities)

    /** The accounts of the active wallet (one recovery phrase), the active one included. */
    val activeWalletAccounts: Flow<List<AccountSummary>> =
        combine(store.accounts, activeAccount) { all, active -> all.filter { it.account.walletId == active?.account?.walletId } }

    /** Whether the active wallet may hold another account ([maxAccounts]). */
    val canAddAccount: Flow<Boolean> = activeWalletAccounts.map { it.isEmpty() || it.size < maxAccounts }

    /** Whether another recovery phrase may be created or restored ([maxWallets]). */
    val canAddWallet: Flow<Boolean> = store.accounts.map { all -> all.map { it.account.walletId }.distinct().size < maxWallets }

    /** The active account's note strategy: its choice, or the brand's default. */
    val noteStrategy: Flow<NoteStrategy> = ofActive(strategies.default) { id -> policies.strategy(id).map(strategies::resolve) }

    /** The note strategies a user can choose from, in the brand's order. */
    val noteStrategies: List<NoteStrategy>
        get() = strategies.offered

    /** Whether the active account moves notes from other mints to its home mint (see [CollectDefaults.active]). */
    val collectAtHome: Flow<Boolean> = ofActive(false) { id -> policies.collect(id).map(collectDefaults::active) }

    /** The active account's fee cap for those moves, in percent. */
    val collectFeeCap: Flow<Int> = ofActive(collectDefaults.feeCapPercent, ::collectFeeCap)

    /** [accountId]'s fee cap for collecting, in percent: its own setting, or the brand's default. */
    private fun collectFeeCap(accountId: String): Flow<Int> = policies.collectFeeCap(accountId).map { it ?: collectDefaults.feeCapPercent }

    /**
     * Whether the active account's user wants to be told what the note policies did by themselves; null until they choose (then
     * [MaintenanceNoticeDefaults.active] decides).
     */
    val maintenanceNoticesChoice: Flow<Boolean?> = ofActive(null, policies::maintenanceNotices)

    suspend fun setMaintenanceNotices(enabled: Boolean) = policies.setMaintenanceNotices(activeId(), enabled)

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

    internal fun blockCollect(accountId: String, host: String, untilMillis: Long) = collectBlocks.update {
        it + ((accountId to host) to untilMillis)
    }

    private val _task = MutableStateFlow<WalletTask?>(null)

    /**
     * What the wallet works on right now that changes what the user sees (see [WalletTask]); null while it works on nothing that takes a
     * mint's answer. Set by [refresh], [recheckNotes] and the note policies.
     */
    val task: StateFlow<WalletTask?> = _task.asStateFlow()

    /**
     * Runs [block] as [task], then goes back to what the wallet worked on before (a refresh around maintenance). Only under the lock, so
     * tasks nest and never interleave.
     */
    internal suspend fun <T> working(task: WalletTask, block: suspend () -> T): T {
        val before = _task.value
        _task.value = task
        try {
            return block()
        } finally {
            _task.value = before
        }
    }

    /** The last time the active account's note policies changed something, if they have this session. */
    val lastPolicyRun: Flow<PolicyRun?> = combine(store.activeAccountId, _lastPolicyRun) { id, runs -> runs[id] }

    /** Saves the active account's auto-rotate setting (address-key notes only) and applies it right away. */
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
     * The active account exists but has no address yet although this app requires one: it was restored and the address step wasn't
     * finished.
     */
    suspend fun needsAddress(): Boolean = requireAddress && store.currentAccountId()?.let { store.currentProfile(it) == null } ?: false

    /**
     * Creates an account on the mint at [host] without a Lightning address, with a fresh recovery phrase, and makes it active. Only where
     * addresses aren't required ([requireAddress]); otherwise accounts start with [onboard]. Without [host], a wallet without a mint: it
     * can't receive until one is added ([addMint]).
     */
    suspend fun createAccount(host: String?, name: String, inActiveWallet: Boolean = false): CreateAccountResult = lock.withLock {
        check(!requireAddress) { "this app requires an address; use onboard" }
        onboarding.createAccount(host, name, inActiveWallet)
    }

    /**
     * Recovers an account from its recovery phrase on [host] and makes it active (see [Restore]). The account has no address afterwards;
     * give it one with [onboard] (no new account name), which accepts an address it already owns. Without [host], only the phrase comes in:
     * adding a mint later finds what it holds there.
     */
    suspend fun restore(phrase: String, host: String?, accountName: String): RestoreResult = lock.withLock {
        restorer.restore(phrase, host, accountName)
    }

    /** The active account's BIP-39 recovery phrase. Only for the backup screen; never log or persist it elsewhere. */
    suspend fun recoveryPhrase(): List<String> = checkNotNull(seeds.load(walletOf(activeId()))) { "account has no seed" }

    suspend fun markBackupVerified() = seeds.setBackupVerified(walletOf(activeId()))

    /**
     * Claims [username] on [host] and confirms the claim. With [newAccountName], creates a new account with its own recovery phrase (kept
     * only if the claim succeeds) and makes it active; without, changes the active account's address. The very first call always creates an
     * account.
     */
    suspend fun onboard(username: String, host: String, newAccountName: String? = null, inActiveWallet: Boolean = false): OnboardResult =
        lock.withLock {
            onboarding.onboard(username, host, newAccountName, inActiveWallet)
        }

    /** Validates and stores a mint (and pins its key) without registering a username. */
    suspend fun connectMint(host: String): ConnectResult = lock.withLock {
        val id = store.currentAccountId() ?: return@withLock ConnectResult.Failed(OnboardResult.MintInvalid("no account"))
        onboarding.connectMint(walletOf(id), host)
    }

    /**
     * Checks whether [username]@[host] is free, taken, or already the active account's. Pass [forNewAccount] when choosing a name for an
     * account that does not exist yet, so nothing counts as ours. Read-only.
     */
    suspend fun checkAddress(username: String, host: String, forNewAccount: Boolean = false): AddressStatus =
        addressCheck.check(username, host, if (forNewAccount) null else store.currentAccountId())

    /**
     * Registers another address for the active account: on its home mint, or on [host] (same recovery phrase, that mint's own keys; never
     * the primary address). Refused with [OnboardResult.LimitReached] once the account holds [limit] addresses there.
     */
    suspend fun addAddress(username: String, limit: Int, host: String? = null): OnboardResult = lock.withLock {
        onboarding.addAddress(username, limit, host)
    }

    /** The fee [host] asks to register an address (LUD-26), or null for none. Throws IOException when the mint can't be reached. */
    suspend fun addressFeeMsat(host: String): Long? = client.servicePayRequest(host).fee?.addressMsat?.takeIf { it > 0 }

    /** Pays held [address]'s fee from the account's notes on its mint, which registers it there and then. */
    suspend fun payAddressFee(address: RegisteredAddress): PayResult? {
        val preview = previewPayment(address.feeInvoice ?: return null) as? PayPreview.Ready ?: return null
        val paid = pay(preview, preview.fixedAmountMsat ?: return null)
        lock.withLock { onboarding.settleHeld(activeId(), address) }
        return paid
    }

    /** Asks the mint about held [address] of the active account: the address as it is now, or null once its hold is over. */
    suspend fun checkHeldAddress(address: RegisteredAddress): RegisteredAddress? = lock.withLock {
        val id = activeId()
        onboarding.settleHeld(id, address)
        store.addresses(id).first().firstOrNull { it.username == address.username && it.host == address.host }
    }

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
        check(store.addresses(id).first().any { it.username == address.username && it.host == address.host }) {
            "not an address of this account"
        }
        check(address.host == store.account(id)?.host) { "the primary address is on the home mint; use changeHome" }
        store.saveProfile(id, Profile(address.username, address.host))
    }

    /**
     * Moves the active account's home to the mint at [host], with [username] there as its primary address: claimed like [onboard] does, so
     * a name the account already holds there is taken as it is. From then on invoices are minted there. Money stays where it is: move it
     * with [moveMoney] (an account that collects at its home mint also has it moved by its next refresh). The old primary address stays
     * registered, and the old home stays trusted if it was.
     */
    suspend fun changeHome(host: String, username: String): OnboardResult = changeHome(host, username, emptyList()).result

    /** [changeHome] for an account without a Lightning address ([requireAddress] `false`): only the mint changes. */
    suspend fun changeHome(host: String): ConnectResult = changeHome(host, emptyList()).result

    /**
     * [changeHome], then [moveMoney] of [lines] to the new home, with nothing in between: a refresh slipping in would collect the money
     * itself (when the account collects at its home mint) and leave the move nothing to do. Nothing moves unless the change succeeds.
     */
    suspend fun changeHome(host: String, username: String, lines: List<MovePreview.Line>): HomeChanged<OnboardResult> = lock.withLock {
        val (id, keepTrusted) = leavingHome(host) ?: return@withLock HomeChanged(OnboardResult.MintInvalid(ALREADY_HOME))
        val result = onboarding.onboard(username, host, newAccountName = null)
        if (result !is OnboardResult.Ready) return@withLock HomeChanged(result)
        keepTrusted?.let { trust.setTrusted(walletOf(id), it, true) }
        HomeChanged(result, moneyMove.move(id, result.profile.host, lines))
    }

    /** [changeHome] with [lines] for an account without a Lightning address. */
    suspend fun changeHome(host: String, lines: List<MovePreview.Line>): HomeChanged<ConnectResult> = lock.withLock {
        val (id, keepTrusted) =
            leavingHome(host) ?: return@withLock HomeChanged(ConnectResult.Failed(OnboardResult.MintInvalid(ALREADY_HOME)))
        check(store.currentProfile(id) == null) { "the account has an address; pass the name to use on the new home" }
        val result = onboarding.connectMint(walletOf(id), host)
        if (result !is ConnectResult.Connected) return@withLock HomeChanged(result)
        store.saveAccount(checkNotNull(store.account(id)).copy(host = result.mint.host))
        keepTrusted?.let { trust.setTrusted(walletOf(id), it, true) }
        HomeChanged(result, moneyMove.move(id, result.mint.host, lines))
    }

    /**
     * What moving the active account's money to [toHost] would do: per mint holding it (only [fromHost], if given), what arrives and the
     * fee, or why it can't move. Null if [toHost] is not a valid host. Connects to [toHost] (pinning its key) to ask for the quotes;
     * nothing is paid.
     */
    suspend fun previewMove(toHost: String, fromHost: String? = null): MovePreview? {
        val id = activeId()
        val h = Onboarding.normalizeHost(toHost) ?: return null
        return moneyMove.preview(id, h, fromHost, collectFeeCap(id).first())
    }

    /**
     * Moves the money of [lines] (from [previewMove], as the user confirmed them) to [toHost]: per mint, its notes combined into one and
     * paid over Lightning, only at the confirmed fee or less. Says per mint what happened.
     */
    suspend fun moveMoney(toHost: String, lines: List<MovePreview.Line>): List<MoneyMoved> = lock.withLock {
        val h = requireNotNull(Onboarding.normalizeHost(toHost)) { "not a valid host" }
        moneyMove.move(activeId(), h, lines)
    }

    /**
     * The active account and its home mint's trusted key, about to be left for [host]; null if [host] already is the home. Its trust is
     * read now: once the account has moved, the home default no longer covers it.
     */
    private suspend fun leavingHome(host: String): Pair<String, MintKey?>? {
        val id = activeId()
        val home = checkNotNull(store.account(id)) { "no active account" }.host ?: return id to null
        if (Onboarding.normalizeHost(host) == home) return null
        val walletId = walletOf(id)
        return id to store.mint(walletId, home)?.mintPubkey?.let { MintKey(home, it) }?.takeIf { isTrusted(walletId, it) }
    }

    /** Frees one of the active account's addresses at its mint. Never the primary one. */
    suspend fun unregisterAddress(address: RegisteredAddress): UnregisterResult = lock.withLock {
        onboarding.unregister(activeId(), address)
    }

    suspend fun switchAccount(accountId: String) = lock.withLock {
        checkNotNull(store.account(accountId)) { "unknown account" }
        store.setActiveAccount(accountId)
    }

    /** Reads what the user entered or scanned: an invoice, a Lightning address or a pay link (see [Payer]). */
    suspend fun previewPayment(input: String): PayPreview = payer.preview(input)

    /** Whether [amountMsat] can be paid to [preview]'s destination right now, and the fee. */
    suspend fun quotePayment(preview: PayPreview.Ready, amountMsat: Long): PayQuote = payer.quote(preview, amountMsat)

    /** Pays [amountMsat] to [preview]'s destination from the active account. [comment] goes to pay links that accept one. */
    suspend fun pay(preview: PayPreview.Ready, amountMsat: Long, comment: String? = null): PayResult = lock.withLock {
        payer.pay(preview, amountMsat, comment)
    }

    /**
     * The name a new account on [host] suggests first: derived from the recovery phrase it is about to get, so a restore finds the address
     * again. Null for an invalid host.
     */
    suspend fun defaultUsername(host: String): String? {
        val h = Onboarding.normalizeHost(host) ?: return null
        val prepared = prepared()
        prepared.names[h]?.let {
            return it
        }
        val words = prepared.words.toList()
        return withContext(Dispatchers.Default) {
                val seed = RecoveryPhrase.seed(words)
                try {
                    DefaultAddress.names(ServiceRoot.fromSeed(seed, h).account(0).share(0).cx1()).first()
                } finally {
                    seed.fill(0)
                }
            }
            .also { prepared.names[h] = it }
    }

    /**
     * After a restore: the seed-derived address on [host] that is still registered to the active account, if any. Null if none is (it was
     * never taken, or was unregistered) or the mint can't be reached.
     */
    suspend fun recoverableUsername(host: String): String? {
        val accountId = activeId()
        val h = Onboarding.normalizeHost(host) ?: return null
        val keys = keysIfSeeded(accountId, h) ?: return null
        return DefaultAddress.names(keys).firstOrNull { addressCheck.check(it, h, accountId) == AddressStatus.Ours }
            ?: legacy.recoverableUsername(accountId, h)
    }

    /** The active account's addresses a restore can't find by itself (not seed-derived): to be written down. */
    suspend fun addressesToWriteDown(): List<String> {
        val accountId = activeId()
        return store
            .addresses(accountId)
            .first()
            .filter { address ->
                val keys = keysIfSeeded(accountId, address.host) ?: return@filter true
                address.username !in DefaultAddress.names(keys) + legacy.names(accountId, address.host)
            }
            .map { it.address }
    }

    /** A mint as the details page shows it, checked live (this also refreshes its stored fees and limits). */
    suspend fun mintDetails(host: String): MintDetails = mintInspector.details(host)

    /**
     * The watch-only key (`cx1`) of the active account's [address]: it lets someone see the notes paid to that address, never spend them.
     * Null if there is no recovery phrase on this device.
     */
    suspend fun watchOnlyKey(address: RegisteredAddress): String? {
        val accountId = activeId()
        return address.share?.let { keysIfSeeded(accountId, address.host)?.share(it)?.cx1() }
            ?: legacy.watchOnlyKey(accountId, address.host)
    }

    /** The most one note can be sent as, from the active account's notes. */
    suspend fun sendableNoteMsat(): Long = sender.availableMsat()

    /** What sending [amountMsat] as a note would cost, or why it can't be made. */
    suspend fun quoteNote(amountMsat: Long): PayQuote = sender.quote(amountMsat)

    /** Makes a note worth exactly [amountMsat] and hands it out as a link (out of the balance until taken back). */
    suspend fun createNote(amountMsat: Long): CreateNoteResult = lock.withLock { sender.create(amountMsat) }

    /** A sent note of the active account, with its link while nobody has claimed it. */
    suspend fun sharedNote(id: String): SharedNote? = sender.sharedNote(id)

    /** Takes back a sent note nobody has claimed yet. */
    suspend fun reclaimNote(id: String): ReclaimResult = lock.withLock {
        val accountId = activeId()
        sender.reclaim(id).also { applyPolicies(accountId) }
    }

    /** The active wallet's notes, every account's (spent ones too), for what concerns the whole wallet. */
    private val walletNotes: Flow<List<Note>> = ofActiveWalletAccounts(store::notes)

    /** The active wallet's Lightning addresses, every account's. */
    private val walletAddresses: Flow<List<RegisteredAddress>> = ofActiveWalletAccounts(store::addresses)

    /** The active wallet's trust choices: trusted and untrusted mints. */
    private val walletTrust: Flow<Pair<Set<MintKey>, Set<MintKey>>> =
        ofActiveWallet(emptySet<MintKey>() to emptySet()) { combine(trust.trusted(it), trust.untrusted(it), ::Pair) }

    /**
     * Every mint the active wallet has used (connected to, or holding any of its accounts' notes), until removed ([removeMint]): its key,
     * whether it's trusted and why, whose home it is, and whether it can be removed. Homes first, then by host. Trust follows [isTrusted].
     * Nothing about one account's money: see [accountMints]. Another wallet's mints, keys and trust are its own.
     */
    val mints: Flow<List<MintInfo>> =
        combine(ofActiveWallet(emptyList(), store::mints), activeWalletAccounts, walletNotes, walletAddresses, walletTrust) {
            mints,
            accounts,
            notes,
            addresses,
            (trusted, untrusted) ->
            val homes = accounts.mapNotNull { it.account.host }.toSet()
            val keys = mints.associate { it.host to it.mintPubkey }
            val held = notes.filter { it.state != NoteState.Spent }
            (keys.keys + held.map { it.host })
                .distinct()
                .map { host ->
                    val key = keys[host]
                    val default = key?.let { trustDefault(MintKey(host, it), pinned = it, homes) }
                    val homeOf = accounts.filter { it.account.host == host }.map { it.account.name }
                    MintInfo(
                        host,
                        pubkey = key,
                        trusted = key != null && isTrusted(MintKey(host, key), default, trusted, untrusted),
                        trustedByDefault = default,
                        homeOf = homeOf,
                        removable = held.none { it.host == host } && addresses.none { it.host == host },
                    )
                }
                .sortedWith(compareBy({ it.homeOf.isEmpty() }, { it.host }))
        }

    /**
     * The active account's mints: its home, then every mint where it has money (or notes it doesn't know) or a Lightning address, by host;
     * with its money there. What the account can do there (change home, move money) starts from here.
     */
    val accountMints: Flow<List<AccountMint>> =
        combine(homeHost, notes, ofActive(emptyList(), store::addresses), trustedHosts()) { home, notes, addresses, trusted ->
            val live = notes.filter { it.state == NoteState.Outstanding }
            val unknown = notes.filter { it.state == NoteState.Unknown }
            (listOfNotNull(home) + live.map { it.host } + unknown.map { it.host } + addresses.map { it.host })
                .distinct()
                .map { host ->
                    AccountMint(
                        host,
                        isHome = host == home,
                        balanceMsat = live.filter { it.host == host }.sumOf { it.amountMsat },
                        noteCount = live.count { it.host == host },
                        addresses = addresses.count { it.host == host },
                        trusted = host in trusted,
                        unknownCount = unknown.count { it.host == host },
                        unknownMsat = unknown.filter { it.host == host }.sumOf { it.amountMsat },
                    )
                }
                .sortedWith(compareBy({ !it.isHome }, { it.host }))
        }

    /**
     * Forgets the active wallet's mint at [host]: its pinned signing key and the user's trust choice for it, so adding it again is a first
     * contact (as after a restore). Only if no account of the wallet has a note that isn't spent, an address or an unsettled operation
     * there ([RemoveMintResult.InUse] says which); notes and history are never touched. An account whose home it was has none afterwards,
     * until the user picks one. Another wallet's mint stays.
     */
    suspend fun removeMint(host: String): RemoveMintResult = lock.withLock {
        val h = Onboarding.normalizeHost(host) ?: host
        val id = activeId()
        val walletId = walletOf(id)
        val accounts = siblings(id)
        val ids = accounts.map { it.id }.toSet()
        val use =
            when {
                accounts.any { a -> store.notes(a.id).first().any { it.host == h && it.state != NoteState.Spent } } -> MintUse.Notes
                accounts.any { a -> store.addresses(a.id).first().any { it.host == h } } -> MintUse.Address
                store.journal(UNSETTLED).any { it.host == h && it.accountId in ids } -> MintUse.OpenOperation
                else -> null
            }
        if (use != null) return@withLock RemoveMintResult.InUse(use)
        store.mint(walletId, h)?.mintPubkey?.let { trust.forget(walletId, MintKey(h, it)) }
        store.removeMint(walletId, h)
        accounts.filter { it.host == h }.forEach { store.saveAccount(it.copy(host = null)) }
        RemoveMintResult.Removed
    }

    /** Whether the active wallet has a mint at all; without one, it can't receive until one is added ([addMint]). */
    val hasMint: Flow<Boolean> = ofActiveWallet(true) { id -> store.mints(id).map { it.isNotEmpty() } }.distinctUntilChanged()

    /** Hosts of the active wallet's trusted mints, for highlighting notes. */
    val trustedHosts: Flow<Set<String>>
        get() = trustedHosts()

    private fun trustedHosts(): Flow<Set<String>> =
        mints.map { list -> list.filter { it.trusted }.map { it.host }.toSet() }.distinctUntilChanged()

    /** Trusts or untrusts [host] in the active wallet, with the key it pinned for it. A mint without a pinned key can't be trusted. */
    suspend fun setMintTrusted(host: String, trusted: Boolean) {
        val walletId = walletOf(activeId())
        val key = checkNotNull(store.mint(walletId, host)?.mintPubkey) { "no signing key known for $host" }
        trust.setTrusted(walletId, MintKey(host, key), trusted)
    }

    /**
     * Keeps using [host] in the active wallet with the signing key [key] it now presents in place of the pinned one, once the user has seen
     * the change. Only that key: if the mint presents yet another one (or none), nothing changes and false is returned. A mint the user
     * trusted stays trusted with the new key, and the wallet's notes held there get the mint's certificates under the new key (see
     * [recertify]). Another wallet keeps the old key until it accepts the new one itself.
     */
    suspend fun acceptMintKey(host: String, key: String): Boolean = lock.withLock {
        val walletId = walletOf(activeId())
        val mint = store.mint(walletId, host) ?: return false
        val presented =
            try {
                client.withdrawDiscovery(host).mintPubkey
            } catch (e: Exception) {
                return false
            }
        if (presented == null || presented.toHex() != key) return false
        val old = mint.mintPubkey
        store.saveMint(mint.copy(mintPubkey = key))
        if (old != null && MintKey(host, old) in trust.trusted(walletId).first()) trust.setTrusted(walletId, MintKey(host, key), true)
        recertify(mint, presented)
        true
    }

    /**
     * Replaces the certificates of the notes on [mint] of every account of its wallet, signed with its old key, by the mint's current ones
     * under [key]: a note shared later carries a certificate its recipient can verify. A note whose certificate doesn't verify is left
     * without one; one the mint doesn't answer for (spent, or no answer) keeps its old one.
     */
    private suspend fun recertify(mint: Mint, key: PublicKey) {
        val held =
            store.accounts
                .first()
                .filter { it.account.walletId == mint.walletId }
                .flatMap { store.notes(it.account.id).first() }
                .filter { it.host == mint.host && it.kind == NoteKind.KeyPath && it.state != NoteState.Spent }
        val updated = held.mapNotNull { note ->
            val q = ByteVector32(note.id)
            val info = runCatching { client.lookup(mint.withdrawUrl, q) }.getOrNull() ?: return@mapNotNull null
            val certificate = info.certificate?.takeIf { certifies(it, mint.host, q, key) && it.amountMsat == info.maxWithdrawableMsat }
            note.copy(certificate = certificate?.encode(), updatedAtMillis = now())
        }
        if (updated.isNotEmpty()) store.upsertNotes(updated)
    }

    /**
     * Connects to a mint the user names (pinning its key), so it can be trusted before any note from it arrives, and finds what every
     * account of the active wallet already holds there, as a restore does; accounts the phone didn't have are created. A scan that stops is
     * [ConnectResult.Failed]: adding the mint again goes on from what it found, and a new mint where it found nothing is forgotten, so that
     * it can be added again. Runs to its end if the screen that asked is left: a mint in the list with its scan cut off would look empty.
     */
    suspend fun addMint(host: String): ConnectResult =
        withContext(NonCancellable) {
            lock.withLock {
                val id = store.currentAccountId() ?: return@withLock ConnectResult.Failed(OnboardResult.MintInvalid("no account"))
                val walletId = walletOf(id)
                val known = Onboarding.normalizeHost(host)?.let { store.mint(walletId, it) } != null
                val connected = onboarding.connectMint(walletId, host)
                if (connected !is ConnectResult.Connected) return@withLock connected
                val h = connected.mint.host
                val failed = restorer.scanAddedMint(walletId, h)
                if (failed != null && !known && siblings(id).none { a -> store.notes(a.id).first().any { it.host == h } }) {
                    store.removeMint(walletId, h)
                    return@withLock ConnectResult.Failed(failed)
                }
                // The wallet's first mint: accounts without a home receive there from now on.
                siblings(id).filter { it.host == null }.forEach { store.saveAccount(it.copy(host = h)) }
                if (failed == null) connected else ConnectResult.Failed(failed)
            }
        }

    /** Whether [mint] (this host with this key) is trusted in the wallet [walletId]. */
    internal suspend fun isTrusted(walletId: String, mint: MintKey): Boolean {
        val homes = store.accounts.first().filter { it.account.walletId == walletId }.mapNotNull { it.account.host }.toSet()
        val default = trustDefault(mint, store.mint(walletId, mint.host)?.mintPubkey, homes)
        return isTrusted(mint, default, trust.trusted(walletId).first(), trust.untrusted(walletId).first())
    }

    /**
     * A home mint of the wallet ([homes]) is trusted by default with the key it [pinned] for it; a brand's mint only with the key it ships.
     */
    private fun trustDefault(mint: MintKey, pinned: String?, homes: Set<String>): TrustDefault? =
        when {
            mint.host in homes && pinned == mint.pubkey -> TrustDefault.Home
            mint in brandTrusted -> TrustDefault.Brand
            else -> null
        }

    /** Hands out the active account's note [id] whole, as a link (see [NoteSender.share]). */
    suspend fun shareNote(id: String): ShareNoteResult = lock.withLock { sender.share(id) }

    /** Asks the mint about the active account's outstanding note [id] and follows its answer (see [NoteCheck]). */
    suspend fun recheckNote(id: String): NoteCheck = lock.withLock { recheck(id) }

    /**
     * Refreshes (as on Home), then asks the mints about every outstanding note of the active account. A mint that doesn't answer is skipped
     * for the rest of the run. As [WalletTask.Updating] throughout, so the work never seems done between the two.
     */
    suspend fun recheckNotes(): NotesCheck = lock.withLock {
        working(WalletTask.Updating) {
            refreshUnderLock()
            var checked = 0
            var gone = 0
            var unknown = 0
            val unreachable = mutableListOf<String>()
            val notes = store.notes(activeId()).first().filter { it.isRecheckable() || it.isUnknown() }
            if (notes.isNotEmpty())
                working(WalletTask.CheckingNotes(0, notes.size)) {
                    for ((i, note) in notes.withIndex()) {
                        _task.value = WalletTask.CheckingNotes(i, notes.size)
                        if (note.host in unreachable) continue
                        when (recheck(note.id)) {
                            is NoteCheck.Ok -> checked++
                            NoteCheck.Spent -> {
                                checked++
                                gone++
                            }
                            // One set aside before is as it was: nothing to tell.
                            NoteCheck.Unknown ->
                                if (!note.isUnknown()) {
                                    checked++
                                    unknown++
                                }
                            NoteCheck.Unreachable,
                            is NoteCheck.KeyChanged -> unreachable += note.host
                            // Only outstanding notes are checked here; the refresh above settled the confirming ones.
                            NoteCheck.NotFound,
                            NoteCheck.Landed,
                            NoteCheck.NotLanded -> Unit
                        }
                    }
                }
            NotesCheck(checked, gone, unreachable, unknown)
        }
    }

    private suspend fun recheck(id: String): NoteCheck {
        val note =
            store.note(id)?.takeIf { it.accountId == activeId() && (it.isRecheckable() || it.isUnknown() || it.isConfirming()) }
                ?: return NoteCheck.NotFound
        if (mintOf(note.accountId, note.host) == null) return NoteCheck.Unreachable
        // A recheck only asks: whatever failure a developer armed, it goes unanswered.
        if (SimulatedFailure.takeAny()) return NoteCheck.Unreachable
        if (note.isConfirming()) return settle(note)
        val probe =
            try {
                inspector.recheck(KeySet.of(note).key(note.accountId, note.host, note.index!!))
            } catch (e: MintKeyChangedException) {
                return NoteCheck.KeyChanged(note.host)
            }
        return when (probe) {
            is KeyProbe.Outstanding -> NoteCheck.Ok(probe.amountMsat, probe.certified)
            is KeyProbe.Spent -> NoteCheck.Spent
            is KeyProbe.Unused -> NoteCheck.Unknown
            is KeyProbe.Refused,
            is KeyProbe.Failed -> NoteCheck.Unreachable
        }
    }

    /**
     * Asks the mints again, now, about the active account's notes they said they don't know ([NoteState.Unknown]); one they know again is
     * back in the balance. [NotesCheck.checked] counts those, [NotesCheck.unknown] those still unknown.
     */
    suspend fun recheckUnknownNotes(): NotesCheck = lock.withLock {
        val notes = store.notes(activeId()).first().filter { it.isUnknown() }
        if (notes.isEmpty()) return@withLock NotesCheck(0, 0, emptyList())
        working(WalletTask.CheckingNotes(0, notes.size)) {
                var back = 0
                var still = 0
                val unreachable = mutableListOf<String>()
                for ((i, note) in notes.withIndex()) {
                    _task.value = WalletTask.CheckingNotes(i, notes.size)
                    if (note.host in unreachable) continue
                    unknownCheckedAt[note.id] = now()
                    when (recheck(note.id)) {
                        is NoteCheck.Ok -> back++
                        NoteCheck.Unknown -> still++
                        NoteCheck.Unreachable,
                        is NoteCheck.KeyChanged -> unreachable += note.host
                        NoteCheck.Spent,
                        NoteCheck.NotFound,
                        NoteCheck.Landed,
                        NoteCheck.NotLanded -> Unit
                    }
                }
                NotesCheck(back, 0, unreachable, still)
            }
            .also { if (it.checked > 0) applyPolicies(activeId()) }
    }

    /**
     * Settles the change [note] went into whose answer never came (see [NoteState.Ambiguous]), now rather than on the next refresh: the
     * mint is asked whether it landed.
     */
    private suspend fun settle(note: Note): NoteCheck {
        val entry =
            store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).firstOrNull { note.id in it.inputIds }
                ?: return NoteCheck.NotFound
        return when (Reconciler(this).reconcile(entry)) {
            JournalStatus.Confirmed -> NoteCheck.Landed
            JournalStatus.Rejected -> NoteCheck.NotLanded
            else -> NoteCheck.Unreachable
        }
    }

    /** Renews the active account's note [id]: rotated onto a fresh wallet key, so any copy of the old one is worthless. */
    suspend fun rotateNote(id: String): RotateResult = lock.withLock {
        val note =
            store.note(id)?.takeIf { it.accountId == activeId() && it.isRecheckable() }
                ?: return@withLock RotateResult.NotRotatable("no outstanding note $id")
        rotator.rotate(note.accountId, note.host, KeySet.of(note), note.index!!)
    }

    /** Checks whether sent note [id] has been spent (on top of the check every refresh does). */
    suspend fun checkSharedNote(id: String): SharedNote? = lock.withLock {
        val unknown = sender.reconcile(activeId())
        sender.sharedNote(id, unknownToMint = id in unknown)
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

    /**
     * Settles unknown outcomes, then checks the address of every account of the active wallet and scans for new notes. As
     * [WalletTask.Updating], unless no mint has anything to tell (no address, nothing open, no handed-out note). Another wallet is left
     * alone: it catches up once it is active.
     */
    suspend fun refresh(): RefreshResult = lock.withLock { refreshUnderLock() }

    private suspend fun refreshUnderLock(): RefreshResult =
        if (asksMints()) working(WalletTask.Updating) { refreshLocked() } else refreshLocked()

    private suspend fun asksMints(): Boolean {
        val ids = walletAccountIds()
        return store.journal(UNSETTLED).any { it.accountId in ids } ||
            store.receivedNotes(ReceivedState.PENDING).any { it.accountId in ids } ||
            store.accounts
                .first()
                .filter { it.account.id in ids }
                .any { summary ->
                    val id = summary.account.id
                    summary.profile != null ||
                        store.addresses(id).first().isNotEmpty() ||
                        store.notes(id).first().any { it.state.isShared || it.state == NoteState.Unknown }
                }
    }

    private suspend fun refreshLocked(): RefreshResult {
        val ids = walletAccountIds()
        reconciler.reconcileAll(ids)
        received.retryAll(ids)
        val active = store.currentAccountId()
        var result = RefreshResult(0, 0)
        for (summary in store.accounts.first().filter { it.account.id in ids }) {
            val id = summary.account.id
            unknownNotes.recheckDue(id)
            // First: a held address that was paid may be the account's primary one now.
            store.addresses(id).first().filter { it.feeInvoice != null }.forEach { onboarding.settleHeld(id, it) }
            legacy.migration.run(id)
            val profile = store.currentProfile(id)
            if (profile != null) {
                addressCheck.check(profile.username, profile.host, id).let { status ->
                    if (status !is AddressStatus.CheckFailed) setAddressStatus(id, profile.address, status)
                }
                val scanned = scanner.scanAll(id, profile.host)
                if (id == active) result = scanned
            }
            // Addresses on other mints (same recovery phrase, that mint's own keys) are paid there, and a restored account's on its home
            // mint, or a mint it added, before the phone knows their names.
            val hosts = store.addresses(id).first().map { it.host } + listOfNotNull(store.account(id)?.host) + hostsPaidAt(id)
            for (host in hosts.distinct() - setOfNotNull(profile?.host)) {
                val scanned = scanner.scanAll(id, host)
                if (id == active) result = RefreshResult(result.newNotes + scanned.newNotes, result.receivedMsat + scanned.receivedMsat)
            }
            sender.reconcile(id)
            applyPolicies(id)
        }
        return result
    }

    /** The mints where [accountId] has handed out shares or holds a note paid to an address, whether or not it knows the address. */
    private suspend fun hostsPaidAt(id: String): List<String> =
        store.notes(id).first().filter { it.isAddressNote }.map { it.host } +
            store
                .mints(walletOf(id))
                .first()
                .map { it.host }
                .filter { (store.keyIndexOrNull(id, spendDomain(it), KeyCounter.Shares) ?: 0) > 0 }

    /**
     * An invoice for [amountMsat] that mints a note to the active account on its home mint once paid (see [InvoiceReceiver]); the way to
     * receive without a Lightning address. Follow it with [invoiceStatus].
     */
    suspend fun requestInvoice(amountMsat: Long): InvoiceRequest = lock.withLock { invoices.request(amountMsat) }

    /** Looks up whether invoice [id] was paid; a paid one's note is in the wallet afterwards. */
    suspend fun invoiceStatus(id: String): InvoiceStatus = lock.withLock {
        invoices.status(id).also { if (it is InvoiceStatus.Paid) applyPolicies(activeId()) }
    }

    suspend fun previewClaim(input: String): ClaimPreview = claimer.preview(input, activeId())

    /** Claims the note now. It is kept first, so if it can't be claimed now ([ClaimResult.Kept]), a refresh claims it later. */
    suspend fun claim(preview: ClaimPreview.Ready): ClaimResult = lock.withLock {
        val id = activeId()
        claimer.claim(preview, id).also { applyPolicies(id) }
    }

    /** Keeps a note that can't be claimed now (offline, or locked); a refresh claims it once it can be. */
    suspend fun keepClaim(preview: ClaimPreview.Keepable): ClaimResult.Kept = lock.withLock { claimer.keep(preview, activeId()) }

    /** Forgets a received note that can no longer be claimed (spent by someone else, or not spendable). */
    suspend fun dismissReceivedNote(id: String) = lock.withLock { received.dismiss(id) }

    internal fun syncWhenOnline() = onWaitingForNetwork()

    /** Moves [amountMsat] (or everything, if null) from the active account to [toAccountId]. */
    suspend fun moveFunds(toAccountId: String, amountMsat: Long?): MoveResult = lock.withLock {
        val id = activeId()
        mover.move(id, toAccountId, amountMsat).also {
            applyPolicies(id)
            applyPolicies(toAccountId)
        }
    }

    /**
     * Deletes the active wallet from this device: its recovery phrase and every account of it, with their notes, journals and settings.
     * Their funds stay at the mint and can only be recovered with the phrase. Another account becomes active if there is one. Returns true
     * if one did.
     */
    suspend fun deleteActiveAccount(): Boolean = lock.withLock { deleteActive(prefer = null, wholeWallet = true) }

    /**
     * Undoes a restore whose address step isn't done (see [needsAddress]): the restored account is removed from this device (the whole
     * wallet, unless another of its accounts is set up), and the account that was active before it (if still known) is active again.
     * Nothing changes at the mint: the money stays under the recovery phrase and can be restored again. Returns whether an account remains.
     */
    suspend fun abortRestore(): Boolean = lock.withLock {
        check(store.currentAccountId()?.let { store.currentProfile(it) == null } == true) { "no restore in progress" }
        val others = siblings(activeId()).filter { it.id != activeId() }
        val alone = others.none { store.currentProfile(it.id) != null }
        deleteActive(prefer = activeBeforeRestore, wholeWallet = alone).also { activeBeforeRestore = null }
    }

    /** The account that was active when a restore started; kept in memory only (after a restart, any account is fine). */
    internal var activeBeforeRestore: String? = null

    private suspend fun deleteActive(prefer: String?, wholeWallet: Boolean): Boolean {
        val active = activeId()
        val walletId = walletOf(active)
        for (id in if (wholeWallet) siblings(active).map { it.id } else listOf(active)) {
            store.deleteAccount(id)
            policies.clear(id)
            _lastPolicyRun.update { it - id }
            collectBlocks.update { blocks -> blocks.filterKeys { it.first != id } }
            keySets.keys.removeAll { it.first == id }
            addressStatuses.update { statuses -> statuses.filterKeys { it.first != id } }
            failedProbes.update { probes -> probes.filterKeys { it.accountId != id } }
        }
        if (wholeWallet) {
            seeds.clear(walletId)
            trust.forgetWallet(walletId)
        }
        val remaining = store.accounts.first().map { it.account.id }
        val next = prefer?.takeIf { it in remaining } ?: remaining.firstOrNull()
        store.setActiveAccount(next)
        return next != null
    }

    // ---- Developer tools over the active account's derived keys

    suspend fun keyHosts(): List<String> = inspector.hosts(activeId())

    /** The active account's index of [set] on [host]: the next key it hands out, or where the next scan starts. */
    suspend fun keyIndex(host: String, set: KeySet): Long = store.indexOf(activeId(), host, set)

    /** The active account's indexes on [host]: each counter or scan start that is set, by the keys it counts. */
    suspend fun keyIndexes(host: String): Map<KeySet, Long> = store.indexesFlow(activeId(), host).first()

    /**
     * The active account's shares on [host]'s domain, in order: every one handed out, with the address on it at [host] if the phone knows
     * it.
     */
    suspend fun addressShares(host: String): List<AddressShare> {
        val id = activeId()
        val addresses = store.addresses(id).first().filter { it.host == host && it.share != null }.associateBy { it.share!! }
        val handedOut = store.keyIndexOrNull(id, spendDomain(host), KeyCounter.Shares) ?: 0
        return ((0 until handedOut) + addresses.keys).distinct().sorted().map { AddressShare(it, addresses[it]?.address) }
    }

    /** Keys 0 until index + [gap] of [set] on [host]. */
    suspend fun derivedKeys(host: String, set: KeySet, gap: Int = settings.scanStopAfterUnusedKeys): List<DerivedKey> =
        activeId().let { id -> inspector.keys(id, host, set, gap, store.keyChecks(id).first(), failedProbes.value) }

    /** Looks up keys index until index + [gap], importing notes and moving the index past any used key found. */
    suspend fun scanKeysAhead(host: String, set: KeySet, gap: Int = settings.scanStopAfterUnusedKeys): KeyScanResult = lock.withLock {
        inspector.scanAhead(activeId(), host, set, gap)
    }

    suspend fun recheckKey(host: String, set: KeySet, index: Long): KeyProbe = lock.withLock {
        inspector.recheck(set.key(activeId(), host, index))
    }

    /** Finds the right index for [set] on [host] (lookups past the highest used key, gap limit 20) and sets it. */
    suspend fun findKeyIndex(host: String, set: KeySet, gap: Int = settings.scanStopAfterUnusedKeys): DiscoveryResult = lock.withLock {
        discovery.discover(activeId(), host, set, gap)
    }

    /** Sets an index by hand; refused below the highest key the wallet knows is used (+1). */
    suspend fun setKeyIndex(host: String, set: KeySet, index: Long): SetIndexResult = lock.withLock {
        val id = activeId()
        val floor = discovery.highestKnownUsed(id, host, set) + 1
        if (index < floor) return@withLock SetIndexResult.BelowFloor(floor)
        store.setIndexOf(id, host, set, index)
        SetIndexResult.Applied(index)
    }

    /** Looks up keys 0 until index + [gap] of [set] on [host] and syncs the wallet with the answers. */
    suspend fun recheckAllKeys(host: String, set: KeySet, gap: Int = settings.scanStopAfterUnusedKeys): KeyScanResult = lock.withLock {
        inspector.recheckAll(activeId(), host, set, gap)
    }

    suspend fun keyIndexHealth(host: String, set: KeySet): IndexHealth {
        val id = activeId()
        val next = store.indexOf(id, host, set)
        return IndexHealth(next, discovery.highestKnownUsed(id, host, set), indexVerifiedAt.value[Triple(id, host, set)])
    }

    internal fun markIndexVerified(accountId: String, host: String, set: KeySet) = indexVerifiedAt.update {
        it + (Triple(accountId, host, set) to now())
    }

    /** The active account's indexes on [host], updated on every change (from any screen or background refresh). */
    fun keyIndexesFlow(host: String): Flow<Map<KeySet, Long>> =
        ofActive(emptyMap()) { id -> store.indexesFlow(id, host) }.distinctUntilChanged()

    /**
     * Emits whenever anything the developer key page shows for [host] may have changed: the active account's notes, lookup results,
     * indexes, policy runs or verification times. Lets that page stay in sync without polling.
     */
    fun keyViewChanges(host: String): Flow<Unit> =
        merge(
            notes.map {},
            keyProbes.map {},
            failedProbes.map {},
            keyIndexesFlow(host).map {},
            lastPolicyRun.map {},
            indexVerifiedAt.map {},
        )

    /** Rotates the active account's note at ([host], [set], [index]) onto the next wallet key. */
    suspend fun rotateKey(host: String, set: KeySet, index: Long): RotateResult = lock.withLock {
        rotator.rotate(activeId(), host, set, index)
    }

    /** Rotates every outstanding note of [set] on [host] onto new wallet keys, one at a time. */
    suspend fun rotateAllKeys(host: String, set: KeySet): RotateAllResult = lock.withLock {
        rotator.rotateAll(activeId(), host, { it == set })
    }

    /** The wallet key index the next rotate would use; a preview, not a reservation. */
    suspend fun nextWalletIndex(host: String): Long = store.indexOf(activeId(), host, KeySet.Wallet)

    /** Saves a lookup's answer; a lookup without one is only remembered for this session. */
    internal suspend fun recordProbe(id: KeyId, probe: KeyProbe) {
        if (probe is KeyProbe.Failed) {
            failedProbes.update { it + (id to probe) }
        } else {
            store.putKeyCheck(id, probe)
            failedProbes.update { it - id }
        }
    }

    /** The active wallet's operations whose outcome isn't settled yet. */
    suspend fun unresolvedOperations(): Int {
        val ids = walletAccountIds()
        return store
            .journal(UNSETTLED)
            // An invoice waiting to be paid isn't stuck (see [requestInvoice]).
            .count { it.accountId in ids && !(it.type == OperationType.Mint && it.detail == null) }
    }

    /** The ids of the active wallet's accounts; none before there is an account. */
    private suspend fun walletAccountIds(): Set<String> =
        store.currentAccountId()?.let { active -> siblings(active).map { it.id }.toSet() }.orEmpty()

    /** Applies [accountId]'s note policies; must be called under the lock. */
    private suspend fun applyPolicies(accountId: String) {
        val collect =
            collectDefaults
                .takeIf { it.active(policies.collect(accountId).first()) }
                ?.let {
                    store.account(accountId)?.host?.let { home -> CollectPolicy(home, collectFeeCap(accountId).first()) }
                }
        val run =
            policyEngine.apply(
                accountId,
                strategies.resolve(policies.strategy(accountId).first()),
                policies.autoRotate(accountId).first(),
                collect,
            )
        if (run.didSomething) _lastPolicyRun.update { it + (accountId to run) }
    }

    /** [ofAccount] of whichever account is active, following switches; [none] while there is no account. */
    private fun <T> ofActive(none: T, ofAccount: (String) -> Flow<T>): Flow<T> =
        store.activeAccountId.flatMapLatest { id -> id?.let(ofAccount) ?: flowOf(none) }

    /** [ofWallet] of the active account's wallet, following switches; [none] while there is no account. */
    private fun <T> ofActiveWallet(none: T, ofWallet: (String) -> Flow<T>): Flow<T> =
        activeAccount.map { it?.account?.walletId }.distinctUntilChanged().flatMapLatest { id -> id?.let(ofWallet) ?: flowOf(none) }

    /** [ofAccount] of every account of the active wallet, joined, following switches and accounts being added and removed. */
    private fun <T> ofActiveWalletAccounts(ofAccount: (String) -> Flow<List<T>>): Flow<List<T>> =
        activeWalletAccounts
            .map { list -> list.map { it.account.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map(ofAccount)) { lists -> lists.flatMap { it.toList() } }
            }

    internal suspend fun activeId(): String = checkNotNull(store.currentAccountId()) { "no active account" }

    /**
     * The account's keys on [host] ([AccountKeys]), kept for the life of the process. The seed they come from is not: it can derive the
     * keys of every mint, so it is made again for each new mint and zeroed straight after. An account not saved yet is its own wallet's
     * first.
     */
    internal suspend fun keys(accountId: String, host: String): AccountKeys {
        keySets[accountId to host]?.let {
            return it
        }
        val account = accountOf(accountId)
        val words = checkNotNull(seeds.load(account?.walletId ?: accountId)) { "account has no seed" }
        val seed = RecoveryPhrase.seed(words)
        return try {
            keySets.getOrPut(accountId to host) {
                AccountKeys(ServiceRoot.fromSeed(seed, host).account(account?.index ?: 0), LegacyKeys.fromSeed(seed, host))
            }
        } finally {
            seed.fill(0)
        }
    }

    /** The keys, or null if the account has no seed (so nothing can be ours). */
    internal suspend fun keysIfSeeded(accountId: String?, host: String): AccountKeys? {
        if (accountId == null) return null
        if (keySets[accountId to host] == null && seeds.load(walletOf(accountId)) == null) return null
        return keys(accountId, host)
    }

    /** An account being set up in an existing wallet: its keys are needed before it is saved. */
    internal var forming: Account? = null

    internal suspend fun accountOf(accountId: String): Account? = store.account(accountId) ?: forming?.takeIf { it.id == accountId }

    /** [accountId]'s wallet's mint at [host]: what it fetched and the key it pinned. */
    internal suspend fun mintOf(accountId: String, host: String): Mint? = store.mint(walletOf(accountId), host)

    /** The wallet (recovery phrase) of [accountId]. An account not saved yet is its own wallet's first. */
    internal suspend fun walletOf(accountId: String): String = accountOf(accountId)?.walletId ?: accountId

    /** The accounts sharing [accountId]'s wallet, it included. */
    internal suspend fun siblings(accountId: String): List<Account> {
        val walletId = walletOf(accountId)
        return store.accounts.first().map { it.account }.filter { it.walletId == walletId }
    }

    /**
     * Which protocol the mint at [host] speaks, asked once per process with a request that changes nothing (see LnurlcashClient.dialect). A
     * mint that answers neither way is taken for a legacy one: only a LUD-26 mint answers the probe in LNURL form. Throws IOException when
     * the mint can't be reached.
     */
    internal suspend fun dialect(host: String): MintDialect =
        dialects[host]
            ?: (try {
                    client.dialect(host)
                } catch (_: ProtocolViolation) {
                    MintDialect.Legacy
                })
                .also { dialects[host] = it }

    /** Asks [host] again next time, e.g. after a request of the other protocol was refused: the mint may have been upgraded. */
    internal fun forgetDialect(host: String) {
        dialects.remove(host)
    }

    internal fun setAddressStatus(accountId: String, address: String, status: AddressStatus?) = addressStatuses.update {
        if (status == null) it - (accountId to address) else it + ((accountId to address) to status)
    }

    /**
     * The recovery phrase the next new account gets, made ahead so onboarding can suggest its seed-derived name ([DefaultAddress]). Held in
     * memory only until an account is created with it or [forgetPreparedPhrase] is called. Its entries are the wordlist's own strings, so
     * emptying the list leaves nothing of the phrase behind.
     */
    @Volatile private var prepared: PreparedPhrase? = null

    /**
     * A phrase made ahead and the names already derived from it, by host: deriving one runs PBKDF2 and BIP-32, which leave copies behind
     * that can't be zeroed, so each mint's name is derived once. The names are public.
     */
    private class PreparedPhrase(val words: MutableList<String>) {
        val names = ConcurrentHashMap<String, String>()
    }

    private fun prepared(): PreparedPhrase =
        prepared
            ?: run {
                val entropy = ByteArray(16).also(random::nextBytes)
                try {
                    PreparedPhrase(MnemonicCode.toMnemonics(entropy).toMutableList()).also { prepared = it }
                } finally {
                    entropy.fill(0)
                }
            }

    /**
     * Drops the phrase made ahead for the next new account. Call it when onboarding is left: a later one gets a new phrase (and so other
     * suggested names).
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
        // The phrase was the wallet's: what it connected to and chose goes with it.
        store.removeMints(accountId)
        trust.forgetWallet(accountId)
        keySets.keys.removeAll { it.first == accountId }
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

    /** The change a confirming note went into landed: the note is spent, its outputs are recorded. */
    data object Landed : NoteCheck

    /** The change a confirming note went into never landed: the note is spendable again. */
    data object NotLanded : NoteCheck

    /** The mint signs with a different key than before; operations on it are stopped. */
    data class KeyChanged(val host: String) : NoteCheck

    /** Not an outstanding note of the active account with a derived key. */
    data object NotFound : NoteCheck
}

/**
 * What [Wallet.recheckNotes] found: notes still there, notes gone (spent elsewhere), mints that didn't answer, and notes set aside because
 * their mint doesn't know them ([unknown]).
 */
data class NotesCheck(val checked: Int, val gone: Int, val unreachable: List<String>, val unknown: Int = 0)

/** Notes a lookup can confirm and a rotate can renew: outstanding, at a key derived from the seed. */
internal fun Note.isRecheckable(): Boolean = state == NoteState.Outstanding && kind == NoteKind.KeyPath && purpose != null && index != null

/** Set aside because its mint says it doesn't know it ([NoteState.Unknown]); a recheck may bring it back. */
internal fun Note.isUnknown(): Boolean = state == NoteState.Unknown && kind == NoteKind.KeyPath && purpose != null && index != null

/** In a change whose answer never came ([NoteState.Ambiguous]); a recheck settles it. */
internal fun Note.isConfirming(): Boolean = state == NoteState.Ambiguous && kind == NoteKind.KeyPath
