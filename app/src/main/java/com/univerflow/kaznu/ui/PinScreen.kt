package com.univerflow.kaznu.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.PinPolicy

/**
 * A full-screen PIN pad like a banking app: dots, an own numeric keypad (so no keyboard app sees the digits), an
 * optional biometric key and "forgot" link. It only collects digits; the owner decides what a PIN means.
 */
@SuppressLint("ViewConstructor")
class PinScreen(context: Context, private val describe: (Int) -> String) : FrameLayout(context) {
    private val digits = CharArray(PinPolicy.LENGTH)
    private var count = 0
    private var inputEnabled = true
    private var succeeded = false
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val message = TextView(context)
    private val dotRow = LinearLayout(context)
    private val dotViews = ArrayList<View>()
    private val keys = ArrayList<MaterialButton>()
    private val biometricKey: MaterialButton
    private val backspaceKey: MaterialButton
    private val forgot = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle)
    private val close = ImageButton(context)

    val isCloseable get() = close.isVisible
    var onComplete: ((CharArray) -> Unit)? = null
    var onBiometric: (() -> Unit)? = null
    var onForgot: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
    private val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    private val onSurfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
    private val keyFill = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.LTGRAY)
    private val error = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, Color.RED)
    private val success = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, Color.GREEN)

    init {
        setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE))
        isClickable = true; isFocusable = true
        // This marks only the pad itself; LockUi hides the views beside it, so nothing underneath is readable by an
        // accessibility service while this screen is up.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY)) }
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_lock); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                imageTintList = ColorStateList.valueOf(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK))
            }, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        column.addView(badge, LinearLayout.LayoutParams(dp(64), dp(64)))
        TextViewCompat.setTextAppearance(title, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        title.setTextColor(onSurface); title.gravity = Gravity.CENTER
        ViewCompat.setAccessibilityHeading(title, true)
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        TextViewCompat.setTextAppearance(subtitle, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        subtitle.setTextColor(onSurfaceVariant); subtitle.gravity = Gravity.CENTER
        subtitle.minLines = 2
        column.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        dotRow.orientation = LinearLayout.HORIZONTAL; dotRow.gravity = Gravity.CENTER
        dotRow.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        repeat(PinPolicy.LENGTH) {
            val dot = View(context)
            dotViews += dot; dotRow.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8); marginEnd = dp(8) })
        }
        column.addView(dotRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })

        TextViewCompat.setTextAppearance(message, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        message.gravity = Gravity.CENTER; message.minLines = 2; message.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        column.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(8) })

        biometricKey = key("", describe(R.string.lock_biometric_button)) { onBiometric?.invoke() }.apply { setIconResource(R.drawable.ic_fingerprint); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconPadding = 0; iconSize = dp(30); iconTint = ColorStateList.valueOf(primary) }
        backspaceKey = key("", describe(R.string.lock_backspace)) { backspace() }.apply { setIconResource(R.drawable.ic_backspace); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconPadding = 0; iconSize = dp(28); iconTint = ColorStateList.valueOf(onSurface) }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        val pad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        rows.forEach { row ->
            pad.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { digit -> addView(key(digit, digit) { add(digit[0]) }, cell()) }
            })
        }
        pad.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(biometricKey, cell()); addView(key("0", "0") { add('0') }, cell()); addView(backspaceKey, cell())
        })
        column.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        forgot.setTextColor(primary); forgot.minHeight = dp(48)
        forgot.setOnClickListener { onForgot?.invoke() }
        column.addView(forgot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        addView(ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        close.setImageResource(R.drawable.ic_close)
        close.setBackgroundResource(android.R.color.transparent); close.imageTintList = ColorStateList.valueOf(onSurface)
        close.setOnClickListener { onClose?.invoke() }
        addView(close, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { topMargin = dp(8); marginStart = dp(8) })
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        paintDots()
    }

    private fun cell() = LinearLayout.LayoutParams(dp(84), dp(84)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

    private fun key(label: String, description: String, action: () -> Unit) = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = label; textSize = 28f; setTextColor(onSurface); contentDescription = description
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        insetTop = 0; insetBottom = 0; setPadding(0, 0, 0, 0)
        cornerRadius = dp(42)
        backgroundTintList = if (label.isEmpty()) ColorStateList.valueOf(Color.TRANSPARENT) else ColorStateList.valueOf(keyFill)
        setOnClickListener { if (inputEnabled) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action() } }
        keys += this
    }

    /** Title, hint, and which extra controls are offered. */
    fun configure(titleText: String, hint: String?, closeable: Boolean, biometric: Boolean, forgotText: String?) {
        Motion.text(title, titleText)
        // The hint keeps its two lines when empty, so the keypad does not jump between the steps.
        subtitle.text = hint.orEmpty()
        close.isVisible = closeable; close.contentDescription = describe(R.string.lock_close)
        biometricKey.isVisible = biometric; biometricKey.isEnabled = biometric
        // The place stays so the zero does not move between screens.
        biometricKey.visibility = if (biometric) View.VISIBLE else View.INVISIBLE
        forgot.isVisible = forgotText != null; forgot.text = forgotText.orEmpty()
        showMessage(null)
    }

    fun showMessage(text: String?, isError: Boolean = true) {
        message.text = text.orEmpty()
        message.setTextColor(if (isError) error else onSurfaceVariant)
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        keys.forEach { Motion.dim(it, if (enabled) 1f else 0.4f) }
    }

    /** Empties the entered digits (nothing is kept anywhere). */
    fun reset() { digits.fill('\u0000'); count = 0; succeeded = false; paintDots() }

    /** The right PIN: every dot takes the success colour as the screen leaves, so the entry is seen to have worked. */
    fun succeed() { setInputEnabled(false); succeeded = true; paintDots() }

    fun shake() {
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        if (!Motion.active) return
        ObjectAnimator.ofFloat(dotRow, View.TRANSLATION_X, 0f, -dp(14).toFloat(), dp(14).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).apply { duration = 320 }.start()
    }

    private fun add(digit: Char) {
        if (count >= PinPolicy.LENGTH) return
        digits[count++] = digit
        paintDots()
        Motion.pop(dotViews[count - 1])
        if (count == PinPolicy.LENGTH) {
            val pin = digits.copyOf()
            digits.fill('\u0000'); count = 0
            onComplete?.invoke(pin)
            // The dots clear a moment after the last one fills, so the entry is seen to complete.
            postDelayed({ paintDots() }, 120)
        }
    }

    private fun backspace() { if (count > 0) { digits[--count] = '\u0000'; paintDots() } }

    private fun paintDots() {
        dotViews.forEachIndexed { index, dot ->
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    succeeded -> setColor(success)
                    index < count -> setColor(primary)
                    else -> { setColor(Color.TRANSPARENT); setStroke(dp(2), onSurfaceVariant) }
                }
            }
        }
        dotRow.contentDescription = describe(R.string.lock_pin_progress).format(count, PinPolicy.LENGTH)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The drawer next to this screen consumes the insets, so they are read from the window itself.
        ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?.let { setPadding(it.left, it.top, it.right, it.bottom) }
    }

    override fun onDetachedFromWindow() { digits.fill('\u0000'); count = 0; super.onDetachedFromWindow() }
}
