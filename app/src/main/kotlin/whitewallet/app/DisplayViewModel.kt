package whitewallet.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences

/** The unit every amount in the app is shown in: the user's choice (if the brand allows one), else the brand's default. */
@HiltViewModel
class DisplayViewModel @Inject constructor(preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    val amountUnit: StateFlow<AmountUnit> = preferences.amountUnit.map(defaults::amountUnit)
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaults.defaultAmountUnit)
}
