# To do

Ideas and follow-ups noted for later. Not a plan: each item still needs its own design.

1. **Better account management.** Open questions: should accounts get their own place in the bottom navigation
   (today: the chip under the balance opens the switcher sheet; settings per account under Settings → Account), and
   should that be a brand option white-label apps can turn on or off? Related: `maxAccounts=1` already hides
   accounts entirely, and with the Notes tab the bar already has three.
2. **A changed signing key on the Mints list.** A mint that signs with a new key isn't recognizable on Settings →
   Mints: the row shows the pinned key and trust as before. The change only shows on the mint's page (its check
   reports `KeyChanged`, with the warning card) or when an operation stops (`Pinning`). The list should flag such a
   mint at a glance (and maybe Settings' Mints row too). Idea: a "Check all" button on the list that asks every mint
   (does it answer, does it still sign with the pinned key; what the mint page's check does, for all of them) and
   shows the result per row: reachable, unreachable, key changed (leading to the page with the warning). The user
   decides when, so the app never asks every mint on its own. On top of that, rows can show what operations already
   saw without asking anyone: a mint that stopped one with a different key (`Pinning`).
3. **Several payments on one success screen.** Receive (address shown) adds up everything that arrives while the
   screen is open (`ReceiveViewModel.received`), and keeps adding after the success screen is up; one refresh can
   also find several payments at once (`RefreshResult` has only a total and `newNotes`). The screen then says
   "Payment received" with the sum, as if it were one payment (seen with the developer faucet tapped several times).
   Idea: keep the sum, but say how many ("2 payments received, ₿2,000 in total", from `newNotes`); an invoice stays
   one payment.
4. **Derived keys where the mint is.** Developer options → Derived keys looks like a home-mint view: it offers a chip
   per mint only once the account has key counters there (`Wallet.keyHosts`), and none while there's just one. The
   keys belong to an account on a mint, so the view fits on the mint's page under Account → Your mints (account
   mode, next to the watch-only key): a "Derived keys" row, shown only while developer options are on, opening the
   view for that mint (a mint without counters yet shows its empty indexes rather than nothing). Then the developer
   entry can go, or stay as a shortcut to the home mint's.
5. **Mint pickers that say more than the name.** New mints are added at setup, when adding an account, under
   Settings → Mints, or by claiming a note from one (`NoteClaimer.rememberService`); everywhere else (change home, move money) a dropdown of known mints shows only hosts. Each entry
   should show what helps choosing: the host, trusted (or a changed key, as a warning), the active account's money
   there ("₿5,000 here", plus "₿2,000 in 2 other accounts" when there is some), "Home mint" where it is. Order: home,
   then trusted, then the rest, each by the account's money there. Untrusted mints stay pickable but marked, and the
   move summary says so before money goes to one. One shared picker component for all these places.
6. **One recovery phrase for several accounts.** Each account's seed would be a BIP-85 child of one master phrase
   (`m/83696968'/39'/0'/12'/i'`, account `i`), so one backup restores every account. LUD-26 stays unchanged inside
   each child (own `m/139'/0`, branch, `cx1` and Lightning address per mint), and an account's child phrase still
   works on its own in any LUD-26 wallet, e.g. exported to a second device, without revealing the master or the
   other accounts. Account 0 is child 0, not the master, so the master never touches a mint. Language and word
   count are part of the path and fixed for good. Only the master is stored; children are derived when needed.
   Open questions:
   - Separation gets weaker: a leaked master loses every account, not one. DESIGN.md ("Accounts") argues the
     opposite today and would change.
   - Restore has to find accounts: walk `i` with a gap limit, probing purposes 0/1/2 of each child on known mints
     (rate limits make it slow). Empty accounts, names and home mints don't come back (names and home mints don't
     today either).
   - The master phrase in another LUD-26 wallet that doesn't know BIP-85 shows nothing; the backup screens have to
     say the master phrase is for this app and account phrases work anywhere.
   - Installed users (since v0.3.0) keep accounts with their own seeds, and imported phrases are the same kind, so
     there are derived and standalone accounts side by side. Backup has to show which accounts the master phrase
     doesn't cover.
   - `maxAccounts=1` brands: the user sees the master phrase, the single account is child 0. Check Zap's
     onboarding and backup first.
7. **A changed signing key with `showMints=false`.** Without `showMints`, users never see mints, yet a mint that
   signs with a new key still names it and sends them to its details: the notes view's outcome (`notes_key_changed`,
   "%1$s now signs with a different key … until you check its details") and the wallet tab's card
   (`home_key_changed_title`, `home_key_changed_body`, its Details button → `onOpenMint`), on a mint page the brand
   hides. Open: what such a user should see and can decide (wording without the mint; whether Details opens a page
   of its own or the mint page anyway, since deciding about the new key is the user's), and whether other outcomes
   and cards name a mint the same way. Found while fixing the notes view for `showMints=false`.
