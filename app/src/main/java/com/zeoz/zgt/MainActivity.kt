package com.zeoz.zgt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("zgt", MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private val centralEndpoint = "https://zgt.zeoz.com.ar/wp-json/gtc/v1/app/resolve"

    private val zgtBackground = Color.rgb(20, 20, 20)
    private val zgtSurface = Color.rgb(30, 30, 30)
    private val zgtBorder = Color.rgb(56, 56, 56)
    private val zgtText = Color.rgb(248, 250, 252)
    private val zgtMuted = Color.rgb(148, 163, 184)
    private val zgtLime = Color.rgb(234, 255, 0)
    private val zgtError = Color.rgb(252, 165, 165)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = zgtBackground
        window.navigationBarColor = zgtBackground
        root = FrameLayout(this)
        setContentView(root)
        showSplash()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = webView
                when {
                    w != null && w.canGoBack() -> w.goBack()
                    w != null -> confirmUnlink()
                    else -> finish()
                }
            }
        })
    }

    private fun showSplash() {
        root.removeAllViews()
        root.setBackgroundColor(zgtBackground)

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.zeoz_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        root.addView(logo, FrameLayout.LayoutParams(dp(184), dp(118), Gravity.CENTER))

        root.postDelayed({
            val url = prefs.getString("site_url", null)
            if (url.isNullOrBlank()) showLinkScreen() else showWorkshop(url)
        }, 420)
    }

    private fun showLinkScreen(message: String? = null) {
        root.removeAllViews()
        root.setBackgroundColor(zgtBackground)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(44), dp(28), dp(36))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.zeoz_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        column.addView(
            logo,
            LinearLayout.LayoutParams(dp(108), dp(73)).apply { bottomMargin = dp(28) }
        )

        column.addView(TextView(this).apply {
            text = "Vinculá tu taller"
            setTextColor(zgtText)
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        column.addView(TextView(this).apply {
            text = "Ingresá el código de vinculación de tu taller."
            setTextColor(zgtMuted)
            textSize = 17f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(28)
        })

        val code = EditText(this).apply {
            hint = "ZGT-XXXX-XXXX"
            setHintTextColor(zgtMuted)
            setTextColor(zgtText)
            textSize = 20f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(18), 0, dp(18), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps())
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            background = roundedDrawable(zgtSurface, 18, zgtBorder, 1)
        }
        column.addView(code, LinearLayout.LayoutParams(-1, dp(60)))

        val status = TextView(this).apply {
            setTextColor(zgtError)
            textSize = 14f
            gravity = Gravity.CENTER
            text = message ?: ""
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        })

        val button = Button(this).apply {
            text = "Vincular"
            isAllCaps = false
            setTextColor(Color.BLACK)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedDrawable(zgtLime, 14)
        }
        column.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(16)
        })

        column.addView(TextView(this).apply {
            text = "ZGT · v${BuildConfig.VERSION_NAME}"
            setTextColor(Color.rgb(90, 90, 90))
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        button.setOnClickListener {
            val value = code.text.toString().trim().uppercase()

            if (!Regex("^ZGT-[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(value)) {
                status.setTextColor(zgtError)
                status.text = "Revisá el formato del código."
                return@setOnClickListener
            }

            button.isEnabled = false
            button.text = "Vinculando…"
            status.setTextColor(zgtMuted)
            status.text = "Conectando con ZGT…"

            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(code.windowToken, 0)

            resolveCode(value) { result ->
                runOnUiThread {
                    button.isEnabled = true
                    button.text = "Vincular"

                    result.onSuccess { data ->
                        prefs.edit()
                            .putString("link_code", value)
                            .putString("workshop_name", data.first)
                            .putString("site_url", data.second)
                            .apply()

                        status.setTextColor(zgtMuted)
                        status.text = "Taller vinculado"
                        showWorkshop(data.second)
                    }.onFailure {
                        status.setTextColor(zgtError)
                        status.text = it.message ?: "No se pudo vincular el taller."
                    }
                }
            }
        }
    }

    private fun resolveCode(code: String, done: (Result<Pair<String, String>>) -> Unit) = thread {
        try {
            val connection = (URL(centralEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            connection.outputStream.use {
                it.write(JSONObject().put("code", code).toString().toByteArray())
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)

            if (status == 200 && json.optBoolean("ok")) {
                val name = json.optString("workshop_name", "Taller")
                val site = json.optString("site_url").trimEnd('/')
                if (!site.startsWith("https://")) throw Exception("La URL del taller no es segura.")
                done(Result.success(name to site))
            } else {
                done(Result.failure(Exception(json.optString("message", "El código de vinculación no es válido."))))
            }
        } catch (_: Exception) {
            done(Result.failure(Exception("No pudimos conectar con ZGT. Revisá tu conexión e intentá nuevamente.")))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWorkshop(siteUrl: String) {
        root.removeAllViews()

        val w = WebView(this)
        webView = w

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString ZGT-Android/0.1"
        }

        w.webChromeClient = WebChromeClient()

        val allowedHost = URL(siteUrl).host
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                return if (host == allowedHost || host.endsWith(".$allowedHost")) {
                    false
                } else {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    true
                }
            }
        }

        root.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.loadUrl("${siteUrl.trimEnd('/')}/wp-login.php")
    }

    private fun confirmUnlink() {
        AlertDialog.Builder(this)
            .setTitle("ZGT")
            .setMessage("¿Querés desvincular este dispositivo del taller?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Desvincular") { _, _ ->
                webView?.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                prefs.edit().clear().apply()
                webView = null
                showLinkScreen()
            }
            .show()
    }

    private fun roundedDrawable(
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeDp: Int = 0
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null && strokeDp > 0) {
            setStroke(dp(strokeDp), strokeColor)
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
