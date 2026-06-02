package com.example.controltaxisimpresora

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val PERMISSION_BLUETOOTH = 1
    private lateinit var myWebView: WebView
    private var mWebviewPop: WebView? = null
    private lateinit var mContainer: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // UI Setup
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        mContainer = findViewById(android.R.id.content)

        checkPermissions()

        // WebView Setup
        myWebView = findViewById(R.id.webview)
        setupWebView()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(myWebView, true)
        myWebView.clearCache(true)
        myWebView.clearHistory()
        myWebView.loadUrl("https://taxis-control-f17c1.web.app")

        onBackPressedDispatcher.addCallback(this) {
            if (myWebView.canGoBack()) myWebView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
        }

        // Version Label
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<android.widget.TextView>(R.id.txtVersion).text = "v${pInfo.versionName}"
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = myWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        myWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url.toString()
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) return false
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        )
                    )
                    return true
                } catch (e: Exception) {
                    return true
                }
            }
        }

        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d(
                    "ANGULAR_LOG",
                    "${consoleMessage?.message()} -- Line ${consoleMessage?.lineNumber()}"
                )
                return true
            }

            // ... (El código de Popups de Google Login sigue aquí igual, lo omito para ahorrar espacio pero NO LO BORRES) ...
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                mWebviewPop = WebView(this@MainActivity)
                mWebviewPop?.isVerticalScrollBarEnabled = false
                mWebviewPop?.isHorizontalScrollBarEnabled = false
                mWebviewPop?.settings?.javaScriptEnabled = true
                mWebviewPop?.settings?.domStorageEnabled = true
                mWebviewPop?.settings?.userAgentString = settings.userAgentString
                mWebviewPop?.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        removeView(window)
                    }
                }
                mWebviewPop?.webViewClient = object : WebViewClient() {}
                mWebviewPop?.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                mContainer.addView(mWebviewPop)
                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = mWebviewPop
                resultMsg?.sendToTarget()
                return true
            }
        }
        myWebView.addJavascriptInterface(WebAppInterface(this), "AndroidPrint")

        // =========================================================================
        // HABILITAR DESCARGAS DE ARCHIVOS (NORMALES Y BLOBS)
        // =========================================================================
        myWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->

            // CASO 1: Es un archivo generado en memoria por Angular (blob: o data:)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                runOnUiThread {
                    Toast.makeText(
                        applicationContext,
                        "⏳ Procesando PDF...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Inyectamos JavaScript para convertir el archivo en texto Base64 y mandarlo a Android
                myWebView.evaluateJavascript(
                    """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function(e) {
                            if (this.status == 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blob);
                                reader.onloadend = function() {
                                    var base64data = reader.result;
                                    AndroidPrint.guardarDescargaBase64(base64data, '$mimetype');
                                }
                            }
                        };
                        xhr.send();
                    })();
                """.trimIndent(), null
                )

                return@setDownloadListener // Detenemos la ejecución aquí
            }

            // CASO 2: Es un enlace normal de internet (https://...)
            try {
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                request.setMimeType(mimetype)
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Descargando archivo...")

                val fileName =
                    android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setTitle(fileName)
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                Toast.makeText(applicationContext, "Descargando archivo...", Toast.LENGTH_SHORT)
                    .show()

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    applicationContext,
                    "No se pudo iniciar la descarga.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun removeView(view: WebView?) {
        if (mWebviewPop != null) {
            mContainer.removeView(mWebviewPop)
            mWebviewPop?.destroy()
            mWebviewPop = null
        }
    }

    // =========================================================================
    // CLASE PUENTE: SILENCIOSA (SOLO AVISA A ANGULAR)
    // =========================================================================
    inner class WebAppInterface(private val mContext: Context) {

        @JavascriptInterface
        fun imprimirTicketData(jsonString: String) {
            // Android recibe la orden y se pone a trabajar en segundo plano
            // No muestra NADA en la pantalla del celular (Angular ya tiene el Swal abierto)
            procesarImpresion(jsonString)
        }

        private fun procesarImpresion(jsonString: String) {
            Thread {
                try {
                    val json = JSONObject(jsonString)
                    // ... (Parseo de variables igual que siempre) ...
                    val unidad = json.optString("unidad", "N/A")
                    val montoRaw = json.optDouble("monto", 0.0)
                    val monto = String.format("%.2f", montoRaw)
                    val fecha = json.optString("fecha", "")
                    val hora = json.optString("hora", "")
                    val cobertura = json.optString("cobertura", "")
                    val usuario = json.optString("usuario", "")

                    var cobParte1 = cobertura
                    var cobParte2 = ""
                    if (cobertura.contains(" hasta ")) {
                        val partes = cobertura.split(" hasta ")
                        cobParte1 = partes[0]
                        cobParte2 = "hasta " + partes[1]
                    }

                    val textoTicket =
                        "[C]<b>CONTROL BASE TAXIS</b>\n" +
                                "[C]--------------------------------\n" +
                                "[L]\n" +
                                "[L]FECHA: <b>$fecha</b>\n" +
                                "[L]HORA:  <b>$hora</b>\n" +
                                "[L]--------------------------------\n" +
                                "[C]COBERTURA:\n" +
                                "[C]<b>$cobParte1</b>\n" +
                                (if (cobParte2.isNotEmpty()) "[C]<b>$cobParte2</b>\n" else "") +
                                "[L]--------------------------------\n" +
                                "[L]\n" +
                                "[C]<font size='big'>UNIDAD: <b>$unidad</b></font>\n" +
                                "[C]<font size='big'>TOTAL: <b>$$monto</b></font>\n" +
                                "[L]\n" +
                                "[C]Cobrado por:\n" +
                                "[C]<b>$usuario</b>\n" +
                                "[C]--------------------------------\n" +
                                "[C]¡Gracias por su pago!\n" +
                                "\n\n"

                    // INTENTO DE CONEXIÓN
                    val connection = BluetoothPrintersConnections.selectFirstPaired()
                    if (connection == null) {
                        throw Exception("No hay impresora vinculada.")
                    }

                    if (!connection.isConnected) {
                        connection.connect() // Aquí se trabará 10-15s si falla, pero el Swal seguirá girando
                    }

                    val printer = EscPosPrinter(connection, 203, 48f, 32)
                    printer.printFormattedText(textoTicket)

                    Thread.sleep(500)
                    connection.disconnect()

                    // --- EXITO: AVISAMOS A ANGULAR ---
                    runOnUiThread {
                        myWebView.evaluateJavascript("window.onPrinterSuccess()", null)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    // --- ERROR: AVISAMOS A ANGULAR ---
                    // Pasamos el mensaje de error para que Angular decida qué mostrar
                    val cleanError = e.message?.replace("'", "") ?: "Error desconocido"
                    runOnUiThread {
                        myWebView.evaluateJavascript("window.onPrinterError('$cleanError')", null)
                    }
                }
            }.start()
        }

        // =========================================================================
        // NUEVO: RECIBE EL BASE64 DE JAVASCRIPT Y LO GUARDA EN EL TELÉFONO
        // =========================================================================
        @RequiresApi(Build.VERSION_CODES.Q)
        @JavascriptInterface
        fun guardarDescargaBase64(base64Data: String, mimeType: String) {
            try {
                // Limpiamos el texto para dejar solo los datos crudos
                val base64String = base64Data.substring(base64Data.indexOf(",") + 1)
                val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)

                // Le damos un nombre al archivo
                val extension = if (mimeType.contains("pdf")) ".pdf" else if (mimeType.contains("excel") || mimeType.contains("spreadsheet")) ".xlsx" else ".bin"
                val fileName = "Reporte_${System.currentTimeMillis()}$extension"

                // Lógica moderna de Android (API 29+) para guardar en carpeta "Descargas" sin pedir permisos extra
                val resolver = mContext.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(decodedBytes)
                    }
                    this@MainActivity.runOnUiThread {
                        Toast.makeText(mContext, "✅ Reporte guardado en Descargas", Toast.LENGTH_LONG).show()
                    }
                } else {
                    this@MainActivity.runOnUiThread {
                        Toast.makeText(mContext, "❌ No se pudo crear el archivo", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                this@MainActivity.runOnUiThread {
                    Toast.makeText(mContext, "❌ Error al guardar el archivo", Toast.LENGTH_SHORT).show()
                }
            }
        }

    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), PERMISSION_BLUETOOTH)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN), PERMISSION_BLUETOOTH)
            }
        }
    }
}