# To do

Ideas and follow-ups noted for later. Not a plan: each item still needs its own design.

1. **Better account management.** Open questions: should accounts get their own place in the bottom navigation
   (today: the chip under the balance opens the switcher sheet; settings per account under Settings → Account), and
   should that be a brand option white-label apps can turn on or off? Related: `maxAccounts=1` already hides
   accounts entirely, and with the Notes tab the bar already has three.
2. **A changed signing key on the Mints list.** A mint that signs with a new key isn't recognizable on Settings →
   Mints: the row shows the pinned key and trust as before. The change only shows on the mint's page (its check
   reports `KeyChanged`, with the warning card) or when an operation stops (`Pinning`). The list should flag such a
   mint at a glance (and maybe Settings' Mints row too). Open: when to check (on opening the list, on refresh, or from
   what operations already saw) without asking every mint each time.
