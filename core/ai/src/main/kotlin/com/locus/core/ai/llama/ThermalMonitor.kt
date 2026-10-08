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

package com.locus.core.ai.llama

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import com.locus.core.domain.chat.ThermalMonitor as DomainThermalMonitor

private const val POLLING_INTERVAL_MS = 1000L

/**
 * Android implementation of [DomainThermalMonitor] wrapping [PowerManager.addThermalStatusListener]
 * and [PowerManager.getCurrentThermalStatus] (M-7).
 */
@Singleton
class ThermalMonitor
    constructor(
        @ApplicationContext private val context: Context,
        powerManager: PowerManager? = null,
        coroutineScope: CoroutineScope? = null,
    ) : DomainThermalMonitor {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(context, null, null)

        private val powerManager: PowerManager? =
            powerManager ?: (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)

        private val _isThrottlingLikely = MutableStateFlow(false)
        override val isThrottlingLikely: StateFlow<Boolean> = _isThrottlingLikely.asStateFlow()

        private var pollingJob: Job? = null
        private var listenerRegistered = false
        private val scope: CoroutineScope =
            coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val thermalListener =
            PowerManager.OnThermalStatusChangedListener { status -> checkThermalStatus(status) }

        override fun startMonitoring() {
            val currentStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            checkThermalStatus(currentStatus)

            val pm = powerManager
            if (!listenerRegistered && pm != null) {
                val executor = Executor { command -> command.run() }
                runCatching {
                    pm.addThermalStatusListener(executor, thermalListener)
                    listenerRegistered = true
                }
            }

            // ponytail: combined listener + 1s polling ensures OEM thermal updates and adb overrides are both
            // caught
            if (pollingJob == null || pollingJob?.isActive == false) {
                pollingJob =
                    scope.launch {
                        while (isActive) {
                            delay(POLLING_INTERVAL_MS)
                            val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                            checkThermalStatus(status)
                        }
                    }
            }
        }

        override fun stopMonitoring() {
            pollingJob?.cancel()
            pollingJob = null

            val pm = powerManager
            if (listenerRegistered && pm != null) {
                runCatching { pm.removeThermalStatusListener(thermalListener) }
                listenerRegistered = false
            }
        }

        override fun dismissWarning() {
            _isThrottlingLikely.value = false
        }

        private fun checkThermalStatus(status: Int) {
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                _isThrottlingLikely.value = true
            }
        }
    }
