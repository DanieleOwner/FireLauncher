package dev.firelauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dopo Home, Fire OS fa aspettare ~5 secondi prima di lasciar aprire FireLauncher.
 * Nel frattempo copro la home Amazon con una schermata nello stile del launcher
 * (finestra sopra le altre app: permesso SYSTEM_ALERT_WINDOW concesso via ADB).
 * Sparisce appena FireLauncher è in primo piano, o comunque dopo 8 secondi.
 */
object HomeOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private val autoHide = Runnable { hide() }

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    @Suppress("DEPRECATION")
    fun show(context: Context) {
        if (view != null || !canShow(context)) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Prende il focus: i tasti premuti durante l'attesa non finiscono nella home Amazon.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        )
        val v = build(app)
        runCatching { manager.addView(v, params) }.onSuccess {
            view = v
            wm = manager
            handler.postDelayed(autoHide, 8_000)
        }
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(200).withEndAction {
            runCatching { wm?.removeView(v) }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun build(context: Context): View {
        fun text(size: Float, color: Int, value: String) = TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
        val now = Date()
        // Le impostazioni sono in SharedPreferences: qui non c'è Compose, le leggo direttamente.
        val en = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            .getString("uiLanguage", null) == "EN"
        val date = if (en) SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH) else SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(72f, 0xFFF1EFE8.toInt(), SimpleDateFormat("HH:mm", Locale.ITALIAN).format(now)))
            addView(text(16f, 0xFFAFA9EC.toInt(), date.format(now)))
            addView(text(13f, 0xFF7F77DD.toInt(), if (en) "Going home…" else "Torno alla home…").apply { setPadding(0, 40, 0, 0) })
        }
        return FrameLayout(context).apply {
            setBackgroundColor(0xFF1B1838.toInt())
            val pad = (48 * resources.displayMetrics.density).toInt()
            addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                ).apply { marginStart = pad },
            )
            isFocusable = true
            isFocusableInTouchMode = true
            // Ingoio i tasti durante l'attesa (tranne Home, che il sistema gestisce comunque).
            setOnKeyListener { _, _, _ -> true }
        }.also { it.setBackgroundColor(Color.rgb(0x1B, 0x18, 0x38)) }
    }
}
