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

        myWebView.webViewClient = WebViewClient()

        // Manejo de Ventanas Emergentes (Login Google)
        myWebView.webChromeClient = object : WebChromeClient() {
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
                    // CONFIGURACIÓN PARA IMPRESORA 58mm (MP210)
                    // 203 DPI, 48mm ancho útil, 32 caracteres por línea
                    val printer = EscPosPrinter(connection, 203, 48f, 32)

                    // 3. DISEÑO DEL TICKET (ESC/POS Nativo)
                    // [C] = Centrado, [L] = Izquierda, [R] = Derecha
                    // <b> = Negrita
                    // <font size='big'> = Letra Doble Altura/Ancho

                    val textoTicket =
                        "[C]<b>CONTROL BASE TAXIS</b>\n" +
                                "[C]--------------------------------\n" +
                                "[L]\n" +
                                "[L]<b>FECHA:</b> $fecha\n" +
                                "[L]<b>HORA: </b> $hora\n" +
                                "[L]<b>UNIDAD:</b> $unidad\n" +
                                "[L]--------------------------------\n" +
                                "[L]<b>COBERTURA:</b>\n" +
                                "[L]$cobertura\n" +
                                "[L]--------------------------------\n" +
                                "[R]<font size='big'><b>TOTAL: $$monto</b></font>\n" +
                                "[L]\n" +
                                "[C]Cobrado por:\n" +
                                "[C]$usuario\n" +
                                "[C]--------------------------------\n" +
                                "[C]¡Gracias por su pago!\n" +
                                "\n\n\n" // Espacio para corte

                    printer.printFormattedText(textoTicket)

                    runOnUiThread {
                        Toast.makeText(mContext, "✅ Listo", Toast.LENGTH_SHORT).show()
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

        // Mantenemos la función vieja por compatibilidad si quieres, o puedes borrarla
        @JavascriptInterface
        fun imprimirTicket(base64String: String) {
            // ... (Lógica de imagen anterior, opcional) ...
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