package io.github.junkers4.ladybird.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.junkers4.ladybird.R
import io.github.junkers4.ladybird.core.engine.DialogKind
import io.github.junkers4.ladybird.core.engine.EngineMenu
import io.github.junkers4.ladybird.core.engine.SelectItem
import io.github.junkers4.ladybird.core.tabs.TabModel
import io.github.junkers4.ladybird.core.url.ExternalUrlPolicy
import io.github.junkers4.ladybird.engine.NativeEngine

/** Native dialogs for web content (UI-006, UI-007), tabs (UI-002), shields (UI-016) and external apps (SEC-008). */
class BrowserDialogs(private val activity: AppCompatActivity) {
    private val webDialogs = mutableMapOf<Long, Pair<AlertDialog, () -> Unit>>()

    private fun builder() = MaterialAlertDialogBuilder(activity)

    fun alert(viewId: Long, message: String) {
        val dialog = builder()
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { closed(viewId) { NativeEngine.nativeDialogClosed(viewId, DialogKind.ALERT, true, null) } }
            .create()
        show(viewId, dialog) { NativeEngine.nativeDialogClosed(viewId, DialogKind.ALERT, true, null) }
    }

    fun confirm(viewId: Long, message: String) {
        var accepted = false
        val dialog = builder()
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> accepted = true }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { closed(viewId) { NativeEngine.nativeDialogClosed(viewId, DialogKind.CONFIRM, accepted, null) } }
            .create()
        show(viewId, dialog) { NativeEngine.nativeDialogClosed(viewId, DialogKind.CONFIRM, accepted, null) }
    }

    fun prompt(viewId: Long, message: String, defaultValue: String) {
        val input = EditText(activity).apply { setText(defaultValue) }
        var accepted = false
        val dialog = builder()
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> accepted = true }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                closed(viewId) { NativeEngine.nativeDialogClosed(viewId, DialogKind.PROMPT, accepted, if (accepted) input.text.toString() else null) }
            }
            .create()
        show(viewId, dialog) { NativeEngine.nativeDialogClosed(viewId, DialogKind.PROMPT, accepted, if (accepted) input.text.toString() else null) }
    }

    /** The page (or WebDriver) closed the dialog itself. */
    fun dismissWebDialog(viewId: Long, accept: Boolean) {
        val (dialog, _) = webDialogs[viewId] ?: return
        if (accept) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick() else dialog.dismiss()
    }

    private fun show(viewId: Long, dialog: AlertDialog, onClose: () -> Unit) {
        webDialogs[viewId] = dialog to onClose
        dialog.show()
    }

    private fun closed(viewId: Long, report: () -> Unit) {
        if (webDialogs.remove(viewId) != null) report()
    }

    fun selectDropdown(viewId: Long, json: String) {
        val items = SelectItem.parseList(json) ?: return NativeEngine.nativeSelectDropdownClosed(viewId, -1)
        val entries = SelectItem.flatten(items)
        val labels = entries.map { it.second }.toTypedArray()
        val selected = entries.indexOfFirst { it.first?.selected == true }
        var chosen = -1L
        builder()
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val option = entries[which].first
                if (option != null && !option.disabled) {
                    chosen = option.id
                    dialog.dismiss()
                }
            }
            .setOnDismissListener { NativeEngine.nativeSelectDropdownClosed(viewId, chosen) }
            .show()
    }

    /** Renders a LibWebView menu (context menu or application menu) as a list. */
    fun engineMenu(menu: EngineMenu, onSelected: (Int) -> Unit, title: String = menu.title) {
        val rows = mutableListOf<Pair<String, EngineMenu.Item>>()
        fun collect(items: List<EngineMenu.Item>, indent: String) {
            for (item in items) when (item) {
                is EngineMenu.Item.Action -> rows += (indent + (if (item.checkable) (if (item.checked) "✓ " else "   ") else "") + item.text) to item
                is EngineMenu.Item.Submenu -> {
                    rows += (indent + item.title) to item
                    collect(item.items, "$indent    ")
                }
                EngineMenu.Item.Separator -> Unit
            }
        }
        collect(menu.visibleItems(), "")
        var selectedIndex = -1
        builder()
            .setTitle(title.replace("&", ""))
            .setItems(rows.map { it.first.replace("&", "") }.toTypedArray()) { _, which ->
                val action = rows[which].second as? EngineMenu.Item.Action
                if (action != null && action.enabled) selectedIndex = action.index
            }
            .setOnDismissListener { onSelected(selectedIndex) }
            .show()
    }

    fun tabSwitcher(tabs: List<TabModel.Tab>, activeTabId: Long?, onSelect: (Long) -> Unit, onClose: (Long) -> Unit, onNewTab: (Boolean) -> Unit) {
        val ordered = tabs.filter { !it.isPrivate } + tabs.filter { it.isPrivate }
        val labels = ordered.map { tab ->
            val marker = if (tab.id == activeTabId) "● " else ""
            val privateMarker = if (tab.isPrivate) activity.getString(R.string.private_marker) + " " else ""
            "$marker$privateMarker${tab.displayTitle}"
        }.toTypedArray()
        builder()
            .setTitle(activity.getString(R.string.tabs_title, tabs.size))
            .setItems(labels) { _, which -> onSelect(ordered[which].id) }
            .setPositiveButton(R.string.new_tab) { _, _ -> onNewTab(false) }
            .setNeutralButton(R.string.new_private_tab) { _, _ -> onNewTab(true) }
            .setNegativeButton(R.string.close_current_tab) { _, _ -> activeTabId?.let(onClose) }
            .show()
    }

    fun shields(host: String, enabled: Boolean, blockingOn: Boolean, onToggle: (Boolean) -> Unit) {
        val message = if (blockingOn) activity.getString(if (enabled) R.string.shields_up_for else R.string.shields_down_for, host) else activity.getString(R.string.shields_globally_off)
        val dialog = builder()
            .setTitle(R.string.shields)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        if (blockingOn) dialog.setPositiveButton(if (enabled) R.string.shields_turn_off else R.string.shields_turn_on) { _, _ -> onToggle(!enabled) }
        dialog.show()
    }

    /** SEC-008: nothing opens another app without confirmation, and intents are sanitized first. */
    fun externalUrl(url: String, loadInBrowser: (String) -> Unit) {
        when (val action = ExternalUrlPolicy.decide(url)) {
            ExternalUrlPolicy.Action.LoadInBrowser -> loadInBrowser(url)
            is ExternalUrlPolicy.Action.Block -> Toast.makeText(activity, activity.getString(R.string.external_blocked, action.reason), Toast.LENGTH_LONG).show()
            is ExternalUrlPolicy.Action.Fallback -> builder()
                .setMessage(activity.getString(R.string.external_fallback, action.url))
                .setPositiveButton(R.string.open) { _, _ -> loadInBrowser(action.url) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            is ExternalUrlPolicy.Action.ConfirmThenOpen -> builder()
                .setTitle(R.string.external_title)
                .setMessage(activity.getString(R.string.external_message, action.request.dataUri ?: action.request.packageName ?: url))
                .setPositiveButton(R.string.open) { _, _ -> launch(action.request) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launch(request: ExternalUrlPolicy.ExternalRequest) {
        val intent = Intent(request.action).apply {
            request.dataUri?.let { data = Uri.parse(it) }
            request.packageName?.let { setPackage(it) }
            request.categories.forEach { addCategory(it) }
            // Never pass on grants or let the target start in our task.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            selector = null
            component = null
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.external_no_app, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(activity, R.string.external_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
