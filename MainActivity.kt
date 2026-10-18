package app.btchat

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

/**
 * A bare WebView that shows assets/index.html and hands it the Bluetooth bridge.
 * No UI is built here: the whole interface lives in index.html.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: BtBridge

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            bridge.onPermissionResult(result.isNotEmpty() && result.values.all { it })
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dark = Color.parseColor("#0A0A0A")
        window.statusBarColor = dark
        window.navigationBarColor = dark

        web = WebView(this)
        web.setBackgroundColor(dark)
        setContentView(web)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)   // lets you inspect the page from desktop Chrome in debug builds
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true        // localStorage: this is where chat history is saved
            allowFileAccess = false
            allowContentAccess = false
        }

        // Serves assets/index.html from a virtual https address, so localStorage works and no network is used.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The page never needs to leave itself: block every navigation so nothing else can get the bridge.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }

        bridge = BtBridge(
            activity = this,
            web = web,
            askPermissions = { perms -> permissionLauncher.launch(perms) },
            keepAwake = { on -> setKeepAwake(on) }
        )
        web.addJavascriptInterface(bridge, "BTBridge")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(function(){return !!(window.onNativeBack&&window.onNativeBack());})()") { r ->
                    if (r != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    fun setKeepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        bridge.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
