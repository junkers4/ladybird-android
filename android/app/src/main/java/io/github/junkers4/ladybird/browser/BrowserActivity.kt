package io.github.junkers4.ladybird.browser

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import io.github.junkers4.ladybird.LadybirdApplication
import io.github.junkers4.ladybird.R
import io.github.junkers4.ladybird.core.engine.ApplicationActions
import io.github.junkers4.ladybird.core.engine.ApplicationMenuUpdate
import io.github.junkers4.ladybird.core.engine.EngineEvent
import io.github.junkers4.ladybird.core.engine.EngineMenu
import io.github.junkers4.ladybird.core.engine.FinishedDownload
import io.github.junkers4.ladybird.core.engine.OpenUrlRequest
import io.github.junkers4.ladybird.core.engine.ViewEvent
import io.github.junkers4.ladybird.core.input.InputPrivacy
import io.github.junkers4.ladybird.core.settings.BrowserSettings
import io.github.junkers4.ladybird.core.tabs.TabModel
import io.github.junkers4.ladybird.core.ui.FindResultText
import io.github.junkers4.ladybird.core.ui.LadybirdPages
import io.github.junkers4.ladybird.core.ui.SecurityLevel
import io.github.junkers4.ladybird.core.ui.ThemePolicy
import io.github.junkers4.ladybird.core.ui.ToolbarState
import io.github.junkers4.ladybird.core.url.HttpsOnlyPolicy
import io.github.junkers4.ladybird.core.url.Omnibox
import io.github.junkers4.ladybird.core.url.OmniboxResult
import io.github.junkers4.ladybird.core.url.TrackingParameters
import io.github.junkers4.ladybird.core.url.UrlParts
import io.github.junkers4.ladybird.databinding.ActivityBrowserBinding
import io.github.junkers4.ladybird.engine.Engine
import io.github.junkers4.ladybird.engine.NativeEngine
import io.github.junkers4.ladybird.settings.SettingsActivity

/**
 * The browser window, laid out like Ladybird's Qt window (UI-001): back, forward, reload, location field,
 * tab count and the overflow menu with Ladybird's own menus (UI-005).
 */
class BrowserActivity : AppCompatActivity(), Engine.Observer {
    private lateinit var binding: ActivityBrowserBinding
    private lateinit var dialogs: BrowserDialogs
    private val tabs = TabModel()
    private val views = mutableMapOf<Long, WebContentView>()
    private val toolbarStates = mutableMapOf<Long, ToolbarState>()
    private var settings = BrowserSettings()
    private lateinit var httpsOnly: HttpsOnlyPolicy
    private var pendingIntent: Intent? = null
    private var fullscreen = false
    private var filePickerViewId = 0L
    private var pendingMenuName: String? = null

