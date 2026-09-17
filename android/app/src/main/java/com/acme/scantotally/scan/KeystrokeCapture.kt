package com.acme.scantotally.scan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Catches a scanner that behaves like a keyboard.
 *
 * Rugged devices ship with DataWedge in KEYSTROKE mode: the scanner enters the
 * barcode into whatever has focus, then usually presses Enter. That is why a
 * scan lands in Excel but vanishes in an app with no text field.
 *
 * Two things this has to survive, both learned on a real Zebra:
 *
 *  1. The app asks DataWedge for intent output, which is cleaner, but that
 *     request can silently fail to apply. The only symptom is a scanner that
 *     looks dead in this app alone.
 *
 *  2. Keystroke input does NOT necessarily arrive as key events. DataWedge can
 *     deliver it through an input method, which commits text without ever
 *     firing onKeyListener. Watching for key presses catches nothing.
 *
 * So this watches the TEXT, not the keys, and ends a scan on either a
 * terminator character or a short quiet period. A scanner delivers a whole
 * barcode in a few milliseconds; a gap means it has finished. That works
 * whether the terminator is Enter, Tab, or absent entirely.
 */
private const val QUIET_PERIOD_MS = 120L

/**
 * Puts the on-screen keyboard away.
 *
 * showSoftInputOnFocus = false asks for it not to appear, and on a rugged
 * handset running DataWedge in keystroke mode that is not always honoured --
 * the scanner's own input method can raise it anyway. So it is dismissed
 * explicitly whenever this field takes focus, which is the moment it appears.
 *
 * There is no text field on the home screen at all, so a keyboard there is
 * always wrong: it covers half the screen and nothing can be typed into it.
 */
private fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

@Composable
fun KeystrokeScanner(
    enabled: Boolean = true,
    onScan: (String) -> Unit,
) {
    if (!enabled) return

    AndroidView(
        modifier = Modifier.size(1.dp).alpha(0f),
        factory = { ctx ->
            val handler = Handler(Looper.getMainLooper())

            EditText(ctx).apply {
                // Focusable and focused, but the on-screen keyboard stays down.
                showSoftInputOnFocus = false
                isFocusableInTouchMode = true
                isCursorVisible = false
                setBackgroundColor(0)
                imeOptions = EditorInfo.IME_ACTION_DONE or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

                var emitting = false

                fun flush() {
                    if (emitting) return
                    val payload = text?.toString()?.trim().orEmpty()
                    if (payload.isEmpty()) return
                    emitting = true
                    setText("")
                    emitting = false
                    onScan(payload)
                }

                val flushRunnable = Runnable { flush() }

                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (emitting) return
                        handler.removeCallbacks(flushRunnable)

                        val current = s?.toString().orEmpty()
                        // A terminator means the scanner has finished; no need
                        // to wait out the quiet period.
                        val cut = current.indexOfFirst { it == '\n' || it == '\r' || it == '\t' }
                        if (cut >= 0) {
                            val payload = current.substring(0, cut).trim()
                            emitting = true
                            s?.clear()
                            emitting = false
                            if (payload.isNotEmpty()) onScan(payload)
                            return
                        }
                        // Otherwise wait for the input to go quiet.
                        handler.postDelayed(flushRunnable, QUIET_PERIOD_MS)
                    }
                })

                // Hardware key events too, for devices that do send them.
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                            keyCode == KeyEvent.KEYCODE_TAB)
                    ) {
                        handler.removeCallbacks(flushRunnable)
                        flush()
                        true
                    } else {
                        false
                    }
                }

                setOnEditorActionListener { _, _, _ ->
                    handler.removeCallbacks(flushRunnable)
                    flush()
                    true
                }

                // A scanner is useless if focus drifts, and in a Compose screen
                // it drifts for all sorts of reasons. Take it back -- and put
                // the keyboard away each time, because taking focus is exactly
                // what raises it.
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) v.hideKeyboard() else v.post { v.requestFocus() }
                }

                requestFocus()
                hideKeyboard()
            }
        },
        update = {
            if (!it.hasFocus()) it.requestFocus()
            it.hideKeyboard()
        },
    )

    DisposableEffect(Unit) { onDispose { } }
}

/**
 * Lets a real text field take the keyboard back.
 *
 * The hidden capture field above holds focus permanently and reclaims it the
 * moment anything else takes it -- which is what makes a scanner work on a
 * screen with no input, and what made manual entry impossible to type into:
 * every tap on a real field handed focus straight back, with the soft keyboard
 * suppressed for good measure.
 *
 * So any screen or dialog that asks a human to type declares it here, and the
 * capture field steps aside for as long as that screen is composed. Hardware
 * scanning continues through the DataWedge intent path regardless; only the
 * keyboard-wedge fallback pauses.
 */
object KeyboardInputGuard {
    private val holders = mutableIntStateOf(0)
    val suspended: Boolean get() = holders.intValue > 0

    fun acquire() { holders.intValue++ }
    fun release() { if (holders.intValue > 0) holders.intValue-- }
}

/** Declares that this composable contains a field a human types into. */
@Composable
fun SuspendScanCapture() {
    DisposableEffect(Unit) {
        KeyboardInputGuard.acquire()
        onDispose { KeyboardInputGuard.release() }
    }
}
