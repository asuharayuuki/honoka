package org.eu.suisei.xray

import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.jdk9.asPublisher
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import java.util.function.Consumer

class DashboardControlsService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actions = Dispatchers.IO.limitedParallelism(1)
    private val uncheckedStates = ConcurrentHashMap<String, Boolean>()
    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        uncheckedStates.clear()
        refresh()
    }
    private val config by lazy { getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE) }
    private val appIntent by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onCreate() {
        super.onCreate()
        config.registerOnSharedPreferenceChangeListener(configListener)
    }

    override fun onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(configListener)
        scope.cancel()
        super.onDestroy()
    }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> = flow {
        for (entry in entries().values) emit(stateless(entry))
    }.asPublisher(Dispatchers.IO)

    override fun createPublisherFor(controlIds: List<String>): Flow.Publisher<Control> = flow {
        updates.collect {
            val current = entries()
            for (id in controlIds.distinct()) {
                val entry = current[id]
                emit(if (entry == null) {
                    Control.StatefulBuilder(id, appIntent).setStatus(Control.STATUS_NOT_FOUND).build()
                } else {
                    stateful(entry)
                })
            }
        }
    }.asPublisher(Dispatchers.IO)

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        scope.launch(actions) {
            val success = try {
                val entry = entries()[controlId]
                entry != null && action.templateId == controlId &&
                    !getSystemService(KeyguardManager::class.java).isDeviceLocked &&
                    performAction(entry, action)
            } catch (_: Exception) {
                false
            }
            refresh()
            consumer.accept(if (success) ControlAction.RESPONSE_OK else ControlAction.RESPONSE_FAIL)
        }
    }

    private fun performAction(entry: Entry, action: ControlAction): Boolean {
        val item = entry.item
        if (item.isCore && action is BooleanAction) {
            val status = if (action.newState) RootShell.startService(this) else RootShell.stopService(this)
            XrayTileService.requestRefresh(this)
            return status.isRootGranted && if (action.newState) {
                status.state == RootShell.State.ON
            } else {
                status.state == RootShell.State.OFF && status.pid.isNullOrEmpty()
            }
        }
        if (isDisabled(item)) return false
        val command = when {
            entry.isSwitch && action is BooleanAction -> if (action.newState) item.onCommand else item.offCommand
            !entry.isSwitch && action is CommandAction -> item.onCommand
            else -> return false
        }
        if (command.isBlank()) return false
        val result = DashboardCommands.runAction(command)
        val success = !result.timedOut && result.exitCode == 0
        if (success && action is BooleanAction && item.stateCheckCommand.isBlank()) {
            uncheckedStates[item.id] = action.newState
        }
        return success
    }

    private fun entries(): Map<String, Entry> = buildMap {
        for (section in DashboardConfigManager.load(this@DashboardControlsService)) {
            for (item in section.items) put(item.id, Entry(item))
        }
    }

    private fun stateless(entry: Entry): Control {
        val item = entry.item
        val builder = Control.StatelessBuilder(item.id, appIntent)
            .setTitle(item.getDisplayTitle(this))
            .setStructure(getString(R.string.app_name))
            .setDeviceType(if (entry.isSwitch) DeviceTypes.TYPE_SWITCH else DeviceTypes.TYPE_ROUTINE)
        if (item.icon.isNotBlank() && !item.icon.equals("none", ignoreCase = true)) {
            builder.setCustomIcon(MaterialIconCatalog.controlsIcon(this, item.icon))
        }
        return builder.build()
    }

    private fun stateful(entry: Entry): Control {
        val item = entry.item
        val builder = Control.StatefulBuilder(stateless(entry))
        if (isDisabled(item)) return builder.setStatus(Control.STATUS_DISABLED).build()

        var statusText = ""
        if (entry.isSwitch) {
            val checked = if (item.isCore) {
                val status = RootShell.queryDetailedStatus(this)
                if (!status.isRootGranted || status.state == RootShell.State.ERROR) {
                    return builder.setStatus(Control.STATUS_ERROR)
                        .setStatusText(getString(if (status.isRootGranted) R.string.state_error else R.string.state_root_denied))
                        .build()
                }
                status.state == RootShell.State.ON || status.state == RootShell.State.EXITED
            } else if (item.stateCheckCommand.isBlank()) {
                uncheckedStates[item.id] ?: false
            } else {
                val exitCode = DashboardCommands.check(item.stateCheckCommand)
                if (exitCode < 0) return builder.setStatus(Control.STATUS_ERROR).build()
                exitCode == 0
            }
            statusText = getString(if (checked) R.string.xray_state_on else R.string.xray_state_off)
            builder.setControlTemplate(ToggleTemplate(item.id, ControlButton(checked, statusText)))
        } else {
            builder.setControlTemplate(StatelessTemplate(item.id))
        }
        if (!item.isCore && item.summaryCommand.isNotBlank()) {
            builder.setSubtitle(DashboardCommands.summary(item.summaryCommand))
        }
        return builder.setStatus(Control.STATUS_OK).setStatusText(statusText).build()
    }

    private fun isDisabled(item: DashboardItem): Boolean =
        !item.isCore && item.disableConditionCommand.isNotBlank() &&
            DashboardCommands.check(item.disableConditionCommand) == 0

    private class Entry(val item: DashboardItem) {
        val isSwitch = item.isCore || DashboardItem.TYPE_SWITCH.equals(item.type, ignoreCase = true)
    }

    companion object {
        private val updates = MutableStateFlow(0L)

        @JvmStatic
        fun refresh() {
            updates.update { it + 1 }
        }
    }
}
