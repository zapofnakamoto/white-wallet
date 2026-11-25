package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** What the mint answered when an address was last checked, and when ([AddressChecks]). */
data class AddressCheckRecord(val atMillis: Long, val status: AddressStatus)

/**
 * When each of an account's Lightning addresses was last checked with its mint, and what it answered: saved, so an address is checked again
 * only once `address.check.interval.millis` is over, also after a restart (each check tells the mint, signed, that this phone holds the
 * name). Only answers are kept: a check that failed says nothing about the address.
 */
interface AddressChecks {
    /** The account's checked addresses, by address ("name@host"). */
    fun checks(accountId: String): Flow<Map<String, AddressCheckRecord>>

    suspend fun record(accountId: String, address: String, check: AddressCheckRecord)

    /** Drops [address]'s check: it isn't the account's any more. */
    suspend fun forget(accountId: String, address: String)

    /** Drops every check of the account: it is gone from the phone. */
    suspend fun forgetAccount(accountId: String)

    class InMemory : AddressChecks {
        private val all = MutableStateFlow(emptyMap<String, Map<String, AddressCheckRecord>>())

        override fun checks(accountId: String): Flow<Map<String, AddressCheckRecord>> = all.map { it[accountId].orEmpty() }

        override suspend fun record(accountId: String, address: String, check: AddressCheckRecord) = all.update {
            it + (accountId to (it[accountId].orEmpty() + (address to check)))
        }

        override suspend fun forget(accountId: String, address: String) = all.update {
            it + (accountId to (it[accountId].orEmpty() - address))
        }

        override suspend fun forgetAccount(accountId: String) = all.update { it - accountId }
    }

    companion object {
        /** One check as saved: "address millis status" (an address never contains a space); null for a check that isn't kept. */
        fun encode(address: String, check: AddressCheckRecord): String? = statusName(check.status)?.let { "$address ${check.atMillis} $it" }

        /** The check [entry] holds, or null for one this version can't read (it is checked again). */
        fun decode(entry: String): Pair<String, AddressCheckRecord>? {
            val (address, at, name) = entry.split(' ').takeIf { it.size == 3 } ?: return null
            val status = statusOf(name) ?: return null
            return address to AddressCheckRecord(at.toLongOrNull() ?: return null, status)
        }

        private fun statusName(status: AddressStatus): String? =
            when (status) {
                AddressStatus.Ours -> "ours"
                AddressStatus.Unconfirmed -> "unconfirmed"
                AddressStatus.Available -> "available"
                AddressStatus.Taken -> "taken"
                is AddressStatus.Invalid -> "invalid-${status.problem.name}"
                is AddressStatus.CheckFailed -> null
            }

        private fun statusOf(name: String): AddressStatus? =
            when (name) {
                "ours" -> AddressStatus.Ours
                "unconfirmed" -> AddressStatus.Unconfirmed
                "available" -> AddressStatus.Available
                "taken" -> AddressStatus.Taken
                else ->
                    name
                        .removePrefix("invalid-")
                        .takeIf { it != name }
                        ?.let { problem ->
                            AddressProblem.entries.firstOrNull { it.name == problem }?.let(AddressStatus::Invalid)
                        }
            }
    }
}