    private val app get() = application as LadybirdApplication
    private val activeTab get() = tabs.active
    private val activeViewId get() = activeTab?.viewId ?: 0L

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val viewId = filePickerViewId
        if (viewId == 0L) return@registerForActivityResult
        val names = mutableListOf<String>()
        val fds = mutableListOf<Int>()
        for (uri in uris) {
            val descriptor = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: continue
            names += uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            fds += descriptor.detachFd()
        }
        NativeEngine.nativeFilePickerClosed(viewId, names.toTypedArray(), fds.toIntArray())
        filePickerViewId = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, if (fullscreen) 0 else bars.top, bars.right, if (fullscreen) 0 else bars.bottom)
            insets
        }

        settings = app.settings.load()
        httpsOnly = HttpsOnlyPolicy(settings.httpsOnly, settings.httpsOnlyExceptions)
        dialogs = BrowserDialogs(this)
        setUpToolbar()
        setUpFindBar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        pendingIntent = intent
        Engine.addObserver(this)
    }

    override fun onDestroy() {
        Engine.removeObserver(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Engine.isReady) handleIntent(intent) else pendingIntent = intent
    }

    override fun onResume() {
        super.onResume()
        val updated = app.settings.load()
        if (updated != settings) {
            settings = updated
            httpsOnly.enabled = settings.httpsOnly
            Engine.applyPolicy(settings)
            updateWindowSecurity()
        }
        views[activeViewId]?.let { NativeEngine.nativeSetVisible(it.viewId, true) }
    }

    override fun onPause() {
        super.onPause()
        views[activeViewId]?.let { if (Engine.isReady) NativeEngine.nativeSetVisible(it.viewId, false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDarkMode()
    }

    // --- Engine events --------------------------------------------------------------------------

    override fun onEngineStateChanged(state: Engine.State, message: String?) {
        binding.engineStatus.isVisible = state != Engine.State.READY
        binding.engineStatus.text = when (state) {
            Engine.State.NOT_STARTED, Engine.State.STARTING -> getString(R.string.engine_starting)
            Engine.State.READY -> ""
            Engine.State.UNAVAILABLE -> getString(R.string.engine_unavailable)
            Engine.State.FAILED -> getString(R.string.engine_failed, message ?: "")
        }
        if (state == Engine.State.READY && tabs.all.isEmpty()) {
            val intent = pendingIntent
            pendingIntent = null
            if (intent == null || !handleIntent(intent)) openTab(LadybirdPages.NEW_TAB, isPrivate = false)
        }
    }

    override fun onEngineEvent(event: Int, text: String?) {
        when (event) {
            EngineEvent.OPEN_URL_IN_NEW_TAB -> OpenUrlRequest.parse(text ?: "")?.let { openTab(it.url, it.private, activate = it.activate) }
            EngineEvent.DISPLAY_ERROR -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            EngineEvent.DOWNLOAD_CONFIRMATION -> FinishedDownload.parse(text ?: "")?.let { DownloadPublisher.publish(this, it) }
            EngineEvent.MENU_CHANGED -> ApplicationMenuUpdate.parse(text ?: "")?.let { update ->
                if (update.name == pendingMenuName) {
                    pendingMenuName = null
                    showEngineMenu(update.menu) { index -> NativeEngine.nativeActivateApplicationMenuItem(update.name, index) }
                }
            }
        }
    }

    override fun onViewEvent(viewId: Long, event: Int, text: String?, extra: String?, i1: Long, i2: Long) {
        if (event == ViewEvent.NEW_TAB) {
            adoptTab(viewId, activate = i1 != 0L, openerViewId = i2)
            return
        }
        val tab = tabs.findByView(viewId) ?: return
        val state = toolbarStates[viewId] ?: ToolbarState(isPrivate = tab.isPrivate)
        val isActive = viewId == activeViewId
        when (event) {
            ViewEvent.LOAD_START -> {
                val url = text ?: ""
                if (enforceHttpsOnly(viewId, url)) return
                toolbarStates[viewId] = state.onLoadStart(url)
                tabs.updatePage(viewId, url = url)
            }
            ViewEvent.LOAD_FINISH -> toolbarStates[viewId] = state.onLoadFinish(text ?: state.url)
            ViewEvent.URL_CHANGED -> {
                toolbarStates[viewId] = state.onUrlChanged(text ?: "")
                tabs.updatePage(viewId, url = text)
            }
            ViewEvent.TITLE_CHANGED -> {
                toolbarStates[viewId] = state.onTitleChanged(text ?: "")
                tabs.updatePage(viewId, title = text)
            }
            ViewEvent.LOADING_STATE_CHANGED -> toolbarStates[viewId] = state.onLoadingState(i1 != 0L)
            ViewEvent.NAVIGATION_STATE_CHANGED -> toolbarStates[viewId] = state.onNavigationState(i1 != 0L, i2 != 0L)
            ViewEvent.REQUEST_ALERT -> dialogs.alert(viewId, text ?: "")
            ViewEvent.REQUEST_CONFIRM -> dialogs.confirm(viewId, text ?: "")
            ViewEvent.REQUEST_PROMPT -> dialogs.prompt(viewId, text ?: "", extra ?: "")
            ViewEvent.REQUEST_ACCEPT_DIALOG, ViewEvent.REQUEST_DISMISS_DIALOG -> dialogs.dismissWebDialog(viewId, accept = event == ViewEvent.REQUEST_ACCEPT_DIALOG)
            ViewEvent.REQUEST_SELECT_DROPDOWN -> dialogs.selectDropdown(viewId, text ?: "[]")
            ViewEvent.REQUEST_CONTEXT_MENU -> EngineMenu.parse(text ?: "")?.let { menu ->
                showEngineMenu(menu) { index -> NativeEngine.nativeContextMenuItemSelected(viewId, index) }
            }
            ViewEvent.REQUEST_FILE_PICKER -> {
                filePickerViewId = viewId
                filePicker.launch(arrayOf("*/*"))
            }
            ViewEvent.FIND_IN_PAGE_RESULT -> binding.findCount.text = FindResultText.format(i1, i2)
            ViewEvent.ENTER_FULLSCREEN -> if (isActive) setFullscreen(true)
            ViewEvent.EXIT_FULLSCREEN -> if (isActive) setFullscreen(false)
            ViewEvent.INPUT_METHOD_STATE_CHANGED -> views[viewId]?.setInputMethodEnabled(i1 != 0L)
            ViewEvent.REQUEST_EXTERNAL_URL -> if (isActive) dialogs.externalUrl(text ?: "") { url -> loadInActiveTab(url) }
            ViewEvent.REQUEST_CLOSE -> closeTab(tab.id)
            ViewEvent.REQUEST_ACTIVATE -> activateTab(tab.id)
            ViewEvent.WEB_CONTENT_CRASHED -> if (isActive) Toast.makeText(this, R.string.page_crashed, Toast.LENGTH_LONG).show()
            ViewEvent.LINK_HOVER -> if (isActive) binding.linkPreview.apply { this.text = text; isVisible = true }
            ViewEvent.LINK_UNHOVER -> binding.linkPreview.isVisible = false
        }
        if (isActive) renderToolbar()
    }

    // --- Tabs -----------------------------------------------------------------------------------

    private fun openTab(url: String, isPrivate: Boolean, activate: Boolean = true, openerId: Long? = null) {
        if (!Engine.isReady) return
        val viewId = NativeEngine.nativeCreateView(isPrivate)
        val tab = tabs.open(viewId, isPrivate, url, activate, openerId)
        createView(viewId, isPrivate)
        if (url.isNotEmpty()) NativeEngine.nativeLoadUrl(viewId, url)
        if (activate) activateTab(tab.id) else renderToolbar()
    }

    /** A view the engine created itself (window.open, target=_blank). */
    private fun adoptTab(viewId: Long, activate: Boolean, openerViewId: Long) {
        val opener = tabs.findByView(openerViewId)
        val isPrivate = opener?.isPrivate ?: false
        val tab = tabs.open(viewId, isPrivate, "", activate, opener?.id)
        createView(viewId, isPrivate)
        if (activate) activateTab(tab.id) else renderToolbar()
    }

    private fun createView(viewId: Long, isPrivate: Boolean) {
        val view = WebContentView(this, viewId, isPrivate) { settings.incognitoKeyboard }
        views[viewId] = view
        toolbarStates[viewId] = ToolbarState(isPrivate = isPrivate)
        view.isVisible = false
        binding.content.addView(view)
        NativeEngine.nativeSetDarkMode(viewId, isDark())
    }

    private fun activateTab(tabId: Long) {
        val previous = activeViewId
        tabs.activate(tabId)
        val current = activeViewId
        if (previous != current && previous != 0L) {
            views[previous]?.isVisible = false
            NativeEngine.nativeSetVisible(previous, false)
        }
        views[current]?.let {
            it.isVisible = true
            NativeEngine.nativeSetVisible(current, true)
            NativeEngine.nativeSetActiveView(current)
        }
        updateWindowSecurity()
        renderToolbar()
    }

    private fun closeTab(tabId: Long) {
        val tab = tabs.close(tabId) ?: return
        views.remove(tab.viewId)?.let { binding.content.removeView(it) }
        toolbarStates.remove(tab.viewId)
        NativeEngine.nativeDestroyView(tab.viewId)
        val next = tabs.activeTabId
        if (next != null) activateTab(next) else if (Engine.isReady) openTab(LadybirdPages.NEW_TAB, isPrivate = false)
    }

    private fun handleIntent(intent: Intent): Boolean {
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return false
        val target = when (val result = Omnibox(settings.searchEngine).resolve(url)) {
            is OmniboxResult.Navigate -> result.url
            is OmniboxResult.Search -> result.url
            is OmniboxResult.Rejected -> return false
        }
        openTab(prepareUrl(target), isPrivate = false)
        return true
    }

    // --- Navigation -----------------------------------------------------------------------------

    /** Tracking-parameter stripping (SEC-005) and HTTPS-Only (SEC-004) for URLs the app loads. */
    private fun prepareUrl(url: String): String {
        val stripped = if (settings.stripTrackingParameters) TrackingParameters.strip(url) else url
        return when (val decision = httpsOnly.evaluate(stripped)) {
            is HttpsOnlyPolicy.Decision.Proceed -> decision.url
            is HttpsOnlyPolicy.Decision.Upgrade -> decision.url
        }
    }

    /** Page-initiated http:// navigations are restarted over https:// (SEC-004). */
    private fun enforceHttpsOnly(viewId: Long, url: String): Boolean {
        val decision = httpsOnly.evaluate(url)
        if (decision !is HttpsOnlyPolicy.Decision.Upgrade) return false
        NativeEngine.nativeStop(viewId)
        NativeEngine.nativeLoadUrl(viewId, decision.url)
        return true
    }

    private fun loadInActiveTab(url: String) {
        val viewId = activeViewId.takeIf { it != 0L } ?: return openTab(prepareUrl(url), isPrivate = false)
        NativeEngine.nativeLoadUrl(viewId, prepareUrl(url))
        views[viewId]?.requestFocus()
    }

    private fun submitLocation(text: String) {
        when (val result = Omnibox(settings.searchEngine).resolve(text)) {
            is OmniboxResult.Navigate -> loadInActiveTab(result.url)
            is OmniboxResult.Search -> loadInActiveTab(result.url)
            is OmniboxResult.Rejected -> if (result.reason == OmniboxResult.Reason.DANGEROUS_SCHEME)
                Toast.makeText(this, R.string.dangerous_scheme, Toast.LENGTH_SHORT).show()
        }
        hideKeyboard()
    }

    private fun goBack() {
        when {
            binding.findBar.isVisible -> closeFindBar()
            fullscreen -> NativeEngine.nativeExitFullscreen(activeViewId)
            toolbarStates[activeViewId]?.canGoBack == true -> NativeEngine.nativeTraverseHistory(activeViewId, -1)
            activeTab?.openerId != null -> activeTab?.let { closeTab(it.id) }
            else -> moveTaskToBack(true)
        }
    }

    // --- Toolbar --------------------------------------------------------------------------------

    private fun setUpToolbar() {
        binding.back.setOnClickListener { NativeEngine.nativeTraverseHistory(activeViewId, -1) }
        binding.forward.setOnClickListener { NativeEngine.nativeTraverseHistory(activeViewId, 1) }
        binding.reload.setOnClickListener {
            if (toolbarStates[activeViewId]?.isLoading == true) NativeEngine.nativeStop(activeViewId) else NativeEngine.nativeReload(activeViewId)
        }
        binding.tabCount.setOnClickListener { showTabSwitcher() }
        binding.menu.setOnClickListener { showMainMenu(it) }
        binding.shields.setOnClickListener { showShields() }
        binding.location.setOnEditorActionListener { view, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                submitLocation(view.text.toString())
                true
            } else {
                false
            }
        }
        binding.location.setOnFocusChangeListener { _, focused -> if (focused) binding.location.selectAll() }
    }

    private fun renderToolbar() {
        val tab = activeTab
        val state = (toolbarStates[activeViewId] ?: ToolbarState()).copy(
            tabCount = tabs.all.size,
            isPrivate = tab?.isPrivate ?: false,
            shieldsUp = settings.contentBlocking && currentHost()?.let { it !in settings.shieldsDownSites } != false,
        )
        binding.back.isEnabled = state.canGoBack
        binding.forward.isEnabled = state.canGoForward
        binding.reload.setImageResource(if (state.reloadShowsStop) R.drawable.ic_stop else R.drawable.ic_reload)
        binding.reload.contentDescription = getString(if (state.reloadShowsStop) R.string.stop else R.string.reload)
        if (!binding.location.hasFocus()) binding.location.setText(state.locationText)
        binding.location.imeOptions = InputPrivacy.imeOptions(EditorInfo.IME_ACTION_GO, state.isPrivate, settings.incognitoKeyboard)
        binding.security.setImageResource(
            when (state.security) {
                SecurityLevel.SECURE -> R.drawable.ic_lock
                SecurityLevel.INSECURE -> R.drawable.ic_warning
                SecurityLevel.INTERNAL, SecurityLevel.NONE -> R.drawable.ic_info
            },
        )
        binding.shields.alpha = if (state.shieldsUp) 1f else 0.4f
        binding.tabCount.text = state.tabCountLabel
        binding.progress.isVisible = state.isLoading
        binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, if (state.isPrivate) R.color.private_toolbar else R.color.toolbar))
    }

    private fun currentHost(): String? = UrlParts.parse(toolbarStates[activeViewId]?.url ?: "")?.host

    // --- Menus ----------------------------------------------------------------------------------

    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.new_tab -> openTab(LadybirdPages.NEW_TAB, isPrivate = false)
                R.id.new_private_tab -> openTab(LadybirdPages.NEW_TAB, isPrivate = true)
                R.id.reopen_closed_tab -> tabs.popClosed()?.let { openTab(it.url, it.isPrivate) }
                R.id.find_in_page -> openFindBar()
                R.id.share -> shareCurrentPage()
                R.id.bookmarks -> openTab(LadybirdPages.BOOKMARKS, activeTab?.isPrivate ?: false)
                R.id.history -> openTab(LadybirdPages.HISTORY, isPrivate = false)
                R.id.downloads -> openTab(LadybirdPages.DOWNLOADS, isPrivate = false)
                R.id.view_source -> NativeEngine.nativeActivateApplicationAction(ApplicationActions.VIEW_SOURCE)
                R.id.menu_zoom -> requestEngineMenu("zoom")
                R.id.menu_color_scheme -> requestEngineMenu("color_scheme")
                R.id.menu_contrast -> requestEngineMenu("contrast")
                R.id.menu_motion -> requestEngineMenu("motion")
                R.id.menu_inspect -> requestEngineMenu("inspect")
                R.id.menu_debug -> requestEngineMenu("debug")
                R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.ladybird_settings -> openTab(LadybirdPages.SETTINGS, isPrivate = false)
                R.id.about -> openTab(LadybirdPages.ABOUT, isPrivate = false)
                R.id.close_private_tabs -> tabs.privateTabs.map { it.id }.forEach(::closeTab)
                R.id.quit -> quit()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.menu.findItem(R.id.reopen_closed_tab)?.isEnabled = tabs.closedCount > 0
        popup.menu.findItem(R.id.close_private_tabs)?.isVisible = tabs.privateTabs.isNotEmpty()
        popup.show()
    }

    private fun requestEngineMenu(name: String) {
        pendingMenuName = name
        NativeEngine.nativeRequestApplicationMenu(name)
    }

    private fun showEngineMenu(menu: EngineMenu, onSelected: (Int) -> Unit) = dialogs.engineMenu(menu, onSelected)

    private fun showTabSwitcher() {
        dialogs.tabSwitcher(
            tabs = tabs.all,
            activeTabId = tabs.activeTabId,
            onSelect = ::activateTab,
            onClose = ::closeTab,
            onNewTab = { isPrivate -> openTab(LadybirdPages.NEW_TAB, isPrivate) },
        )
    }

    private fun showShields() {
        val host = currentHost() ?: return
        val enabled = host !in settings.shieldsDownSites
        dialogs.shields(host, enabled, settings.contentBlocking) { up ->
            settings = app.settings.update { current ->
                current.copy(shieldsDownSites = if (up) current.shieldsDownSites - host else current.shieldsDownSites + host)
            }
            Engine.applyPolicy(settings)
            NativeEngine.nativeReload(activeViewId)
            renderToolbar()
        }
    }

    private fun shareCurrentPage() {
        val url = toolbarStates[activeViewId]?.url?.takeIf { it.startsWith("http") } ?: return
        val shared = if (settings.stripTrackingParameters) TrackingParameters.strip(url) else url
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared), null))
    }

    /** "Quit" closes everything; with clear-on-exit, browsing data goes too (SEC-015). */
    private fun quit() {
        tabs.all.map { it.id }.forEach { id -> tabs.close(id)?.let { tab -> NativeEngine.nativeDestroyView(tab.viewId) } }
        if (settings.clearDataOnExit) BrowsingData.clear(this)
        finishAndRemoveTask()
    }

    // --- Find bar -------------------------------------------------------------------------------

    private fun setUpFindBar() {
        binding.findQuery.setOnEditorActionListener { view, _, _ ->
            NativeEngine.nativeFindInPage(activeViewId, view.text.toString(), false)
            true
        }
        binding.findNext.setOnClickListener { NativeEngine.nativeFindNext(activeViewId, true) }
        binding.findPrevious.setOnClickListener { NativeEngine.nativeFindNext(activeViewId, false) }
        binding.findClose.setOnClickListener { closeFindBar() }
    }

    private fun openFindBar() {
        binding.findBar.isVisible = true
        binding.findCount.text = ""
        binding.findQuery.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.findQuery, 0)
    }

    private fun closeFindBar() {
        NativeEngine.nativeFindInPage(activeViewId, "", false)
        binding.findBar.isVisible = false
        hideKeyboard()
    }

    // --- Window ---------------------------------------------------------------------------------

    /** FLAG_SECURE for private tabs and when screenshot protection is on (SEC-013, SEC-017). */
    private fun updateWindowSecurity() {
        if (InputPrivacy.secureWindow(activeTab?.isPrivate ?: false, settings.screenshotProtection)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Element fullscreen hides the toolbar and the system bars (UI-015). */
    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        binding.toolbar.isVisible = !enabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun isDark() = ThemePolicy.useDark(settings.theme, (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    private fun applyDarkMode() {
        val dark = isDark()
        for (viewId in views.keys) NativeEngine.nativeSetDarkMode(viewId, dark)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.location.clearFocus()
    }
}
