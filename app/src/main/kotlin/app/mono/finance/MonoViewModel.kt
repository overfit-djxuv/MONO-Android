package app.mono.finance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mono.domain.MoneyContext
import app.mono.domain.model.TransactionRecord
import app.mono.finance.data.LedgerState
import app.mono.finance.data.ledgerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One ViewModel for the money screens. Screens read [ledger] and send intents; no maths happens here. */
class MonoViewModel(app: Application) : AndroidViewModel(app) {
    private val mono = app as MonoApp
    private val dao = mono.db.dao()

    val ledger: StateFlow<LedgerState?> = dao.ledgerState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val moneyContext: StateFlow<MoneyContext> =
        mono.settings.moneyContext.stateIn(viewModelScope, SharingStarted.Eagerly, MoneyContext())

    fun saveTransaction(t: TransactionRecord) { viewModelScope.launch { dao.put(t) } }
    fun deleteTransaction(id: String) { viewModelScope.launch { dao.deleteTransaction(id) } }
    fun toggleHidden() { viewModelScope.launch { mono.settings.setHidden(!moneyContext.value.hidden) } }
}
