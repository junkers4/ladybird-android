/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.serenityos.ladybird.databinding.ActivityMainBinding

class LadybirdActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resourceDir: String
    private lateinit var view: WebView
    private lateinit var urlEditText: EditText
    private lateinit var settings: AppSettings
    private lateinit var bookmarks: BookmarksStore
    private lateinit var history: HistoryStore
    private lateinit var timeTracker: TimeLimitTracker
    private lateinit var netController: NetworkController
    private var limitDialogShowing = false
    // Current chrome colours, so a mode switch can animate from them.
    private var currentBarColor = 0
    private var currentFieldColor = 0
    private var themeAnimator: ValueAnimator? = null
    private var timerService = TimerExecutorService()
    private var nativeInitialized = false
    private var viewInitialized = false
    private var isLoading = false
    private var hasRenderedContent = false
    private var startupOverlayDismissed = false
    private var startupOverlayShownAt = 0L
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    // A proxied compartment (Tor/I2P) is "ready" only once its daemon has
    // bootstrapped. Until then we refuse to navigate so no request can leak out
    // directly; an attempted navigation is queued here and run once it's ready.
    private var compartmentReady = true
    private var pendingNavigation: String? = null
    private val tabs = TabStore()

    private fun isNewTabUrl(url: String): Boolean =
        url == NEW_TAB_LOAD_URL || url == AppSettings.DEFAULT_HOME || url.startsWith("data:text/html")

    // Called from native code (by name): binds the Compositor Android service
    // with the service-side end of a socketpair created in native code.
    fun bindCompositorService(ipcFd: Int) {
        Log.i("Ladybird", "Binding Compositor service with IPC fd $ipcFd")
        val connector = LadybirdServiceConnection(ipcFd, resourceDir)
        connector.onConnect = {
            Log.i("Ladybird", "Compositor service connected")
            nativeCompositorServiceConnected()
        }
        val bound = bindService(
            Intent(this, CompositorService::class.java),
            connector,
            Context.BIND_AUTO_CREATE
        )
        Log.i("Ladybird", "bindService(CompositorService) returned $bound")
    }

    private external fun nativeCompositorServiceConnected()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            resourceDir = TransferAssets.transferAssets(this)
        } catch (exception: Exception) {
            Log.e("Ladybird", "Failed to prepare runtime assets", exception)
            finish()
            return
        }
        val userDir = applicationContext.getExternalFilesDir(null)!!.absolutePath
        initNativeCode(resourceDir, "Ladybird", timerService, userDir)
        nativeInitialized = true

        settings = AppSettings(this)
        bookmarks = BookmarksStore(this)
        history = HistoryStore(this)
        timeTracker = TimeLimitTracker(settings) { host, mins -> onSiteLimitReached(host, mins) }
        netController = NetworkController(this, keepI2pRunning = { settings.i2pKeepRunning })

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Edge-to-edge like Chromium: the page draws under the transparent
        // gesture bar; only the app bar is inset below the status bar. The IME
        // inset keeps the find bar/omnibox visible above the keyboard.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            binding.appBar.setPadding(0, statusBars.top, 0, 0)
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            root.setPadding(0, 0, 0, ime.bottom)
            insets
        }
        urlEditText = binding.urlEditText
        view = binding.webView
        startStartupOverlayAnimation()

        view.onLoadStart = { url: String, _ ->
            Log.i("LadybirdLoad", "onLoadStart: $url")
            setLoading(true)
            currentUrl = url
            trackTime(url)
            updateNewTabOverlay(url)
            if (!urlEditText.hasFocus())
                urlEditText.setText(if (isNewTabUrl(url)) "" else url, TextView.BufferType.EDITABLE)
        }
        view.onLoadFinish = { url: String ->
            Log.i("LadybirdLoad", "onLoadFinish: $url")
            currentUrl = url
            tabs.active().url = url
            setLoading(false)
            updateNewTabOverlay(url)
            injectGoogleSorryPageFixesIfNeeded(url)
            if (!isNewTabUrl(url) && settings.saveHistory)
                history.record(url, currentTitle.ifBlank { url })
            // Nudge a single repaint once load settles; do NOT spam
            // setViewportGeometry on every load-finish (Google/SPAs trigger many
            // of these per click) — the engine already has the correct geometry
            // from onSizeChanged and per-rebind initialize_client.
            view.postInvalidateOnAnimation()
        }
        view.onUrlChange = { url: String ->
            Log.i("LadybirdLoad", "onUrlChange: $url")
            currentUrl = url
            tabs.active().url = url
            trackTime(url)
            updateNewTabOverlay(url)
            injectGoogleSorryPageFixesIfNeeded(url)
            if (!urlEditText.hasFocus())
                urlEditText.setText(if (isNewTabUrl(url)) "" else url, TextView.BufferType.EDITABLE)
        }
        view.onTitleChange = { title: String ->
            currentTitle = title
            tabs.active().title = title
        }
        view.onFindInPage = { current: Int, total: Int ->
            updateFindCounter(current, total)
        }
        view.onLinkHover = { url: String? ->
            if (!url.isNullOrEmpty())
                Log.d("Ladybird", "Hover: $url")
        }
        view.onContentReady = {
            Log.i("LadybirdLoad", "onContentReady")
            hasRenderedContent = true
            if (isLoading)
                setLoading(false)
            injectGoogleSorryPageFixesIfNeeded(currentUrl)
            hideStartupOverlayIfNeeded()
        }
        view.onWebContentCrash = {
            Log.e("LadybirdLoad", "onWebContentCrash")
            setLoading(false)
            applySettingsToView()
            view.syncViewport()
            // Suppress the spurious crash signal that fires once during initial
            // WebContent service bind, before any content has rendered.
            if (hasRenderedContent) {
                Snackbar.make(binding.root, R.string.browser_webcontent_crashed, Snackbar.LENGTH_LONG)
                    .setAction(R.string.browser_reload) {
                        if (currentUrl.isNotBlank()) view.loadURL(currentUrl)
                        else view.reload()
                    }
                    .show()
            }
        }
        view.onLongPress = { _, _ ->
            showPageContextMenu()
        }
        view.onSwipeRefresh = {
            if (currentUrl.isNotBlank()) view.loadURL(currentUrl) else view.reload()
        }
        view.setOnTouchListener { _, _ ->
            if (urlEditText.hasFocus())
                leaveOmniboxEditMode()
            false
        }

        urlEditText.setOnEditorActionListener { textView: TextView, actionId: Int, keyEvent: KeyEvent? ->
            val isImeSubmit = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH
            val isHardwareEnter = keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN
            if (isImeSubmit || isHardwareEnter) {
                navigateToInput(textView.text.toString())
                true
            } else {
                false
            }
        }
        binding.menuButton.setOnClickListener { showBrowserMenu() }
        binding.networkModeButton.setOnClickListener { showNetworkModeSwitcher() }
        binding.homeButton.setOnClickListener { navigateToInput(settings.homePage) }
        // Single-tab engine for now: "+" opens a fresh page (the native NTP
        // when the home page is the default about:newtab).
        binding.newTabButton.setOnClickListener { openNewTab() }
        binding.tabCountButton.setOnClickListener { showTabSwitcher() }
        updateTabCount()
        // The NTP shows just our logo — no centre search pill. The top omnibox
        // stays visible for searching; tapping anywhere on the empty NTP simply
        // focuses it.
        val ntpFocusHandler = View.OnClickListener {
            binding.urlBarCard.visibility = View.VISIBLE
            enterOmniboxEditMode()
        }
        binding.newTabOverlay.setOnClickListener(ntpFocusHandler)

        setupFindBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.findInPageBar.visibility == View.VISIBLE) {
                    hideFindBar()
                } else {
                    view.goBack()
                }
            }
        })

        view.initialize(resourceDir)
        viewInitialized = true
        applySettingsToView()
        applyNetworkTheme()
        // Defer the initial navigation until the WebView has been laid out so
        // the WebContent viewport is sized correctly before the first render.
        val initialTarget = intent.dataString ?: settings.homePage
        view.post { navigateToInput(initialTarget) }
    }

    override fun onResume() {
        super.onResume()
        if (viewInitialized) applySettingsToView()
        // Apply any cookie/cache clear requested from the history page (which has
        // no live web view of its own).
        if (viewInitialized) {
            if (settings.pendingClearCookiesSeconds >= 0) {
                view.clearCookies(settings.pendingClearCookiesSeconds)
                settings.pendingClearCookiesSeconds = -1
            }
            if (settings.pendingClearCache) {
                view.clearCache(); view.collectGarbage()
                settings.pendingClearCache = false
            }
        }
        if (::timeTracker.isInitialized) timeTracker.start()
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground — the keep-alive service (if any) is no longer needed.
        PlaybackService.stop(this)
    }

    override fun onStop() {
        // Going to the background or the screen locked: keep the process (and its
        // audio stream) alive only for sites the user allowed for background
        // playback. Everything else is left to the normal background lifecycle.
        if (viewInitialized && !isNewTabUrl(currentUrl) && settings.isBackgroundAudioAllowed(currentUrl)) {
            PlaybackService.start(this, settings.normalizeHost(currentUrl))
        } else {
            PlaybackService.stop(this)
        }
        super.onStop()
    }

    override fun onPause() {
        if (::timeTracker.isInitialized) timeTracker.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { navigateToInput(it) }
    }

    override fun onDestroy() {
        // "Clear data when the app closes": wipe history, cookies and cache on exit.
        if (settings.clearOnExit && isFinishing) {
            runCatching {
                history.clear()
                if (viewInitialized) {
                    view.clearCookies(0)
                    view.clearCache()
                }
            }
        }
        if (::netController.isInitialized)
            netController.shutdown()
        if (viewInitialized)
            view.dispose()
        if (nativeInitialized)
            disposeNativeCode()
        super.onDestroy()
    }

    private fun scheduleEventLoop() {
        mainExecutor.execute {
            execMainEventLoop()
        }
    }

    private fun applySettingsToView() {
        view.setPreferredColorScheme(settings.colorScheme.nativeValue)
        view.setUserAgent(settings.effectiveUserAgent)
        view.setNavigatorCompatibility(settings.navigatorCompatibility)
        view.setScriptingEnabled(settings.javascriptHelpersEnabled)
        view.setPinchZoomEnabled(settings.pinchZoomEnabled)
        view.setContentBlockingEnabled(settings.adBlockEnabled)
    }

    private fun navigateToInput(input: String) {
        val url = resolveTarget(input)
        // Don't let any request out until the active compartment's daemon is
        // ready — otherwise it would leak directly while Tor/I2P is still
        // connecting. Queue the navigation and run it the moment we're ready
        // (see onNetworkState Ready); the centre logo fills up meanwhile.
        if (!compartmentReady && !isNewTabUrl(url)) {
            // Silently queue it — the filling centre logo is the only cue; the
            // page opens by itself once the compartment is ready.
            pendingNavigation = input
            leaveOmniboxEditMode()
            return
        }
        // Refuse to open a site whose daily time limit is already used up.
        val host = settings.normalizeHost(url)
        if (!isNewTabUrl(url) && settings.isOverLimit(host)) {
            leaveOmniboxEditMode()
            onSiteLimitReached(host, settings.limitMinutesFor(host) ?: 0)
            return
        }
        urlEditText.setText(if (isNewTabUrl(url)) "" else url, TextView.BufferType.EDITABLE)
        leaveOmniboxEditMode()
        setLoading(true)
        updateNewTabOverlay(url)
        view.loadURL(url)
    }

    /** Feed the foreground host to the time tracker (blank for the new-tab page). */
    private fun trackTime(url: String) {
        if (::timeTracker.isInitialized)
            timeTracker.onHost(if (isNewTabUrl(url)) "" else url)
    }

    /** A host hit its daily limit: kick off the site (if showing) and inform the user. */
    private fun onSiteLimitReached(host: String, minutes: Int) {
        runOnUiThread {
            if (!isNewTabUrl(currentUrl) && settings.normalizeHost(currentUrl) == host) {
                urlEditText.setText("", TextView.BufferType.EDITABLE)
                currentUrl = AppSettings.DEFAULT_HOME
                updateNewTabOverlay(AppSettings.DEFAULT_HOME)
                view.loadURL(NEW_TAB_LOAD_URL)
            }
            if (!limitDialogShowing) {
                limitDialogShowing = true
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.limit_reached_title)
                    .setMessage(getString(R.string.limit_reached_message, minutes, host))
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { limitDialogShowing = false }
                    .show()
            }
        }
    }

    /** Resolve user/omnibox input or a stored home value to a loadable URL. */
    private fun resolveTarget(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return resolveTarget(settings.homePage)
        if (trimmed == AppSettings.DEFAULT_HOME) return NEW_TAB_LOAD_URL
        return normalizeUrlOrSearch(trimmed)
    }

    private fun normalizeUrlOrSearch(input: String): String {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty())
            return settings.homePage

        if (trimmedInput.startsWith("view-source:"))
            return trimmedInput

        val parsedUri = Uri.parse(trimmedInput)
        if (!parsedUri.scheme.isNullOrEmpty()) {
            // I2P has no TLS — eepsites are http-only. Downgrade https→http for any
            // .i2p host, and for *everything* while the I2P compartment is active,
            // so a typed/pasted https:// URL still loads instead of failing.
            if (i2pHttpOnly(trimmedInput) && trimmedInput.startsWith("https://", ignoreCase = true))
                return "http://" + trimmedInput.substring("https://".length)
            // HTTPS-only mode: quietly upgrade plain http:// to https:// (except
            // for I2P/onion/loopback hosts, which legitimately use http).
            if (settings.httpsOnly && trimmedInput.startsWith("http://", ignoreCase = true)
                && !i2pHttpOnly(trimmedInput) && !isHttpsExempt(trimmedInput))
                return "https://" + trimmedInput.substring("http://".length)
            return trimmedInput
        }

        val looksLikeUrl = !trimmedInput.contains(WHITESPACE_REGEX) &&
            (trimmedInput.contains(".") ||
                trimmedInput.equals("localhost", ignoreCase = true) ||
                trimmedInput.startsWith("[") && trimmedInput.contains("]"))

        if (looksLikeUrl) {
            // .i2p uses plain http, as does anything opened in the I2P compartment;
            // everything else defaults to https.
            val scheme = if (i2pHttpOnly(trimmedInput)) "http" else "https"
            return "$scheme://$trimmedInput"
        }

        return settings.searchEngine.urlFor(trimmedInput)
    }

    /** True if the host part of [input] is an I2P eepsite (…​.i2p). */
    private fun isI2pHost(input: String): Boolean {
        val host = input.substringAfter("://")
            .substringBefore("/").substringBefore("?").substringBefore(":")
            .trim().lowercase()
        return host.endsWith(".i2p")
    }

    /** True when [input] must be forced to plain http: either it's a .i2p eepsite
     *  or the I2P compartment is active (I2P carries no TLS, so https can't work). */
    private fun i2pHttpOnly(input: String): Boolean =
        settings.networkMode == NetworkMode.I2P || isI2pHost(input)

    /** Hosts that legitimately stay on http and must not be upgraded to https:
     *  loopback, link-local names, plain IPs and .onion hidden services. */
    private fun isHttpsExempt(input: String): Boolean {
        val host = input.substringAfter("://")
            .substringBefore("/").substringBefore("?").substringBefore(":")
            .trim().lowercase()
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".onion")) return true
        // Bare IPv4/IPv6 literals (e.g. 192.168.x.x, [::1]) — no cert to expect.
        if (host.startsWith("[")) return true
        return host.split(".").all { it.toIntOrNull() != null } && host.contains(".")
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        binding.loadingProgress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun updateNewTabOverlay(url: String) {
        val onNtp = isNewTabUrl(url)
        binding.newTabOverlay.visibility = if (onNtp) View.VISIBLE else View.GONE
        // Vanadium hides the toolbar omnibox on the New Tab page — the big
        // search pill in the content area takes its place. INVISIBLE (not
        // GONE) keeps the toolbar buttons anchored to the edges.
        // No centre search pill any more — keep the top omnibox visible on the
        // NTP so the user can always search, while the content area shows just
        // the logo.
        binding.urlBarCard.visibility = View.VISIBLE
        // The New Tab page is intentionally just the logo — no most-visited tiles.
    }

    private fun injectGoogleSorryPageFixesIfNeeded(url: String) {
        if (!url.startsWith("https://www.google.com/sorry/index")
            && !url.startsWith("https://www.google.com/search")
        )
            return

        view.runJavascript(
            """
            (() => {
              const pageText = document.body ? document.body.innerText : '';
              if (!location.href.includes('/sorry/')
                && !pageText.includes('Our systems have detected unusual traffic')
                && !document.querySelector('.g-recaptcha, [id*="recaptcha"], [class*="recaptcha"]')) {
                return;
              }
              if (document.getElementById('ladybird-google-sorry-fixes')) return;
              const style = document.createElement('style');
              style.id = 'ladybird-google-sorry-fixes';
              style.textContent = `
                html, body {
                  width: auto !important;
                  max-width: 100vw !important;
                  overflow-x: hidden !important;
                }
                body, body * {
                  max-width: 100% !important;
                  overflow-wrap: anywhere !important;
                  word-wrap: break-word !important;
                  word-break: break-all !important;
                }
                pre, code {
                  white-space: pre-wrap !important;
                  overflow-wrap: anywhere !important;
                  word-break: break-all !important;
                }
                iframe, .g-recaptcha, [id*="recaptcha"], [class*="recaptcha"] {
                  max-width: 100% !important;
                }
              `;
              (document.head || document.documentElement).appendChild(style);
            })();
            """.trimIndent()
        )
    }

    private fun startStartupOverlayAnimation() {
        startupOverlayShownAt = SystemClock.elapsedRealtime()
        binding.startupOverlay.alpha = 1f
        binding.startupOverlay.visibility = View.VISIBLE
    }

    private fun hideStartupOverlayIfNeeded() {
        if (startupOverlayDismissed)
            return
        startupOverlayDismissed = true
        val elapsed = SystemClock.elapsedRealtime() - startupOverlayShownAt
        val remainingDelay = (450L - elapsed).coerceAtLeast(0L)
        binding.startupOverlay.postDelayed({
            binding.startupOverlay.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction {
                    binding.startupOverlay.visibility = View.GONE
                }
                .start()
        }, remainingDelay)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
    }

    private fun enterOmniboxEditMode() {
        urlEditText.requestFocus()
        urlEditText.post {
            urlEditText.setSelection(urlEditText.text.length)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(urlEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun leaveOmniboxEditMode() {
        if (!urlEditText.hasFocus())
            return
        if (urlEditText.hasFocus())
            urlEditText.clearFocus()
        urlEditText.post {
            if (urlEditText.text.isNotEmpty())
                urlEditText.setSelection(0)
        }
        hideKeyboard()
        // Restore overlay if still on the New Tab page (user cancelled without navigating).
        updateNewTabOverlay(currentUrl)
    }

    private fun setupFindBar() {
        binding.findInPageEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                if (q.isNotEmpty()) view.findInPage(q, caseSensitive = false)
                else updateFindCounter(0, 0)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.findInPageEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                view.findNext(); true
            } else false
        }
        binding.findInPageNext.setOnClickListener { view.findNext() }
        binding.findInPagePrev.setOnClickListener { view.findPrevious() }
        binding.findInPageClose.setOnClickListener { hideFindBar() }
    }

    private fun showFindBar() {
        binding.findInPageBar.visibility = View.VISIBLE
        binding.findInPageEdit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.findInPageEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFindBar() {
        binding.findInPageEdit.setText("")
        binding.findInPageBar.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.findInPageEdit.windowToken, 0)
        updateFindCounter(0, 0)
    }

    private fun updateFindCounter(current: Int, total: Int) {
        binding.findInPageCounter.text = if (total > 0)
            getString(R.string.find_in_page_counter, current, total)
        else if (binding.findInPageEdit.text.isNotEmpty())
            getString(R.string.find_in_page_no_matches)
        else ""
    }

    private fun showBrowserMenu() {
        val popupView = layoutInflater.inflate(R.layout.popup_overflow_menu, null)
        val popup = PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.elevation = resources.displayMetrics.density * 8f
        popup.isOutsideTouchable = true

        // Top icon row: forward / bookmark / share / page info / reload
        popupView.findViewById<View>(R.id.menuForward).setOnClickListener {
            popup.dismiss(); view.goForward()
        }
        popupView.findViewById<View>(R.id.menuBookmarkAdd).setOnClickListener {
            popup.dismiss(); addCurrentBookmark()
        }
        popupView.findViewById<View>(R.id.menuShare).setOnClickListener {
            popup.dismiss(); shareCurrent()
        }
        popupView.findViewById<View>(R.id.menuPageInfo).setOnClickListener {
            popup.dismiss(); showPageInfoDialog()
        }
        popupView.findViewById<View>(R.id.menuRefresh).setOnClickListener {
            popup.dismiss(); view.reload()
        }

        // List rows, grouped like Vanadium
        popupView.findViewById<View>(R.id.rowNewPage).setOnClickListener {
            popup.dismiss(); openNewTab()
        }
        popupView.findViewById<View>(R.id.rowHistory).setOnClickListener {
            popup.dismiss(); openUrlList(UrlListActivity.MODE_HISTORY)
        }
        popupView.findViewById<View>(R.id.rowDeleteData).setOnClickListener {
            popup.dismiss(); confirmDeleteBrowsingData()
        }
        popupView.findViewById<View>(R.id.rowBookmarks).setOnClickListener {
            popup.dismiss(); openUrlList(UrlListActivity.MODE_BOOKMARKS)
        }
        popupView.findViewById<View>(R.id.rowFindInPage).setOnClickListener {
            popup.dismiss(); showFindBar()
        }
        // Show a checkmark only when desktop site is on; nothing (no empty box)
        // when it isn't being used.
        popupView.findViewById<ImageView>(R.id.desktopSiteCheck).visibility =
            if (settings.desktopSite) View.VISIBLE else View.GONE
        popupView.findViewById<View>(R.id.rowDesktopSite).setOnClickListener {
            popup.dismiss(); toggleDesktopSite()
        }
        popupView.findViewById<View>(R.id.rowSettings).setOnClickListener {
            popup.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        popupView.findViewById<View>(R.id.rowAbout).setOnClickListener {
            popup.dismiss(); showAboutDialog()
        }

        // Anchor at top-right under the 3-dot button, like Chromium
        popup.showAsDropDown(binding.menuButton, 0, 0, android.view.Gravity.END)
    }

    /**
     * Show the Normal / Tor / I2P compartment switcher. Picking a mode recolors
     * the whole chrome and (via applyNetworkTheme) updates the routing hook.
     */
    private fun showNetworkModeSwitcher() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun color(res: Int) = ContextCompat.getColor(this, res)
        val onSurface = color(R.color.ladybird_on_surface)
        val muted = color(R.color.ladybird_on_surface_muted)

        // A plain, flat list — close to a native Chromium/Vanadium chooser: an
        // icon, the name + one-line description, and a radio for the current one.
        val dialog = BottomSheetDialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // Solid sheet background so the page never shows through.
            background = ContextCompat.getDrawable(this@LadybirdActivity, R.drawable.bg_bottom_sheet)
            setPadding(0, dp(18), 0, dp(12))
        }
        root.addView(android.widget.TextView(this).apply {
            text = getString(R.string.net_mode_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(onSurface)
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        for (mode in NetworkMode.entries) {
            val theme = NetworkTheme.of(mode)
            val selected = settings.networkMode == mode
            val subtitle = when (mode) {
                NetworkMode.Normal -> getString(R.string.compartment_normal_subtitle)
                NetworkMode.Tor -> getString(R.string.compartment_tor_subtitle)
                NetworkMode.I2P -> getString(R.string.compartment_i2p_subtitle)
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(14))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    if (mode != settings.networkMode) {
                        // Switching compartment opens a FRESH tab in it (with its
                        // own loading screen) — compartments stay isolated rather
                        // than re-routing the current page.
                        pendingNavigation = null
                        openNewTab(mode)
                    }
                }
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(theme.icon)
                val s = dp(28)
                layoutParams = android.widget.LinearLayout.LayoutParams(s, s)
                    .apply { marginEnd = dp(18) }
                if (mode == NetworkMode.Normal) setColorFilter(muted)
            })
            row.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                addView(android.widget.TextView(this@LadybirdActivity).apply {
                    text = mode.displayName
                    setTextColor(onSurface)
                    textSize = 16f
                })
                addView(android.widget.TextView(this@LadybirdActivity).apply {
                    text = subtitle
                    setTextColor(muted)
                    textSize = 13f
                })
            })
            row.addView(android.widget.RadioButton(this).apply {
                isChecked = selected
                isClickable = false
            })
            root.addView(row)
        }
        dialog.setContentView(root)
        dialog.show()
    }

    /** Recolor the chrome and the toolbar logo to the active compartment, and
     *  push the matching proxy down to the engine (routing hook). */
    private fun applyNetworkTheme() {
        val theme = NetworkTheme.of(settings.networkMode)
        val bar = ContextCompat.getColor(this, theme.barColor)
        val field = ContextCompat.getColor(this, theme.fieldColor)
        val on = ContextCompat.getColor(this, theme.onColor)

        // Smoothly cross-fade the chrome colours from the current ones to the new
        // compartment's, instead of snapping, so switching modes feels fluid.
        val fromBar = if (currentBarColor != 0) currentBarColor else bar
        val fromField = if (currentFieldColor != 0) currentFieldColor else field
        themeAnimator?.cancel()
        themeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            val argb = ArgbEvaluator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                val b = argb.evaluate(t, fromBar, bar) as Int
                val f = argb.evaluate(t, fromField, field) as Int
                binding.appBar.setBackgroundColor(b)
                window.statusBarColor = b
                binding.urlBarCard.setCardBackgroundColor(f)
            }
            start()
        }
        currentBarColor = bar
        currentFieldColor = field

        binding.networkModeButton.setImageResource(theme.icon)
        // The globe (Normal) is a monochrome glyph tinted to the bar colour; the
        // Tor onion and I2P mascot are full-colour images shown untinted. NOTE:
        // the button has android:tint in XML (an imageTintList), which a
        // colorFilter does NOT override — so toggle imageTintList itself, else
        // the full-colour logos render as a flat tinted silhouette.
        binding.networkModeButton.imageTintList =
            if (theme.tintIcon) android.content.res.ColorStateList.valueOf(on) else null

        // In a non-Normal compartment, also tint the other toolbar glyphs so the
        // mode reads at a glance; Normal keeps the default on-surface colour.
        if (settings.networkMode != NetworkMode.Normal) {
            binding.homeButton.setColorFilter(on)
            binding.newTabButton.setColorFilter(on)
            binding.menuButton.setColorFilter(on)
        } else {
            val def = ContextCompat.getColor(this, R.color.ladybird_on_surface)
            binding.homeButton.setColorFilter(def)
            binding.newTabButton.setColorFilter(def)
            binding.menuButton.setColorFilter(def)
        }

        // The New Tab logo carries the compartment identity too (and fills up
        // while the daemon bootstraps; see begin/finishBootstrapUi).
        applyNtpLogoTint()

        applyNetworkRouting()
    }

    /** Routing hook: bring up (or tear down) the compartment's daemon and only
     *  publish its proxy once it is actually ready. The NetworkController writes
     *  the proxy file the RequestServer process consumes; here we also notify the
     *  live view and surface the daemon state to the user. */
    private fun applyNetworkRouting() {
        if (!::netController.isInitialized) return
        val mode = settings.networkMode
        // Switching a network mode must never crash the browser. If anything in
        // the daemon/controller path throws, log it and stay on a direct route.
        try {
            netController.switchTo(
                mode,
                onProgress = { pct -> runOnUiThread { onNetworkProgress(mode, pct) } },
            ) { state -> onNetworkState(mode, state) }
        } catch (t: Throwable) {
            Log.e("LadybirdNet", "network switch to ${mode.displayName} failed", t)
            if (viewInitialized) view.setNetworkProxy(null, null, 0)
        }
    }

    /**
     * Kill our :RequestServer (and :WebContent) helper processes so they respawn
     * and re-read the network_proxy file. RequestServer only applies the proxy at
     * process startup, so this is how a mode change actually takes effect for
     * live browsing. They are our own processes (same UID); WebContent already
     * tolerates a RequestServer respawn.
     */
    private fun restartRequestServerProcess() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            am.runningAppProcesses?.forEach { proc ->
                // Only RequestServer: it re-reads the proxy on respawn and is
                // re-bound by WebContent on the next request. Don't kill
                // WebContent itself (that would blank the page).
                if (proc.processName.contains(":RequestServer")) {
                    Log.i("LadybirdNet", "restarting ${proc.processName} (pid ${proc.pid}) to apply proxy")
                    android.os.Process.killProcess(proc.pid)
                }
            }
        } catch (t: Throwable) {
            Log.w("LadybirdNet", "could not restart RequestServer process", t)
        }
    }

    private fun onNetworkState(mode: NetworkMode, state: NetworkController.State) {
        runOnUiThread {
            try {
                when (state) {
                    NetworkController.State.Ready -> {
                        compartmentReady = true
                        if (viewInitialized)
                            view.setNetworkProxy(mode.proxyType, mode.proxyHost, mode.proxyPort)
                        // Daemon is up — snap the logo to full colour and hide the bar.
                        finishBootstrapUi()
                        // The proxy file is now written; the RequestServer process
                        // only reads it at startup, so restart it (kill -> WebContent
                        // respawns it) so traffic actually goes through the proxy.
                        restartRequestServerProcess()
                        // Run a navigation the user queued while connecting; else
                        // reload the current page so it goes through the proxy.
                        if (pendingNavigation != null) flushPendingNavigation()
                        else if (currentUrl.isNotBlank() && !isNewTabUrl(currentUrl)) view.reload()
                    }
                    NetworkController.State.Direct -> {
                        compartmentReady = true
                        finishBootstrapUi()
                        if (viewInitialized)
                            view.setNetworkProxy(mode.proxyType, mode.proxyHost, mode.proxyPort)
                        restartRequestServerProcess()
                        flushPendingNavigation()
                    }
                    NetworkController.State.Starting -> {
                        // Not ready: block navigation (no direct leak) and show the
                        // centre logo filling up with the compartment colour as the
                        // daemon bootstraps; real progress arrives via onNetworkProgress.
                        compartmentReady = false
                        beginBootstrapUi(mode)
                        // The proxy file was already written with this compartment's
                        // endpoint, so restart RequestServer now to pick it up.
                        restartRequestServerProcess()
                    }
                    NetworkController.State.Unavailable -> {
                        compartmentReady = true
                        finishBootstrapUi()
                        Toast.makeText(this, "${mode.displayName} is not bundled yet — staying direct", Toast.LENGTH_LONG).show()
                        if (viewInitialized) view.setNetworkProxy(null, null, 0)
                        flushPendingNavigation()
                    }
                    NetworkController.State.Failed -> {
                        compartmentReady = true
                        finishBootstrapUi()
                        Toast.makeText(this, "${mode.displayName} failed to start — staying direct", Toast.LENGTH_LONG).show()
                        if (viewInitialized) view.setNetworkProxy(null, null, 0)
                        flushPendingNavigation()
                    }
                }
            } catch (t: Throwable) {
                Log.e("LadybirdNet", "network state handling failed", t)
            }
        }
    }

    /**
     * Live bootstrap tick (0–100) for the active compartment's daemon. Fades the
     * toolbar logo from grey to full colour as the circuit builds and advances
     * the determinate bar, so the user sees the network "coming up" rather than a
     * dead pause. The bar/logo are reset by begin/finishBootstrapUi.
     */
    private fun onNetworkProgress(mode: NetworkMode, percent: Int) {
        if (mode != settings.networkMode || mode == NetworkMode.Normal) return
        val pct = percent.coerceIn(0, 100)
        // While connecting, ONLY the centre logo + its percentage change.
        binding.ntpLogoFill.setImageLevel(pct * 100)
        binding.ntpLogoPercent.apply {
            text = getString(R.string.bootstrap_percent, pct)
            visibility = View.VISIBLE
        }
        applyLogoSaturation(pct / 100f)
        if (pct >= 100) finishBootstrapUi()
    }

    /** Enter the "connecting" look: the centre logo becomes the compartment's own
     *  badge — the Tor onion or the I2P mascot — a dim base with the full-colour
     *  image filling up from the bottom, plus a live percentage. */
    private fun beginBootstrapUi(mode: NetworkMode) {
        if (mode == NetworkMode.Normal) { finishBootstrapUi(); return }
        binding.netBootstrapProgress.visibility = View.GONE
        applyLogoSaturation(0f)
        val icon = NetworkTheme.of(mode).icon
        binding.ntpLogo.setImageResource(icon)
        binding.ntpLogo.imageTintList = null
        binding.ntpLogo.alpha = 0.25f
        binding.ntpLogoFill.setImageDrawable(clipOf(icon))
        binding.ntpLogoFill.imageTintList = null
        binding.ntpLogoFill.setImageLevel(0)
        binding.ntpLogoFill.visibility = View.VISIBLE
        binding.ntpLogoPercent.apply {
            text = getString(R.string.bootstrap_percent, 0)
            visibility = View.VISIBLE
        }
    }

    /** Leave the "connecting" look: hide the fill + percentage, show the full
     *  compartment badge. */
    private fun finishBootstrapUi() {
        binding.netBootstrapProgress.visibility = View.GONE
        binding.networkModeButton.colorFilter = null
        binding.networkModeButton.alpha = 1f
        binding.ntpLogoFill.visibility = View.GONE
        binding.ntpLogoPercent.visibility = View.GONE
        binding.ntpLogo.alpha = 1f
        applyNtpLogoTint()
    }

    /** Centre logo at rest: the Tor onion, the I2P mascot, or — in Normal — the
     *  plain Ladybird mark. */
    private fun applyNtpLogoTint() {
        binding.ntpLogo.imageTintList = null
        binding.ntpLogo.setImageResource(
            if (settings.networkMode == NetworkMode.Normal) R.drawable.ntp_logo_white
            else NetworkTheme.of(settings.networkMode).icon
        )
    }

    /** A bottom-up clip of a drawable, used for the fill-up logo animation. */
    private fun clipOf(resId: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.ClipDrawable(
            ContextCompat.getDrawable(this, resId),
            android.view.Gravity.BOTTOM,
            android.graphics.drawable.ClipDrawable.VERTICAL)

    /** Run a navigation the user attempted while the compartment was connecting. */
    private fun flushPendingNavigation() {
        val pending = pendingNavigation ?: return
        pendingNavigation = null
        navigateToInput(pending)
    }

    /**
     * Desaturate the toolbar logo by [fraction] (0 = greyscale + dim, 1 = full
     * colour). Only meaningful for the image logos (Tor onion / I2P mascot); the
     * Normal globe is tinted and never enters bootstrap.
     */
    private fun applyLogoSaturation(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val matrix = android.graphics.ColorMatrix().apply { setSaturation(f) }
        binding.networkModeButton.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        binding.networkModeButton.alpha = 0.4f + 0.6f * f
    }

    // ----------------------------------------------------------------------
    // Tabs
    // ----------------------------------------------------------------------

    private fun updateTabCount() {
        binding.tabCountLabel.text = tabs.count.toString()
    }

    /** Persist the live page into the active tab before leaving it. */
    private fun saveActiveTab() {
        tabs.active().let {
            it.url = currentUrl.ifBlank { it.url }
            it.title = currentTitle
            it.networkMode = settings.networkMode
        }
        // Snapshot the page so the tab switcher can show a real preview.
        val tab = tabs.active()
        captureThumbnail { bmp -> if (bmp != null) tab.thumbnail = bmp }
    }

    /** Grab a downscaled snapshot of the live web view via PixelCopy (works for
     *  the hardware-rendered surface, unlike View.draw). Async; [onDone] gets
     *  null if capture isn't possible. */
    private fun captureThumbnail(onDone: (android.graphics.Bitmap?) -> Unit) {
        val v: View = binding.webView
        if (!viewInitialized || v.width <= 0 || v.height <= 0) { onDone(null); return }
        try {
            val full = android.graphics.Bitmap.createBitmap(
                v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val src = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            android.view.PixelCopy.request(window, src, full, { result ->
                if (result == android.view.PixelCopy.SUCCESS) {
                    val tw = 400
                    val th = (tw.toFloat() / full.width * full.height).toInt().coerceAtLeast(1)
                    val thumb = android.graphics.Bitmap.createScaledBitmap(full, tw, th, true)
                    full.recycle()
                    onDone(thumb)
                } else {
                    full.recycle()
                    onDone(null)
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (t: Throwable) {
            Log.w("Ladybird", "thumbnail capture failed", t)
            onDone(null)
        }
    }

    private fun openNewTab(mode: NetworkMode = settings.networkMode) {
        saveActiveTab()
        tabs.open(AppSettings.DEFAULT_HOME, mode)
        updateTabCount()
        applyTabMode(mode)
        navigateToInput(settings.homePage)
    }

    private fun switchToTab(index: Int) {
        if (index == tabs.activeIndex) return
        saveActiveTab()
        tabs.select(index)
        val tab = tabs.active()
        updateTabCount()
        applyTabMode(tab.networkMode)
        navigateToInput(tab.url)
    }

    private fun closeTab(index: Int) {
        val wasActive = index == tabs.activeIndex
        val nowActive = tabs.close(index)
        updateTabCount()
        if (wasActive) {
            applyTabMode(nowActive.networkMode)
            navigateToInput(nowActive.url)
        }
    }

    /** Switch the network compartment to a tab's mode (if it differs). */
    private fun applyTabMode(mode: NetworkMode) {
        if (settings.networkMode != mode) {
            settings.networkMode = mode
            applyNetworkTheme()
        }
    }

    /**
     * Full-screen tab switcher: a 2-column grid of tab cards (Vanadium-style)
     * instead of the old bottom-sheet list. Each card shows a compartment dot
     * (Tor purple / I2P red / Normal grey), the title and URL, and a close
     * button; the active tab is outlined in the accent colour. A new-tab row at
     * the bottom opens a tab in any compartment.
     */
    private fun showTabSwitcher() {
        saveActiveTab()
        // Snapshot the current page first so its card shows a fresh preview, then
        // build the grid (other tabs use the snapshot taken when they were active).
        captureThumbnail { bmp ->
            if (bmp != null) tabs.active().thumbnail = bmp
            buildTabSwitcher()
        }
    }

    private fun buildTabSwitcher() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun color(res: Int) = ContextCompat.getColor(this, res)

        val surface = color(R.color.ladybird_surface)
        val surfaceVariant = color(R.color.ladybird_surface_variant)
        val onSurface = color(R.color.ladybird_on_surface)
        val muted = color(R.color.ladybird_on_surface_muted)
        val accent = color(R.color.ladybird_accent)
        fun compartmentColor(mode: NetworkMode) = when (mode) {
            NetworkMode.Tor -> color(R.color.net_tor)
            NetworkMode.I2P -> color(R.color.net_i2p)
            NetworkMode.Normal -> muted
        }

        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else dp(24) }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(8), statusBar + dp(8), dp(8), dp(8))
        }

        // Top bar: "Tabs" title + close.
        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
            addView(android.widget.TextView(this@LadybirdActivity).apply {
                text = getString(R.string.browser_tabs)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            // New tab in the current compartment (Vanadium-style "+" up top).
            addView(android.widget.ImageButton(this@LadybirdActivity).apply {
                setImageResource(R.drawable.ic_add)
                background = null
                setColorFilter(onSurface)
                setOnClickListener { dialog.dismiss(); openNewTab(settings.networkMode) }
            })
            addView(android.widget.ImageButton(this@LadybirdActivity).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                setColorFilter(onSurface)
                setOnClickListener { dialog.dismiss() }
            })
        })

        // Lets a card's close button refresh the grid in place (assigned once the
        // grid + search field exist below). Avoids tearing the dialog down and
        // rebuilding it, which used to flick the page underneath.
        var refreshGrid: () -> Unit = {}

        fun makeCard(i: Int): View {
            val tab = tabs.all()[i]
            val active = i == tabs.activeIndex
            val label = if (isNewTabUrl(tab.url)) getString(R.string.tab_label_new) else tab.title.ifBlank { tab.url }
            val host = (runCatching { android.net.Uri.parse(tab.url).host }.getOrNull() ?: "")
                .removePrefix("www.")
            val compColor = compartmentColor(tab.networkMode)

            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(surfaceVariant)
                    cornerRadius = dp(18).toFloat()
                    if (active) setStroke(dp(2), accent)
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener { dialog.dismiss(); switchToTab(i) }
            }
            // Header: favicon-style monogram tile (compartment-coloured) + title + close.
            card.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(android.widget.TextView(this@LadybirdActivity).apply {
                    text = host.ifBlank { label }.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(compColor)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(30), dp(30))
                        .apply { marginEnd = dp(10) }
                })
                addView(android.widget.TextView(this@LadybirdActivity).apply {
                    text = label
                    setTextColor(onSurface)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(android.widget.ImageButton(this@LadybirdActivity).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setColorFilter(muted)
                    val s = dp(28)
                    layoutParams = android.widget.LinearLayout.LayoutParams(s, s)
                    setOnClickListener {
                        val wasLast = tabs.count == 1
                        closeTab(i)
                        // Closing the final tab drops us onto a fresh new-tab page;
                        // just leave the switcher. Otherwise refresh the grid in
                        // place so the card vanishes without the page flicking.
                        if (wasLast) dialog.dismiss() else refreshGrid()
                    }
                })
            })
            // Preview panel: a real page snapshot when we have one (Vanadium-style),
            // otherwise a darker inset surface showing the host.
            val snap = tab.thumbnail
            if (snap != null && !snap.isRecycled) {
                card.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(snap)
                    scaleType = android.widget.ImageView.ScaleType.MATRIX
                    adjustViewBounds = false
                    clipToOutline = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(surface)
                    }
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                        }
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(150))
                        .apply { topMargin = dp(10) }
                    // Crop to the top of the page (matrix scale to width, anchored top).
                    post {
                        val s = width.toFloat() / snap.width
                        imageMatrix = android.graphics.Matrix().apply { setScale(s, s) }
                    }
                })
            } else {
                card.addView(android.widget.TextView(this).apply {
                    text = if (isNewTabUrl(tab.url)) getString(R.string.tab_label_new) else host.ifBlank { tab.url }
                    setTextColor(muted)
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(surface)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(150))
                        .apply { topMargin = dp(10) }
                })
            }
            return card
        }

        // "Search your tabs" field (Vanadium-style), filters the grid live.
        val search = android.widget.EditText(this).apply {
            hint = getString(R.string.tabs_search_hint)
            setHintTextColor(muted)
            setTextColor(onSurface)
            textSize = 15f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(surfaceVariant)
            }
            setPadding(dp(22), dp(14), dp(22), dp(14))
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                .apply { setMargins(dp(10), dp(4), dp(10), dp(10)) }
        }
        root.addView(search)

        // Grid of cards, two per row, filtered by the search query.
        val grid = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun rebuildGrid(query: String) {
            grid.removeAllViews()
            val q = query.trim().lowercase()
            val indices = tabs.all().indices.filter { idx ->
                val t = tabs.all()[idx]
                q.isEmpty() || t.title.lowercase().contains(q) || t.url.lowercase().contains(q)
            }
            var k = 0
            while (k < indices.size) {
                val rowL = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                for (col in 0 until 2) {
                    val slot = android.widget.FrameLayout(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                            .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
                    }
                    if (k < indices.size) slot.addView(makeCard(indices[k]))
                    rowL.addView(slot)
                    k++
                }
                grid.addView(rowL)
            }
        }
        rebuildGrid("")
        refreshGrid = { rebuildGrid(search.text?.toString().orEmpty()) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                rebuildGrid(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        root.addView(android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            addView(grid)
        })

        dialog.setContentView(root)
        dialog.show()
    }

    private fun toggleDesktopSite() {
        // Flip the dedicated desktop-site flag (source of truth for the
        // checkbox); applySettingsToView applies the effective UA. This always
        // toggles both ways and never clobbers the user's chosen UA preset.
        settings.desktopSite = !settings.desktopSite
        applySettingsToView()
        if (currentUrl.isNotBlank() && !isNewTabUrl(currentUrl)) view.reload()
    }

    private fun confirmDeleteBrowsingData() {
        // Full-screen Vanadium-style screen; it queues the cookie/cache clear,
        // which we apply in onResume when we come back.
        startActivity(Intent(this, DeleteBrowsingDataActivity::class.java))
    }

    private fun showPageInfoDialog() {
        if (currentUrl.isBlank() || isNewTabUrl(currentUrl)) return
        val secure = currentUrl.startsWith("https://")
        MaterialAlertDialogBuilder(this)
            .setTitle(currentTitle.ifBlank { currentUrl })
            .setMessage("$currentUrl\n\n" + getString(if (secure) R.string.page_info_secure else R.string.page_info_insecure))
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.menu_open_external) { _, _ -> openCurrentInSystemBrowser() }
            .show()
    }

    private fun addCurrentBookmark() {
        if (currentUrl.isBlank()) return
        val added = bookmarks.add(currentUrl, currentTitle.ifBlank { currentUrl })
        Toast.makeText(
            this,
            if (added) R.string.bookmark_added else R.string.bookmark_already_exists,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareCurrent() {
        if (currentUrl.isBlank()) return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentUrl)
            putExtra(Intent.EXTRA_SUBJECT, currentTitle)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.menu_share)))
    }

    private fun copyCurrentUrl() {
        if (currentUrl.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
        Toast.makeText(this, R.string.menu_copy_url, Toast.LENGTH_SHORT).show()
    }

    private fun openViewSource() {
        if (currentUrl.isNotBlank() && !currentUrl.startsWith("view-source:"))
            view.loadURL("view-source:$currentUrl")
    }

    private fun openCurrentInSystemBrowser() {
        if (currentUrl.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.feature_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setColorScheme(scheme: ColorSchemePreference) {
        settings.colorScheme = scheme
        view.setPreferredColorScheme(scheme.nativeValue)
    }

    private fun openUrlList(mode: String) {
        startActivity(Intent(this, UrlListActivity::class.java).putExtra(UrlListActivity.EXTRA_MODE, mode))
    }

    private fun showBookmarksSheet() {
        val initial = bookmarks.all().map { UrlRow(it.url, it.title.ifBlank { it.url }) }
        showUrlListSheet(
            iconRes = R.drawable.ic_bookmark,
            titleRes = R.string.bookmarks_title,
            emptyRes = R.string.bookmarks_empty,
            subtitleFormat = R.string.bookmarks_subtitle,
            actionLabel = if (initial.isNotEmpty()) R.string.dialog_done else 0,
            initial = initial,
            onActivate = { row -> navigateToInput(row.url) },
            onDelete = { row ->
                bookmarks.remove(row.url)
                Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
            },
            onAction = null
        )
    }

    private fun showHistorySheet() {
        val initial = history.all().map { UrlRow(it.url, it.title.ifBlank { it.url }) }
        showUrlListSheet(
            iconRes = R.drawable.ic_history,
            titleRes = R.string.history_title,
            emptyRes = R.string.history_empty,
            subtitleFormat = R.string.history_subtitle,
            actionLabel = if (initial.isNotEmpty()) R.string.history_clear else 0,
            initial = initial,
            onActivate = { row -> navigateToInput(row.url) },
            onDelete = { row -> history.remove(row.url) },
            onAction = { dismiss ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.history_clear)
                    .setPositiveButton(R.string.dialog_clear) { _, _ ->
                        history.clear()
                        dismiss()
                        Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
        )
    }

    private fun showUrlListSheet(
        iconRes: Int,
        titleRes: Int,
        emptyRes: Int,
        subtitleFormat: Int,
        actionLabel: Int,
        initial: List<UrlRow>,
        onActivate: (UrlRow) -> Unit,
        onDelete: ((UrlRow) -> Unit)?,
        onAction: ((() -> Unit) -> Unit)?
    ) {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_url_list, null)
        dialog.setContentView(sheet)

        sheet.findViewById<ImageView>(R.id.sheetIcon).setImageResource(iconRes)
        sheet.findViewById<TextView>(R.id.sheetTitle).setText(titleRes)
        val subtitle = sheet.findViewById<TextView>(R.id.sheetSubtitle)
        subtitle.text = getString(subtitleFormat, initial.size)

        val list = sheet.findViewById<RecyclerView>(R.id.sheetList)
        list.layoutManager = LinearLayoutManager(this)
        val emptyState = sheet.findViewById<View>(R.id.sheetEmptyState)
        sheet.findViewById<TextView>(R.id.sheetEmptyText).setText(emptyRes)

        val rows = initial.toMutableList()
        lateinit var adapter: UrlListAdapter
        val deleteCallback = onDelete

        fun refreshState() {
            emptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            subtitle.text = getString(subtitleFormat, rows.size)
        }

        adapter = UrlListAdapter(
            rows,
            onClick = { row -> dialog.dismiss(); onActivate(row) },
            onDelete = if (deleteCallback != null) { row ->
                val idx = rows.indexOf(row)
                if (idx >= 0) {
                    adapter.removeAt(idx)
                    deleteCallback(row)
                    refreshState()
                }
            } else null
        )
        list.adapter = adapter
        refreshState()

        val actionButton = sheet.findViewById<com.google.android.material.button.MaterialButton>(R.id.sheetActionButton)
        if (actionLabel != 0) {
            actionButton.visibility = View.VISIBLE
            actionButton.setText(actionLabel)
            actionButton.setOnClickListener {
                if (onAction != null) onAction { dialog.dismiss() }
                else dialog.dismiss()
            }
        } else {
            actionButton.visibility = View.GONE
        }

        dialog.show()
    }

    private fun showPageContextMenu() {
        val items = mutableListOf<Pair<Int, () -> Unit>>()
        if (currentUrl.isNotBlank()) {
            items += R.string.context_copy_url to { copyCurrentUrl() }
            items += R.string.menu_share to { shareCurrent() }
            items += R.string.menu_reload to { view.reload() }
            items += R.string.context_view_source to { openViewSource() }
            items += R.string.menu_open_external to { openCurrentInSystemBrowser() }
            items += R.string.menu_find to { showFindBar() }
        }
        items += R.string.menu_bookmark to { addCurrentBookmark() }
        items += R.string.menu_select_all to { view.selectAllOnPage() }

        val labels = items.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(if (currentTitle.isNotBlank()) currentTitle else getString(R.string.app_name))
            .setItems(labels) { _, idx -> items[idx].second() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
        } catch (_: Exception) { "dev" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_ladybird_title)
            .setMessage(getString(R.string.about_ladybird_message, version))
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.about_visit_website) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ladybird.org/")))
                } catch (_: Exception) {}
            }
            .show()
    }

    private external fun initNativeCode(
        resourceDir: String, tag: String, timerService: TimerExecutorService, userDir: String
    )

    private external fun disposeNativeCode()
    private external fun execMainEventLoop()

    companion object {
        init {
            System.loadLibrary("Ladybird")
        }

        private const val NEW_TAB_LOAD_URL = "about:blank"
        private val WHITESPACE_REGEX = Regex("\\s")
    }
}
