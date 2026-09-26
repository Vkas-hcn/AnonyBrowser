package com.anony.bro.wser.view.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.anony.bro.wser.R
import com.anony.bro.wser.ads.AdLoadListener
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.ads.BannerAdSlot
import com.anony.bro.wser.databinding.FragmentBrowserBinding
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder

class BrowserFragment : Fragment() {

    interface Callback {
        fun onBrowserStateChanged(
            tabId: String,
            title: String?,
            url: String?,
            progress: Int,
            canGoBack: Boolean,
            canGoForward: Boolean,
            isLoading: Boolean,
            hasError: Boolean,
        )

        fun onBrowserTabsClick()

        fun onBrowserMenuClick(anchor: View)

        fun onBrowserReturnHome()
    }

    private var _binding: FragmentBrowserBinding? = null
    private val binding: FragmentBrowserBinding
        get() = _binding ?: error("Browser binding is not available")

    private val callback: Callback?
        get() = activity as? Callback

    private val tabId: String by lazy {
        requireArguments().getString(ARG_TAB_ID) ?: error("Missing tab id")
    }

    private var pendingUrl: String? = null
    private var restoredState: Bundle? = null
    private var currentProgress = 0
    private var isLoading = false
    private var hasPageError = false
    private var activeErrorPage: BrowserErrorPage? = null
    private var pendingSslHandler: SslErrorHandler? = null
    private var bannerVisitArmed = true
    private var bannerLoadListener: AdLoadListener? = null
    private val bannerMode: BannerAdSlot.BannerMode = AdMobManager.bannerModeCollapsible()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUrl = savedInstanceState?.getString(STATE_URL)
            ?: requireArguments().getString(ARG_INITIAL_URL)
        restoredState = savedInstanceState?.getBundle(STATE_WEBVIEW)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbarActions()
        configureWebView(binding.webView)
        val state = restoredState
        if (state != null) {
            binding.webView.restoreState(state)
            updateToolbar(binding.webView.url)
        } else {
            pendingUrl?.let { url ->
                updateToolbar(url)
                loadRequestedUrl(url)
            }
        }
        notifyState()
    }

    override fun onResume() {
        super.onResume()
        syncBannerAd()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        syncBannerAd()
    }

    private fun syncBannerAd() {
        val visible = _binding != null && isResumed && !isHidden
        if (!visible) {
            unbindBannerLoadObserver()
            AdMobManager.discardBannerCache(bannerMode)
            bannerVisitArmed = true
            return
        }
        val host = (activity as? MainActivity)?.bannerOverlay() ?: return
        bindBannerLoadObserver(host)
        if (!bannerVisitArmed) return
        bannerVisitArmed = false
        // AdMob 折叠展开条必须在可见 View 树上用 Activity context 加载才会展开；
        // Guide 离屏缓存不可用，丢弃后就地重载。
        AdMobManager.discardBannerCache(bannerMode)
        AdMobManager.loadBanner(requireContext(), bannerMode, attachTo = host)
    }

    private fun bindBannerLoadObserver(host: ViewGroup) {
        if (bannerLoadListener != null) return
        val listener = object : AdLoadListener {
            override fun onAdLoaded() {
                if (_binding == null || !isResumed || isHidden) return
                if (!host.isAttachedToWindow) return
                AdMobManager.updateBannerContainer(host, bannerMode)
            }

            override fun onAdFailedToLoad(error: String) = Unit
        }
        bannerLoadListener = listener
        AdMobManager.addBannerLoadObserver(bannerMode, listener)
    }

    private fun unbindBannerLoadObserver() {
        val listener = bannerLoadListener ?: return
        AdMobManager.removeBannerLoadObserver(bannerMode, listener)
        bannerLoadListener = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, bindingOrNull()?.webView?.url ?: pendingUrl)
        bindingOrNull()?.webView?.let { webView ->
            outState.putBundle(STATE_WEBVIEW, Bundle().also(webView::saveState))
        }
    }

    override fun onDestroyView() {
        unbindBannerLoadObserver()
        if (!isHidden) {
            AdMobManager.discardBannerCache(bannerMode)
        }
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        _binding = null
        super.onDestroyView()
    }

    fun loadUrl(url: String) {
        pendingUrl = url
        updateToolbar(url)
        loadRequestedUrl(url)
    }

    fun canGoBack(): Boolean = bindingOrNull()?.webView?.canGoBack() == true

    fun canGoForward(): Boolean = bindingOrNull()?.webView?.canGoForward() == true

    fun goBack() {
        bindingOrNull()?.webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        bindingOrNull()?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        val binding = bindingOrNull() ?: return
        val target = binding.webView.url
            ?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: pendingUrl
        if (target != null) {
            loadRequestedUrl(target)
        } else {
            hasPageError = false
            hideErrorPage()
            binding.webView.reload()
        }
    }

    fun stopLoading() {
        bindingOrNull()?.webView?.stopLoading()
        isLoading = false
        bindingOrNull()?.loadProgress?.visibility = View.GONE
        notifyState()
    }

    fun currentUrl(): String? = bindingOrNull()?.webView?.url ?: pendingUrl

    fun currentTitle(): String? = bindingOrNull()?.webView?.title

    fun updateTabCount(count: Int) {
        bindingOrNull()?.tvBrowserTabCount?.text = count.toString()
    }

    fun setContentInsets(top: Int, bottom: Int = 0) {
        val binding = bindingOrNull() ?: return
        binding.browserToolbar.updatePadding(top = top)
        binding.browserToolbar.layoutParams = binding.browserToolbar.layoutParams.apply {
            height = dp(48) + top
        }
        binding.browserRoot.updatePadding(bottom = bottom)
    }

    fun captureThumbnail(cacheDir: File, tabId: String): String? {
        val webView = bindingOrNull()?.webView ?: return null
        if (webView.width <= 0 || webView.height <= 0) return null
        return runCatching {
            val dir = File(cacheDir, "tab_thumbnails").apply { mkdirs() }
            val file = File(dir, "$tabId.png")
            val bitmap = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(360f / webView.width, 480f / webView.height)
            webView.draw(canvas)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 88, out)
            }
            bitmap.recycle()
            file.absolutePath
        }.getOrNull()
    }

    fun captureFavicon(cacheDir: File, tabId: String): String? {
        val favicon = bindingOrNull()?.webView?.favicon ?: return null
        return runCatching {
            val dir = File(cacheDir, "tab_favicons").apply { mkdirs() }
            val file = File(dir, "$tabId.png")
            FileOutputStream(file).use { out ->
                favicon.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun setupToolbarActions() {
        binding.urlBox.setOnClickListener {
            binding.tvUrlHost.requestFocus()
            showKeyboard(binding.tvUrlHost)
        }
        binding.tvUrlHost.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.tvUrlHost.setText(currentUrl().orEmpty())
                binding.tvUrlHost.selectAll()
            } else {
                updateToolbar(currentUrl())
            }
        }
        binding.tvUrlHost.setOnEditorActionListener { textView, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                openUserInput(textView.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.btnBrowserTabs.setOnClickListener {
            callback?.onBrowserTabsClick()
        }
        binding.btnBrowserMenu.setOnClickListener { view ->
            callback?.onBrowserMenuClick(view)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                isLoading = true
                hasPageError = false
                activeErrorPage = null
                hideErrorPage()
                bindingOrNull()?.loadProgress?.visibility = View.VISIBLE
                updateToolbar(url)
                notifyState()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                isLoading = false
                bindingOrNull()?.loadProgress?.visibility = View.GONE
                if (activeErrorPage == null) {
                    hasPageError = false
                    updateToolbar(url)
                }
                notifyState()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    updateToolbar(request.url?.toString() ?: view.url)
                    showErrorPage(errorPageFor(error.errorCode))
                    notifyState()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                val errorPage = when (errorResponse.statusCode) {
                    404 -> BrowserErrorPage.NotFound
                    in 500..599 -> BrowserErrorPage.Server
                    else -> BrowserErrorPage.Unexpected
                }
                updateToolbar(request.url?.toString() ?: view.url)
                showErrorPage(errorPage)
                notifyState()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError?,
            ) {
                pendingSslHandler?.cancel()
                pendingSslHandler = handler
                updateToolbar(view.url)
                showErrorPage(BrowserErrorPage.SslWarning)
                notifyState()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                currentProgress = newProgress
                bindingOrNull()?.loadProgress?.apply {
                    progress = newProgress
                    visibility = if (activeErrorPage == null && newProgress in 1..99) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
                if (newProgress >= 100) {
                    isLoading = false
                }
                notifyState()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                notifyState(title = title)
            }
        }
    }

    private fun loadRequestedUrl(url: String) {
        pendingUrl = url
        bindingOrNull()?.tvUrlHost?.clearFocus()
        if (!isNetworkAvailable()) {
            updateToolbar(url)
            showErrorPage(BrowserErrorPage.NoInternet)
            notifyState()
            return
        }
        hasPageError = false
        hideErrorPage()
        bindingOrNull()?.webView?.apply {
            visibility = View.VISIBLE
            loadUrl(url)
        }
    }

    private fun showErrorPage(errorPage: BrowserErrorPage) {
        val binding = bindingOrNull() ?: return
        activeErrorPage = errorPage
        hasPageError = true
        isLoading = false
        binding.loadProgress.visibility = View.GONE
        binding.webView.visibility = View.INVISIBLE
        binding.errorPage.visibility = View.VISIBLE
        binding.ivError.setImageResource(R.drawable.ic_error)

        val (titleRes, messageRes) = when (errorPage) {
            BrowserErrorPage.NoInternet -> R.string.browser_error_no_internet_title to
                R.string.browser_error_no_internet_message
            BrowserErrorPage.Timeout -> R.string.browser_error_timeout_title to
                R.string.browser_error_timeout_message
            BrowserErrorPage.NotFound -> R.string.browser_error_404_title to
                R.string.browser_error_404_message
            BrowserErrorPage.Server -> R.string.browser_error_server_title to
                R.string.browser_error_server_message
            BrowserErrorPage.SslWarning -> R.string.browser_ssl_warning_title to
                R.string.browser_ssl_warning_message
            BrowserErrorPage.Unexpected -> R.string.browser_error_unexpected_title to
                R.string.browser_error_unexpected_message
        }
        binding.tvErrorTitle.setText(titleRes)
        binding.tvErrorMessage.setText(messageRes)

        if (errorPage == BrowserErrorPage.SslWarning) {
            binding.btnErrorPrimary.setText(R.string.browser_return)
            binding.btnErrorSecondary.setText(R.string.browser_continue_anyway)
            binding.btnErrorSecondary.visibility = View.VISIBLE
            binding.btnErrorPrimary.setOnClickListener {
                pendingSslHandler?.cancel()
                pendingSslHandler = null
                if (binding.webView.canGoBack()) {
                    hideErrorPage(cancelSslHandler = false)
                    binding.webView.goBack()
                } else {
                    callback?.onBrowserReturnHome()
                }
            }
            binding.btnErrorSecondary.setOnClickListener {
                val handler = pendingSslHandler
                pendingSslHandler = null
                hasPageError = false
                isLoading = true
                hideErrorPage(cancelSslHandler = false)
                binding.loadProgress.visibility = View.VISIBLE
                handler?.proceed()
                notifyState()
            }
        } else {
            binding.btnErrorPrimary.setText(
                if (errorPage == BrowserErrorPage.NoInternet) {
                    R.string.browser_try_again
                } else {
                    R.string.browser_reload_page
                },
            )
            binding.btnErrorSecondary.visibility = View.GONE
            binding.btnErrorSecondary.setOnClickListener(null)
            binding.btnErrorPrimary.setOnClickListener {
                val target = binding.webView.url
                    ?.takeIf { it.isNotBlank() && it != "about:blank" }
                    ?: pendingUrl
                if (target != null) {
                    loadRequestedUrl(target)
                } else {
                    hasPageError = false
                    hideErrorPage()
                    binding.webView.reload()
                }
            }
        }
        updateToolbar(pendingUrl ?: binding.webView.url)
    }

    private fun hideErrorPage(cancelSslHandler: Boolean = true) {
        if (cancelSslHandler) {
            pendingSslHandler?.cancel()
            pendingSslHandler = null
        }
        activeErrorPage = null
        bindingOrNull()?.apply {
            errorPage.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }
    }

    private fun errorPageFor(errorCode: Int): BrowserErrorPage {
        if (!isNetworkAvailable()) return BrowserErrorPage.NoInternet
        return when (errorCode) {
            WebViewClient.ERROR_TIMEOUT -> BrowserErrorPage.Timeout
            else -> BrowserErrorPage.Unexpected
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val context = context ?: return false
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateToolbar(url: String?) {
        val binding = bindingOrNull() ?: return
        val displayUrl = url ?: pendingUrl
        if (!binding.tvUrlHost.hasFocus()) {
            binding.tvUrlHost.setText(displayUrl.orEmpty())
        }
        val isHttps = displayUrl?.startsWith("https://", ignoreCase = true) == true
        binding.ivLock.visibility = if (isHttps && !hasPageError) View.VISIBLE else View.GONE
        binding.ivSecurity.setImageResource(
            if (hasPageError) R.drawable.ic_dangerous else R.drawable.ic_security,
        )
    }

    private fun openUserInput(rawInput: String) {
        val target = when (val result = buildNavigationTarget(rawInput)) {
            InputTarget.Blank -> return
            InputTarget.Invalid -> {
                showToast(getString(R.string.browser_invalid_url))
                return
            }
            is InputTarget.Url -> result.url
        }
        if (!isNetworkAvailable()) {
            showToast(getString(R.string.browser_no_internet_connection))
            return
        }
        hideKeyboard(binding.tvUrlHost)
        binding.tvUrlHost.clearFocus()
        loadRequestedUrl(target)
    }

    private fun buildNavigationTarget(input: String): InputTarget {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return InputTarget.Blank
        val lower = trimmed.lowercase()
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
        if (hasScheme) {
            return normalizeHttpUrl(trimmed)?.let(InputTarget::Url) ?: InputTarget.Invalid
        }
        if (trimmed.contains("://")) {
            return InputTarget.Invalid
        }
        val looksLikeUrl = trimmed.contains(".") &&
            !trimmed.contains(" ") &&
            !trimmed.contains("\n")
        return if (looksLikeUrl) {
            normalizeHttpUrl("https://$trimmed")?.let(InputTarget::Url) ?: InputTarget.Invalid
        } else {
            val query = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            InputTarget.Url("https://www.google.com/search?q=$query")
        }
    }

    private fun normalizeHttpUrl(rawUrl: String): String? =
        runCatching {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme !in setOf("http", "https") || !isValidHost(host)) {
                return@runCatching null
            }
            uri.toASCIIString()
        }.getOrNull()

    private fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) return false
        return true
    }

    private fun showToast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun notifyState(title: String? = null) {
        val webView = bindingOrNull()?.webView
        callback?.onBrowserStateChanged(
            tabId = tabId,
            title = title ?: webView?.title,
            url = webView?.url ?: pendingUrl,
            progress = currentProgress,
            canGoBack = webView?.canGoBack() == true,
            canGoForward = webView?.canGoForward() == true,
            isLoading = isLoading,
            hasError = hasPageError,
        )
    }

    private fun bindingOrNull(): FragmentBrowserBinding? = _binding

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private enum class BrowserErrorPage {
        NoInternet,
        Timeout,
        NotFound,
        Server,
        SslWarning,
        Unexpected,
    }

    private sealed class InputTarget {
        data object Blank : InputTarget()
        data object Invalid : InputTarget()
        data class Url(val url: String) : InputTarget()
    }

    companion object {
        private const val ARG_TAB_ID = "tab_id"
        private const val ARG_INITIAL_URL = "initial_url"
        private const val STATE_URL = "state_url"
        private const val STATE_WEBVIEW = "state_webview"

        fun newInstance(tabId: String, initialUrl: String): BrowserFragment =
            BrowserFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_ID, tabId)
                    putString(ARG_INITIAL_URL, initialUrl)
                }
            }
    }
}
