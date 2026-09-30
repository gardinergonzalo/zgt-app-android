package com.zeoz.zgt

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("zgt", MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var nativePrintWebView: WebView? = null
    private var nativePdfOverlay: LinearLayout? = null
    private var pendingCameraPermissionRequest: PermissionRequest? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingCameraPermissionRequest
            pendingCameraPermissionRequest = null

            if (request == null) {
                return@registerForActivityResult
            }

            if (granted) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                request.deny()
            }
        }
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
                    nativePdfOverlay != null -> closeNativePdfViewer()
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
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(36), dp(28), dp(36))
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
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(13))
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            background = roundedDrawable(zgtSurface, 18, zgtBorder, 1)
        }
        column.addView(code, LinearLayout.LayoutParams(-1, dp(60)))

        code.addTextChangedListener(object : TextWatcher {
            private var editing = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (editing) return

                val raw = editable?.toString()
                    .orEmpty()
                    .uppercase()
                    .filter { it.isLetterOrDigit() }
                    .take(11)

                val formatted = buildString {
                    raw.forEachIndexed { index, ch ->
                        if (index == 3 || index == 7) append('-')
                        append(ch)
                    }
                }

                if (editable?.toString() != formatted) {
                    editing = true
                    code.setText(formatted)
                    code.setSelection(formatted.length)
                    editing = false
                }
            }
        })

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
            text = "ZGT · v${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(Color.rgb(90, 90, 90))
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(-1, -1))
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
            userAgentString = "$userAgentString ZGT-Android/0.1.9"
        }

        w.addJavascriptInterface(ZGTNativeBridge(), "ZGTNative")
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val origin = request.origin?.toString().orEmpty()
                    val wantsVideo = request.resources.contains(
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    )

                    if (!wantsVideo || !isTrustedWorkshopUrl(origin)) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(
                            arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        )
                    } else {
                        pendingCameraPermissionRequest?.deny()
                        pendingCameraPermissionRequest = request
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingCameraPermissionRequest === request) {
                    pendingCameraPermissionRequest = null
                }
            }
        }

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

            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("/wp-login.php")) {
                    view.evaluateJavascript(
                        "(function(){var r=document.getElementById('rememberme');if(r){r.checked=true;r.setAttribute('checked','checked');}})();",
                        null
                    )
                }
                CookieManager.getInstance().flush()
            }
        }

        root.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.loadUrl("${siteUrl.trimEnd('/')}/wp-admin/")
    }


    private fun isTrustedWorkshopUrl(value: String): Boolean {
        val siteUrl = prefs.getString("site_url", null) ?: return false

        return try {
            val target = URL(value)
            val allowed = URL(siteUrl)

            target.protocol.equals("https", ignoreCase = true) &&
                (
                    target.host.equals(allowed.host, ignoreCase = true) ||
                    target.host.endsWith(".${allowed.host}", ignoreCase = true)
                )
        } catch (_: Exception) {
            false
        }
    }

    private fun safePdfFilename(value: String): String {
        val base = value
            .trim()
            .ifBlank { "documento.pdf" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(120)
            .ifBlank { "documento.pdf" }

        return if (base.lowercase().endsWith(".pdf")) base else "$base.pdf"
    }

    private fun downloadAuthenticatedPdf(
        urlString: String,
        filename: String,
        done: (Result<File>) -> Unit
    ) {
        if (!isTrustedWorkshopUrl(urlString)) {
            done(Result.failure(Exception("URL no permitida.")))
            return
        }

        val cookie = CookieManager.getInstance().getCookie(urlString).orEmpty()
        val userAgent = webView?.settings?.userAgentString.orEmpty()
        val referer = webView?.url.orEmpty()

        thread {
            var connection: HttpURLConnection? = null

            try {
                val file = File(
                    File(cacheDir, "shared").apply { mkdirs() },
                    safePdfFilename(filename)
                )

                connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                    if (userAgent.isNotBlank()) setRequestProperty("User-Agent", userAgent)
                    if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    throw Exception("HTTP $status")
                }

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                val signature = ByteArray(4)
                FileInputStream(file).use { input ->
                    if (input.read(signature) != 4) {
                        throw Exception("PDF vacío.")
                    }
                }

                if (String(signature, Charsets.US_ASCII) != "%PDF") {
                    file.delete()
                    throw Exception("La respuesta no es un PDF.")
                }

                done(Result.success(file))
            } catch (error: Exception) {
                done(Result.failure(error))
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun fileUri(file: File): Uri =
        FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )

    private fun closeNativePdfViewer() {
        nativePdfOverlay?.let { overlay ->
            try {
                root.removeView(overlay)
            } catch (_: Exception) {}
        }
        nativePdfOverlay = null
    }

    private fun openPdfFile(file: File) {
        closeNativePdfViewer()

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(zgtBackground)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(zgtSurface)
        }

        val backButton = Button(this).apply {
            text = "‹ Volver"
            isAllCaps = false
            setTextColor(zgtText)
            textSize = 15f
            background = roundedDrawable(zgtSurface, 10)
            setOnClickListener { closeNativePdfViewer() }
        }

        val titleView = TextView(this).apply {
            text = file.nameWithoutExtension.ifBlank { "Presupuesto" }
            setTextColor(zgtText)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }

        val shareButton = Button(this).apply {
            text = "Compartir"
            isAllCaps = false
            setTextColor(zgtText)
            textSize = 13f
            background = roundedDrawable(zgtSurface, 10)
            setOnClickListener { sharePdfFile(file) }
        }

        val printButton = Button(this).apply {
            text = "Imprimir"
            isAllCaps = false
            setTextColor(zgtText)
            textSize = 13f
            background = roundedDrawable(zgtSurface, 10)
            setOnClickListener { printPdfFile(file) }
        }

        header.addView(
            backButton,
            LinearLayout.LayoutParams(dp(90), dp(46))
        )
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(
            shareButton,
            LinearLayout.LayoutParams(dp(92), dp(46))
        )
        actions.addView(
            printButton,
            LinearLayout.LayoutParams(dp(84), dp(46)).apply {
                leftMargin = dp(4)
            }
        )
        header.addView(
            actions,
            LinearLayout.LayoutParams(-2, dp(46))
        )

        overlay.addView(
            header,
            LinearLayout.LayoutParams(-1, -2)
        )

        val pages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(24))
        }

        try {
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val maxWidth =
                        resources.displayMetrics.widthPixels - dp(24)

                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val scale =
                                maxWidth.toFloat() / page.width.toFloat()

                            val height =
                                (page.height * scale)
                                    .toInt()
                                    .coerceAtLeast(1)

                            val bitmap = Bitmap.createBitmap(
                                maxWidth,
                                height,
                                Bitmap.Config.ARGB_8888
                            )

                            bitmap.eraseColor(Color.WHITE)

                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )

                            val image = ImageView(this).apply {
                                setImageBitmap(bitmap)
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setBackgroundColor(Color.WHITE)
                            }

                            pages.addView(
                                image,
                                LinearLayout.LayoutParams(
                                    -1,
                                    -2
                                ).apply {
                                    if (index > 0) {
                                        topMargin = dp(12)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No se pudo visualizar el PDF.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(
                pages,
                FrameLayout.LayoutParams(-1, -2)
            )
        }

        overlay.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(-1, -1)
        )

        nativePdfOverlay = overlay
    }

    private fun sharePdfFile(file: File) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, fileUri(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir PDF"))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No se pudo abrir el menú para compartir.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun printPdfFile(file: File) {
        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = file.nameWithoutExtension.ifBlank { "ZGT Presupuesto" }

        manager.print(
            jobName,
            PdfFilePrintAdapter(file, file.name),
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun printWebUrl(urlString: String, jobName: String) {
        if (!isTrustedWorkshopUrl(urlString)) {
            Toast.makeText(this, "URL de impresión no permitida.", Toast.LENGTH_LONG).show()
            return
        }

        nativePrintWebView?.let {
            try {
                root.removeView(it)
            } catch (_: Exception) {}
            it.destroy()
        }

        val printView = WebView(this)
        nativePrintWebView = printView

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(printView, true)
        }

        printView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            userAgentString = webView?.settings?.userAgentString ?: userAgentString
        }

        printView.alpha = 0f
        root.addView(printView, FrameLayout.LayoutParams(1, 1))

        printView.webViewClient = object : WebViewClient() {
            private var printed = false

            override fun onPageFinished(view: WebView, url: String) {
                if (printed) return
                printed = true

                view.postDelayed({
                    try {
                        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                        val safeJobName = jobName.trim().ifBlank { "ZGT QR" }

                        /*
                         * El QR es una etiqueta física de 50 × 30 mm.
                         * Android usa milésimas de pulgada (mils):
                         * 50 mm = 1969 mils; 30 mm = 1181 mils.
                         */
                        val qrMediaSize = PrintAttributes.MediaSize(
                            "ZGT_50X30",
                            "Etiqueta 50 × 30 mm",
                            1969,
                            1181
                        )

                        manager.print(
                            safeJobName,
                            view.createPrintDocumentAdapter(safeJobName),
                            PrintAttributes.Builder()
                                .setMediaSize(qrMediaSize)
                                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                .setResolution(
                                    PrintAttributes.Resolution(
                                        "ZGT_300DPI",
                                        "300 dpi",
                                        300,
                                        300
                                    )
                                )
                                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                                .build()
                        )
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "No se pudo iniciar la impresión.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }, 800)
            }
        }

        printView.loadUrl(urlString)
    }

    private inner class ZGTNativeBridge {
        @JavascriptInterface
        fun appVersion(): String = "0.1.9"

        @JavascriptInterface
        fun openPdf(url: String, filename: String) {
            downloadAuthenticatedPdf(url, filename) { result ->
                runOnUiThread {
                    result.onSuccess(::openPdfFile)
                        .onFailure {
                            Toast.makeText(
                                this@MainActivity,
                                "No se pudo abrir el PDF.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }

        @JavascriptInterface
        fun sharePdf(url: String, filename: String) {
            downloadAuthenticatedPdf(url, filename) { result ->
                runOnUiThread {
                    result.onSuccess(::sharePdfFile)
                        .onFailure {
                            Toast.makeText(
                                this@MainActivity,
                                "No se pudo compartir el PDF.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }

        @JavascriptInterface
        fun printPdf(url: String, filename: String) {
            downloadAuthenticatedPdf(url, filename) { result ->
                runOnUiThread {
                    result.onSuccess(::printPdfFile)
                        .onFailure {
                            Toast.makeText(
                                this@MainActivity,
                                "No se pudo preparar el PDF para imprimir.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }

        @JavascriptInterface
        fun printUrl(url: String, jobName: String) {
            runOnUiThread {
                printWebUrl(url, jobName)
            }
        }
    }

    private class PdfFilePrintAdapter(
        private val file: File,
        private val documentName: String
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }

            callback?.onLayoutFinished(
                PrintDocumentInfo.Builder(documentName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build(),
                true
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            if (destination == null) {
                callback?.onWriteFailed("Destino de impresión no disponible.")
                return
            }

            thread {
                try {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onWriteCancelled()
                        return@thread
                    }

                    FileInputStream(file).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }

                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Exception) {
                    callback?.onWriteFailed(error.message)
                }
            }
        }
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

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        closeNativePdfViewer()
        nativePrintWebView?.destroy()
        webView?.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
