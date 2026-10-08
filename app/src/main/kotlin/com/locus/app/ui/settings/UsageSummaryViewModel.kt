/*
 * Copyright 2026 Locus Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.locus.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.usage.MonthlyUsageSummary
import com.locus.core.domain.usage.PriceTableStore
import com.locus.core.domain.usage.ProviderPrice
import com.locus.core.domain.usage.UsageTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageSummaryViewModel
    @Inject
    constructor(
        private val usageTracker: UsageTracker,
        private val priceTableStore: PriceTableStore,
    ) : ViewModel() {
        private val _selectedMonth = MutableStateFlow(YearMonth.now())
        val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

        val monthlySummary: StateFlow<MonthlyUsageSummary> =
            _selectedMonth
                .flatMapLatest { ym -> usageTracker.observeMonthlySummary(ym) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = MonthlyUsageSummary(_selectedMonth.value, emptyList()),
                )

        val prices: StateFlow<Map<String, ProviderPrice>> =
            priceTableStore.prices.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyMap(),
            )

        fun previousMonth() {
            _selectedMonth.value = _selectedMonth.value.minusMonths(1)
        }

        fun nextMonth() {
            _selectedMonth.value = _selectedMonth.value.plusMonths(1)
        }

        fun updatePrice(
            providerId: String,
            inputPrice: Double,
            outputPrice: Double,
        ) {
            viewModelScope.launch {
                priceTableStore.setPrice(
                    providerId = providerId,
                    price =
                        ProviderPrice(
                            inputPricePerMillion = inputPrice,
                            outputPricePerMillion = outputPrice,
                        ),
                )
            }
        }

        fun resetPrice(providerId: String) {
            viewModelScope.launch { priceTableStore.resetPrice(providerId) }
        }

        companion object {
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
