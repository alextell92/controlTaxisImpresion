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

    // Variables para el Popup de Login de Google
    private var mWebviewPop: WebView? = null
    private lateinit var mContainer: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
// Oculta tanto la barra de estado como la de navegación
        controller.hide(WindowInsetsCompat.Type.systemBars())
// Hace que aparezcan solo si el usuario desliza desde el borde
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Contenedor para ventanas emergentes
        mContainer = findViewById(android.R.id.content)

        // 1. Pedir Permisos
        checkPermissions()

        // 2. Configurar WebView
        myWebView = findViewById(R.id.webview)
        setupWebView()

        // 3. Cookies (Vital para Auth)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(myWebView, true)

        myWebView.clearCache(true)
        myWebView.clearHistory()

        // 4. Cargar tu URL
        // ¡¡¡ REEMPLAZA ESTO CON TU URL REAL DE FIREBASE !!!
        myWebView.loadUrl("https://taxis-control-f17c1.web.app")

        // 5. Botón Atrás
        onBackPressedDispatcher.addCallback(this) {
            if (myWebView.canGoBack()) {
                myWebView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = myWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true) // Permitir popups de login

        // Truco para evitar error 403 de Google
        val defaultUserAgent = settings.userAgentString
        settings.userAgentString = defaultUserAgent.replace("; wv", "")

        // REEMPLAZA LA LÍNEA 90 (myWebView.webViewClient = WebViewClient()) POR ESTO:

        myWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url.toString()

                // 1. Si es tu página web normal, deja que cargue
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return false
                }

                // 2. Si es un protocolo externo (mailto, tel, rawbt, whatsapp), ábrelo fuera del WebView
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    // Si no tiene la app instalada (ej. RawBT), no hacemos nada para que no truene la app
                    return true
                }
            }
        }

        // Manejo de Ventanas Emergentes (Login Google)
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("ANGULAR_LOG", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
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

        // Inyectamos el puente
        myWebView.addJavascriptInterface(WebAppInterface(this), "AndroidPrint")
    }

    fun removeView(view: WebView?) {
        if (mWebviewPop != null) {
            mContainer.removeView(mWebviewPop)
            mWebviewPop?.destroy()
            mWebviewPop = null
        }
    }

    // --- CLASE PUENTE: RECIBE JSON E IMPRIME TEXTO NATIVO ---
    inner class WebAppInterface(private val mContext: Context) {

        // Angular llama a esta función enviando un string JSON
        @JavascriptInterface
        fun imprimirTicketData(jsonString: String) {

            try {
                runOnUiThread {
                    Toast.makeText(mContext, "🖨️ Imprimiendo Ticket...", Toast.LENGTH_SHORT).show()
                }

                // 1. Parsear JSON
                val json = JSONObject(jsonString)
                val unidad = json.optString("unidad", "N/A")
                // Formateamos monto a 2 decimales si viene como número
                val montoRaw = json.optDouble("monto", 0.0)
                val monto = String.format("%.2f", montoRaw)

                val fecha = json.optString("fecha", "")
                val hora = json.optString("hora", "")
                val cobertura = json.optString("cobertura", "")
                val usuario = json.optString("usuario", "")

                // 2. Conectar Impresora
                val connection = BluetoothPrintersConnections.selectFirstPaired()

                if (connection != null) {

                    try {
                        // 1. Intentar conectar (con un pequeño reintento interno)
                        if (!connection.isConnected) {
                            try {
                                connection.connect()
                            } catch (e: Exception) {
                                // Si falla el primer intento, esperamos medio segundo y reintentamos "despertarla"
                                Thread.sleep(500)
                                connection.disconnect() // Limpiamos cualquier rastro previo
                                connection.connect()
                            }
                        }

                    // CONFIGURACIÓN PARA IMPRESORA 58mm (MP210)
                    // 203 DPI, 48mm ancho útil, 32 caracteres por línea
                    val printer = EscPosPrinter(connection, 203, 48f, 32)

                    // 3. DISEÑO DEL TICKET (ESC/POS Nativo)
                    // [C] = Centrado, [L] = Izquierda, [R] = Derecha
                    // <b> = Negrita
                    // <font size='big'> = Letra Doble Altura/Ancho

                        // ... (Tu código de parseo JSON anterior) ...
                        val fecha = json.optString("fecha", "")
                        val hora = json.optString("hora", "")
                        val cobertura = json.optString("cobertura", "")
                        val usuario = json.optString("usuario", "")
                        val unidad = json.optString("unidad", "N/A") // Asegúrate de tener esta variable

                        // -----------------------------------------------------------
                        // 1. TRUCO PARA LA COBERTURA EN DOS RENGLONES
                        // -----------------------------------------------------------
                        // Buscamos la palabra " hasta " y la reemplazamos por un Enter (\n) + "hasta "
                        // Así la impresora bajará el texto automáticamente.
                        var cobParte1 = cobertura
                        var cobParte2 = ""

                        // Si la frase contiene " hasta ", la partimos en dos
                        if (cobertura.contains(" hasta ")) {
                            val partes = cobertura.split(" hasta ")
                            cobParte1 = partes[0]             // Ej: "desde 01/ene/2026"
                            cobParte2 = "hasta " + partes[1]  // Ej: "hasta 31/ene/2026"
                        }

                        // -----------------------------------------------------------
                        // 2. DISEÑO DEL TICKET
                        // -----------------------------------------------------------
                        val textoTicket =
                            "[C]<b>CONTROL BASE TAXIS</b>\n" +
                                    "[C]--------------------------------\n" +
                                    "[L]\n" +
                                    "[L]FECHA: <b>$fecha</b>\n" +
                                    "[L]HORA:  <b>$hora</b>\n" +
                                    "[L]--------------------------------\n" +

                                    // --- COBERTURA CENTRADA EN DOS LÍNEAS ---
                                    "[C]COBERTURA:\n" +
                                    "[C]<b>$cobParte1</b>\n" +
                                    // Solo imprimimos la segunda línea si existe (para evitar líneas vacías)
                                    (if (cobParte2.isNotEmpty()) "[C]<b>$cobParte2</b>\n" else "") +
                                    // ----------------------------------------

                                    "[L]--------------------------------\n" +
                                    "[L]\n" +
                                    "[C]<font size='big'>UNIDAD: <b>$unidad</b></font>\n" +
                                    "[C]<font size='big'>TOTAL: <b>$$monto</b></font>\n" +
                                    "[L]\n" +
                                    "[C]Cobrado por:\n" +
                                    "[C]<b>$usuario</b>\n" +
                                    "[C]--------------------------------\n" +
                                    "[C]¡Gracias por su pago!\n" +
                                    "\n"

                    printer.printFormattedText(textoTicket)

// 3. ¡IMPORTANTE! Desconectar manualmente para liberar el socket
                        // Esto evita que la siguiente impresión encuentre el canal "ocupado"
                        Thread.sleep(500) // Damos tiempo a que termine de enviar el buffer
                        connection.disconnect()
                    runOnUiThread {
                        Toast.makeText(mContext, "✅ Listo", Toast.LENGTH_SHORT).show()
                    }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Si algo falla, intentamos desconectar para no dejar el puerto bloqueado
                        connection.disconnect()

                        runOnUiThread {
                            // Si después de los reintentos internos falló, avisamos a Angular
                            myWebView.evaluateJavascript("window.onPrinterError('Error de conexión: ${e.message}');", null)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(mContext, "⚠️ No hay impresora vinculada", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(mContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }


    }

    // --- PERMISOS DE ANDROID ---
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                ), PERMISSION_BLUETOOTH)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN), PERMISSION_BLUETOOTH)
            }
        }
    }
}