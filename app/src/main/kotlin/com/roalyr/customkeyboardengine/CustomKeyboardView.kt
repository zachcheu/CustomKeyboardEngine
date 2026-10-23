package com.roalyr.customkeyboardengine

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnKeyboardActionListener {
        fun onKey(code: Int?, label: CharSequence?)

        /** Moves the cursor by [dx] characters and [dy] lines; extends the selection when [select] is true. */
        fun onCursorMove(dx: Int, dy: Int, select: Boolean) {}

        /** Clears the selection and leaves the cursor at the moving end of it. */
        fun onSelectionCollapse() {}

        /** Voice key touched. */
        fun onVoiceDown() {}

        /** Voice key swiped up while held: keep listening after release. */
        fun onVoiceLock() {}

        /** Voice key released. */
        fun onVoiceUp() {}
    }

    private enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    /** Per-pointer gesture state. Coordinates are raw view pixels. */
    private class PointerGesture(
        val key: Key,
        val startX: Float,
        val startY: Float,
    ) {
        var stepOriginX = startX
        var stepOriginY = startY
        var hasDragged = false
        var swipe: SwipeDirection? = null
        var voiceLocked = false
    }

    private val gestures = mutableMapOf<Int, PointerGesture>()

    /** True while voice input is listening; the voice key is drawn highlighted. */
    var isVoiceActive = false
        set(value) {
            field = value
            invalidate()
        }

    // Keyboard data.
    var keyboard: CustomKeyboard? = null
    private var keys: List<Key>? = null

    // Drawing.
    private var buffer: Bitmap? = null
    private var paint: Paint = Paint()
    private val iconCache = mutableMapOf<String, Drawable?>()

    // Scaling factors for rendering.
    private var scaleX = 1f
    private var scaleY = 1f

    // Logical dimensions of the keyboard (unscaled)
    private var logicalWidth = 0f
    private var logicalHeight = 0f

    // Rendered dimensions of the keyboard (scaled for display)
    private var renderedWidth = 0f
    private var renderedHeight = 0f

    // Settings from SettingsManager
    private var settings: KeyboardSettings = KeyboardSettings()

    private var keyboardActionListener: OnKeyboardActionListener? = null
    private var isShiftOn = false
    private var isCtrlOn = false
    private var isAltOn = false
    private var isCapsLockOn = false
    private var isKeyRepeated = false
    private var isLongPressHandled = false
    private var downKeyIndex = -1

    private var repeatKeyRunnable: Runnable? = null

    private val activeKeys = mutableMapOf<Int, Key?>() // Track active keys by pointer ID

    // Define specific meta key codes for shifting case.
    private val metaKeyCodes = arrayOf(
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK
    )

    companion object {
        private const val TAG = "CustomKeyboardView"
        private const val MSG_LONGPRESS = 1
        private const val LONGPRESS_TIMEOUT = 250
        private const val REPEAT_DELAY = 50
        private const val REPEAT_START_DELAY = 250
    }

    // INIT
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = 255
    }

    fun updateKeyboard(keyboard: CustomKeyboard) {
        this.keyboard = keyboard
        keys = keyboard.getAllKeys()
        invalidateAllKeys()
    }

    /**
     * Updates the keyboard settings used for rendering.
     * @param settings The [KeyboardSettings] to apply.
     */
    fun updateSettings(settings: KeyboardSettings) {
        this.settings = settings
        invalidate()
    }

    // Handle events
    private val handlerCallback = Handler.Callback { msg ->
        if (msg.what == MSG_LONGPRESS) {
            val key = msg.obj as? Key
            if (key != null && !isLongPressHandled) {
                handleLongPress(key)
            }
            true
        } else {
            false
        }
    }

    private val handler = Handler(Looper.getMainLooper(), handlerCallback)

    private fun handleTouchDown(x: Int, y: Int, pointerId: Int, rawX: Float, rawY: Float) {
        val correctedX = x + settings.touchCorrectionX.toInt()
        val correctedY = y + settings.touchCorrectionY.toInt()
        val key = keyboard?.getKeyAt(correctedX.toFloat(), correctedY.toFloat()) ?: return

        downKeyIndex = keys?.indexOf(key) ?: -1
        isLongPressHandled = false
        isKeyRepeated = false
        gestures[pointerId] = PointerGesture(key, rawX, rawY)

        if (key.keyCode == Constants.KEYCODE_CURSOR_PAD || key.keyCode == Constants.KEYCODE_SELECTION_PAD) {
            // Drag keys have no tap, repeat or long-press timers.
        } else if (key.keyCode == Constants.KEYCODE_VOICE_INPUT) {
            keyboardActionListener?.onVoiceDown()
        } else if (key.isRepeatable == true) {
            startKeyRepeat(key)
        } else {
            // Schedule long press for non-repeatable keys
            scheduleLongPress(key)
        }

        // Save key reference in the activeKeys map
        activeKeys[pointerId] = key
    }


    private fun handleTouchUp(pointerId: Int) {
        cancelRepeatKey()
        handler.removeMessages(MSG_LONGPRESS)

        // Retrieve the key associated with this pointer ID
        val key = activeKeys[pointerId]
        val gesture = gestures.remove(pointerId)
        val swipe = gesture?.swipe

        if (key != null) {
            val it = key
            when {
                it.keyCode == Constants.KEYCODE_CURSOR_PAD -> Unit
                it.keyCode == Constants.KEYCODE_SELECTION_PAD -> {
                    if (gesture?.hasDragged != true) keyboardActionListener?.onSelectionCollapse()
                }
                it.keyCode == Constants.KEYCODE_VOICE_INPUT -> keyboardActionListener?.onVoiceUp()
                swipe != null -> {
                    val (code, label) = swipeAction(it, swipe)
                    keyboardActionListener?.onKey(code, label)
                }
                isLongPressHandled || isKeyRepeated -> Unit
                else -> keyboardActionListener?.onKey(it.keyCode, it.label)
            }
        }

        // Reset states for this pointer
        isLongPressHandled = false
        isKeyRepeated = false
        activeKeys.remove(pointerId)
    }



    private fun handleLongPress(key: Key) {
        isLongPressHandled = true
        keyboardActionListener?.onKey(key.keyCodeLongPress, key.labelLongPress)
    }

    private fun scheduleLongPress(key: Key) {
        handler.obtainMessage(MSG_LONGPRESS, key).let {
            handler.sendMessageDelayed(it, LONGPRESS_TIMEOUT.toLong())
        }
    }

    private fun startKeyRepeat(key: Key) {
        repeatKeyRunnable = object : Runnable {
            override fun run() {
                isKeyRepeated = true // Mark repeat triggered
                keyboardActionListener?.onKey(key.keyCode, key.label)
                handler.postDelayed(this, REPEAT_DELAY.toLong())
            }
        }
        handler.postDelayed(repeatKeyRunnable!!, REPEAT_START_DELAY.toLong())
    }

    private fun cancelRepeatKey() {
        repeatKeyRunnable?.let {
            handler.removeCallbacks(it)
        }
        repeatKeyRunnable = null
    }

    fun cancelAllEvents() {
        if (gestures.values.any { it.key.keyCode == Constants.KEYCODE_VOICE_INPUT }) {
            keyboardActionListener?.onVoiceUp()
        }
        cancelRepeatKey()
        handler.removeMessages(MSG_LONGPRESS)
        isKeyRepeated = false
        isLongPressHandled = false
        activeKeys.clear()
        gestures.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllEvents()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            cancelAllEvents()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            cancelAllEvents()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        return super.dispatchTouchEvent(event)
    }

    private fun isMetaKeyToggled(code: Int?): Boolean {
        return when (code) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> isShiftOn
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> isCtrlOn
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> isAltOn
            KeyEvent.KEYCODE_CAPS_LOCK -> isCapsLockOn
            else -> false
        }
    }


    fun setOnKeyboardActionListener(listener: OnKeyboardActionListener) {
        keyboardActionListener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked // Use actionMasked for multi-touch support
        val pointerIndex = event.actionIndex // Index of the pointer causing the event
        val pointerId = event.getPointerId(pointerIndex)

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Handle new touch
                val rawX = event.getX(pointerIndex)
                val rawY = event.getY(pointerIndex)
                handleTouchDown((rawX / scaleX).toInt(), (rawY / scaleY).toInt(), pointerId, rawX, rawY)
                performClick()
                //Log.d("TOUCH", "Pointer Down: Index $pointerIndex, x=$scaledX, y=$scaledY")
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // Handle touch release
                val scaledX = (event.getX(pointerIndex) / scaleX).toInt()
                val scaledY = (event.getY(pointerIndex) / scaleY).toInt()
                handleTouchUp(pointerId)
                performClick()
                //Log.d("TOUCH", "Pointer Up: Index $pointerIndex, x=$scaledX, y=$scaledY")
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handleTouchMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Handle cancel action if necessary
                cancelAllEvents()
            }
        }
        return true
    }


    override fun performClick(): Boolean {
        // Call the superclass implementation (important for accessibility events)
        super.performClick()
        return true
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun handleTouchMove(pointerId: Int, x: Float, y: Float) {
        val gesture = gestures[pointerId] ?: return
        val key = gesture.key
        when (key.keyCode) {
            Constants.KEYCODE_CURSOR_PAD, Constants.KEYCODE_SELECTION_PAD -> {
                val step = dpToPx(Constants.DRAG_STEP_DP)
                val dx = ((x - gesture.stepOriginX) / step).toInt()
                val dy = ((y - gesture.stepOriginY) / step).toInt()
                if (dx == 0 && dy == 0) return
                gesture.stepOriginX += dx * step
                gesture.stepOriginY += dy * step
                gesture.hasDragged = true
                keyboardActionListener?.onCursorMove(dx, dy, key.keyCode == Constants.KEYCODE_SELECTION_PAD)
            }
            Constants.KEYCODE_VOICE_INPUT -> {
                if (!gesture.voiceLocked && gesture.startY - y > dpToPx(Constants.SWIPE_THRESHOLD_DP)) {
                    gesture.voiceLocked = true
                    keyboardActionListener?.onVoiceLock()
                }
            }
            else -> {
                if (gesture.swipe != null || isLongPressHandled || !key.hasSwipeActions) return
                val dx = x - gesture.startX
                val dy = y - gesture.startY
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < dpToPx(Constants.SWIPE_THRESHOLD_DP)) return
                val direction = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                } else {
                    if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
                }
                // Only commit to a swipe if this direction has an action; otherwise the tap still counts.
                val (code, label) = swipeAction(key, direction)
                if (code == null && label == null) return
                gesture.swipe = direction
                cancelRepeatKey()
                handler.removeMessages(MSG_LONGPRESS)
            }
        }
    }

    private fun swipeAction(key: Key, direction: SwipeDirection): Pair<Int?, String?> = when (direction) {
        SwipeDirection.UP -> key.keyCodeSwipeUp to key.labelSwipeUp
        SwipeDirection.DOWN -> key.keyCodeSwipeDown to key.labelSwipeDown
        SwipeDirection.LEFT -> key.keyCodeSwipeLeft to key.labelSwipeLeft
        SwipeDirection.RIGHT -> key.keyCodeSwipeRight to key.labelSwipeRight
    }

    /////////////////////////////////////
    // RENDERED VIEW SCALING LOGIC
    // Helper to calculate keyboard height dynamically in pixels
    private fun calculateKeyboardHeight(context: Context): Float {
        val keyboard = keyboard ?: return 0f
        var totalHeight = 0f

        keyboard.rows.forEach { row ->
            val rowHeight = row.defaultKeyHeight ?: Constants.DEFAULT_KEY_HEIGHT
            totalHeight += rowHeight.toPx(context)
        }

        return totalHeight
    }

    private fun Float.toPx(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, this, context.resources.displayMetrics
        )
    }



    /////////////////////////////////////
    // MEASURE VIEW DIMENSIONS
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Calculate the desired keyboard height in pixels
        val desiredHeight = calculateKeyboardHeight(context)

        // Measure width
        val measuredWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> suggestedMinimumWidth
        }

        // Measure height
        val measuredHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec).toFloat()
            else -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec).toFloat())
        }

        // Store rendered dimensions
        renderedWidth = measuredWidth.toFloat()
        renderedHeight = measuredHeight

        // Set the measured dimensions
        setMeasuredDimension(measuredWidth, measuredHeight.toInt())
    }


    /////////////////////////////////////
    // ON SIZE CHANGE: CALCULATE SCALES
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyboard?.let { kb ->
            // Cache logical dimensions
            logicalWidth = kb.totalLogicalWidth.toFloat()
            logicalHeight = kb.totalLogicalHeight.toFloat()

            // Compute scaling factors
            scaleX = if (logicalWidth > 0) w / logicalWidth else 1f
            scaleY = if (logicalHeight > 0) h / logicalHeight else 1f

        }
    }


    ///////////////////////////////////////
    // DRAWING LOGIC
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val keyboard = keyboard ?: return

        // Resolve accent color or fallback to a soft violet-purple
        val accentColor = ColorUtils.parseHexColor(settings.customAccentColor)
            ?: context.resolveThemeColor(android.R.attr.colorAccent, Constants.DEFAULT_ACCENT_COLOR)

        // Define colors based on theme
        val isDarkTheme = when (settings.themeMode) {
            Constants.THEME_MODE_DARK -> true
            Constants.THEME_MODE_LIGHT -> false
            else -> try {
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            } catch (e: Exception) {
                Log.w("ThemeDetection", "Failed to detect UI mode, falling back to dark theme.")
                true // Fallback to dark theme
            }
        }

        val keyboardBackgroundColor = if (isDarkTheme) adjustColor(accentColor, 0.2f, 0.6f) else adjustColor(accentColor, 0.99f, 0.2f)
        val keyBackgroundColor = if (isDarkTheme) adjustColor(accentColor, 0.25f, 0.4f) else adjustColor(accentColor, 1.0f, 0.1f)
        val keyModifierBackgroundColor = if (isDarkTheme) adjustColor(accentColor, 1.2f, 0.7f) else adjustColor(accentColor, 1.0f, 1.0f)
        val keyLabelTextColor = if (isDarkTheme) Constants.TEXT_COLOR_DARK_THEME else Constants.TEXT_COLOR_LIGHT_THEME
        val keyLabelLongPressTextColor = if (isDarkTheme) adjustColor(accentColor, 1.4f, 0.9f) else adjustColor(accentColor, 0.95f, 1.0f)

        // Keep those values the same as text or background colors.
        val keyIconColor = keyLabelTextColor
        val keyModifierIconColor = keyboardBackgroundColor
        val keyModifierLabelTextColor = keyboardBackgroundColor
        val keyModifierSmallLabelTextColor = keyboardBackgroundColor


        // Draw keyboard background
        paint.color = keyboardBackgroundColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Scaling factors
        val scaleX = width.toFloat() / keyboard.totalLogicalWidth
        val scaleY = height.toFloat() / keyboard.totalLogicalHeight

        // Rendered gaps from settings
        val renderedKeyGap = settings.renderedKeyGap
        val renderedRowGap = settings.renderedRowGap

        var currentY = 0f

        keyboard.rows.forEach { row ->
            val rowHeight = row.defaultKeyHeight?.times(scaleY) ?: (settings.defaultKeyHeight * scaleY)
            val logicalRowGap = row.logicalRowGap?.times(scaleY) ?: (settings.defaultLogicalRowGap * scaleY)
            var currentX = 0f

            row.keys.forEach { key ->
                val logicalKeyWidth = key.keyWidth?.times(scaleX) ?: (row.defaultKeyWidth?.times(scaleX) ?: (settings.defaultKeyWidth * scaleX))
                val logicalKeyHeight = key.keyHeight?.times(scaleY) ?: rowHeight
                val logicalKeyGap = key.logicalKeyGap?.times(scaleX) ?: (row.defaultLogicalKeyGap?.times(scaleX) ?: (settings.defaultLogicalKeyGap * scaleX))

                // Adjust for rendered gaps
                val rectKeyX = currentX + renderedKeyGap / 2
                val rectKeyY = currentY + renderedRowGap / 2
                val rectKeyWidth = logicalKeyWidth - renderedKeyGap
                val rectKeyHeight = logicalKeyHeight - renderedRowGap

                // Define key bounds
                val keyBounds = RectF(rectKeyX, rectKeyY, rectKeyX + rectKeyWidth, rectKeyY + rectKeyHeight)

                // Define corner radius from settings
                val cornerRadius = rectKeyHeight * settings.keyCornerRadiusFactor

                // Draw key background
                paint.color = when {
                    key.keyCode == Constants.KEYCODE_VOICE_INPUT && isVoiceActive -> keyLabelLongPressTextColor
                    key.isModifier == true -> keyModifierBackgroundColor
                    else -> keyBackgroundColor
                }
                canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, paint)

                // Swipe hints: up top-left, down bottom-center, left mid-left, right mid-right.
                if (key.hasSwipeActions) {
                    paint.color = if (key.isModifier == true) keyModifierSmallLabelTextColor else keyLabelLongPressTextColor
                    paint.textSize = rectKeyHeight * Constants.TEXT_SIZE_FACTOR_SWIPE_LABEL
                    val pad = rectKeyHeight * 0.08f
                    val ascent = paint.textSize * 0.8f
                    key.labelSwipeUp?.let {
                        paint.textAlign = Paint.Align.LEFT
                        canvas.drawText(it, keyBounds.left + pad, keyBounds.top + pad + ascent, paint)
                    }
                    key.labelSwipeDown?.let {
                        paint.textAlign = Paint.Align.CENTER
                        canvas.drawText(it, keyBounds.centerX(), keyBounds.bottom - pad, paint)
                    }
                    key.labelSwipeLeft?.let {
                        paint.textAlign = Paint.Align.LEFT
                        canvas.drawText(it, keyBounds.left + pad, keyBounds.centerY() + ascent / 2, paint)
                    }
                    key.labelSwipeRight?.let {
                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(it, keyBounds.right - pad, keyBounds.centerY() + ascent / 2, paint)
                    }
                    paint.textAlign = Paint.Align.CENTER
                }

                // --- Draw content if any ---
                if (shouldRenderKey(key)) {
                    key.icon?.let { iconName ->
                        val drawable = getIconDrawable(iconName)
                        drawable?.let {
                            // TODO: add key icon / label size overrides
                            val iconSize = (rectKeyHeight * Constants.TEXT_SIZE_FACTOR_ICON).toInt()
                            val iconLeft = rectKeyX.toInt() + ((rectKeyWidth - iconSize) / 2).toInt()
                            val iconTop = rectKeyY.toInt() + ((rectKeyHeight - iconSize) / 2).toInt()
                            it.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                            // Modulate the icon color by setting a color filter directly on the drawable
                            it.colorFilter = PorterDuffColorFilter(
                                if (key.isModifier == true) keyModifierIconColor else keyIconColor,
                                PorterDuff.Mode.SRC_IN
                            )

                            // Draw the drawable
                            it.draw(canvas)
                        }
                    } ?: run {
                        // Check if small label exists
                        if (key.labelLongPress != null) {
                            // --- Draw small label (secondary text) ---
                            key.labelLongPress.let {
                                paint.color = if (key.isModifier == true) keyModifierSmallLabelTextColor else keyLabelLongPressTextColor
                                paint.textSize = rectKeyHeight * Constants.TEXT_SIZE_FACTOR_SMALL_LABEL
                                paint.textAlign = Paint.Align.CENTER

                                // Transform the small label dynamically based on meta states
                                val renderedSmallLabel = updateSmallLabelState(key) ?: ""

                                // Draw the small label if it's not empty
                                if (renderedSmallLabel.isNotEmpty()) {
                                    canvas.drawText(
                                        renderedSmallLabel,
                                        keyBounds.centerX(),
                                        keyBounds.top + rectKeyHeight * Constants.SMALL_LABEL_Y_POSITION_FACTOR, // Upper half position
                                        paint
                                    )
                                }
                            }

                            // --- Draw primary label (main text) ---
                            key.label?.let {
                                paint.color = if (key.isModifier == true) keyModifierLabelTextColor else keyLabelTextColor
                                paint.textSize = rectKeyHeight * Constants.TEXT_SIZE_FACTOR_PRIMARY_LABEL
                                paint.textAlign = Paint.Align.CENTER

                                // Transform the label dynamically based on meta states
                                var renderedLabel = updateLabelState(key) ?: ""

                                var textX = keyBounds.centerX()

                                // Dynamically set label if the key is a clipboard key
                                if (key.keyCode == Constants.KEYCODE_CLIPBOARD_ENTRY) {
                                    paint.textAlign = Paint.Align.LEFT
                                    // Correct the padding issue by adjusting the starting position
                                    textX = keyBounds.left + rectKeyHeight * Constants.CLIPBOARD_KEY_PADDING_FACTOR // Minimal padding from the left
                                    key.label = CustomKeyboardClipboard.getClipboardEntry(key.id) ?: "" // This text is committed
                                    renderedLabel = CustomKeyboardClipboard.getClipboardEntry(key.id) ?: "" // This text is rendered
                                }

                                // Draw the label if it's not empty
                                if (renderedLabel.isNotEmpty()) {
                                    canvas.drawText(
                                        renderedLabel,
                                        textX,
                                        keyBounds.centerY() + rectKeyHeight * Constants.LABEL_Y_OFFSET_FACTOR, // Slightly lower in the middle half
                                        paint
                                    )
                                }
                            }
                        } else {
                            // --- Draw primary label only (centered) ---
                            key.label?.let {
                                paint.color = if (key.isModifier == true) keyModifierLabelTextColor else keyLabelTextColor
                                paint.textSize = if (key.isModifier == true) rectKeyHeight * Constants.TEXT_SIZE_FACTOR_MODIFIER_LABEL else rectKeyHeight * Constants.TEXT_SIZE_FACTOR_CENTERED_LABEL
                                paint.textAlign = Paint.Align.CENTER

                                // Transform the label dynamically based on meta states
                                var renderedLabel = updateLabelState(key) ?: ""

                                var textX = keyBounds.centerX()

                                // Dynamically set label if the key is a clipboard key
                                if (key.keyCode == Constants.KEYCODE_CLIPBOARD_ENTRY) {
                                    paint.textAlign = Paint.Align.LEFT
                                    // Correct the padding issue by adjusting the starting position
                                    textX = keyBounds.left + rectKeyHeight * Constants.CLIPBOARD_KEY_PADDING_FACTOR // Minimal padding from the left
                                    key.label = CustomKeyboardClipboard.getClipboardEntry(key.id) ?: "" // This text is committed
                                    renderedLabel = CustomKeyboardClipboard.getClipboardEntry(key.id) ?: "" // This text is rendered
                                }

                                // Draw the label if it's not empty
                                if (renderedLabel.isNotEmpty()) {
                                    canvas.drawText(
                                        renderedLabel,
                                        textX,
                                        keyBounds.centerY() + (paint.textSize / 3), // Centered vertically
                                        paint
                                    )
                                }
                            }
                        }
                    }
                }

                // Increment X for the next key
                currentX += logicalKeyWidth + logicalKeyGap
            }

            // Increment Y for the next row
            currentY += rowHeight + logicalRowGap
        }
    }

    private fun shouldRenderKey(key: Key): Boolean {
        return !key.icon.isNullOrEmpty() || !key.label.isNullOrEmpty() || !key.labelLongPress.isNullOrEmpty()
    }

    // Adjust Color Method
    private fun adjustColor(color: Int, brightnessFactor: Float, intensityFactor: Float): Int {
        val alpha = color shr 24 and 0xFF
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF

        // Adjust intensity (blend with white)
        val blendedRed = (red + (255 - red) * (1 - intensityFactor)).toInt()
        val blendedGreen = (green + (255 - green) * (1 - intensityFactor)).toInt()
        val blendedBlue = (blue + (255 - blue) * (1 - intensityFactor)).toInt()

        // Adjust brightness
        val adjustedRed = (blendedRed * brightnessFactor).coerceIn(0f, 255f).toInt()
        val adjustedGreen = (blendedGreen * brightnessFactor).coerceIn(0f, 255f).toInt()
        val adjustedBlue = (blendedBlue * brightnessFactor).coerceIn(0f, 255f).toInt()

        return (alpha shl 24) or (adjustedRed shl 16) or (adjustedGreen shl 8) or adjustedBlue
    }

    // Extension to resolve theme color
    private fun Context.resolveThemeColor(attr: Int, fallbackColor: Int): Int {
        val typedValue = TypedValue()
        val resolved = theme.resolveAttribute(attr, typedValue, true)
        return if (resolved) {
            typedValue.data
        } else {
            Log.w("ThemeResolution", "Failed to resolve attribute: $attr. Using fallback color.")
            fallbackColor
        }
    }

    fun updateMetaState(shiftOn: Boolean, ctrlOn: Boolean, altOn: Boolean, capsLockOn: Boolean) {
        isShiftOn = shiftOn
        isCtrlOn = ctrlOn
        isAltOn = altOn
        isCapsLockOn = capsLockOn
        invalidateAllKeys()
    }

    private fun updateLabelState(key: Key): String? {

        // If preserveLabelCase is true, return the label as is.
        if (key.preserveLabelCase == true) {
            return key.label
        }

        val isMetaKeyToggled = isMetaKeyToggled(key.keyCode)

        return when {
            key.keyCode in metaKeyCodes && isMetaKeyToggled -> {
                // For meta keys (CAP, CTRL, SHIFT, ALT) toggled on, use uppercase.
                key.label?.uppercase()
            }
            key.keyCode in metaKeyCodes && !isMetaKeyToggled -> {
                // For meta keys toggled off, use lowercase.
                key.label?.lowercase()
            }
            key.isModifier == true && key.keyCode !in metaKeyCodes -> {
                // Modifier keys not in meta keys list (like Esc, Ent, Tab) retain their label.
                key.label
            }
            key.isModifier == false && (isShiftOn || isCapsLockOn) -> {
                // Non-modifier keys (ordinary characters) shift to uppercase when Shift or Caps Lock is active.
                key.label?.uppercase()
            }
            key.isModifier == false -> {
                // Non-modifier keys (ordinary characters) shift to lowercase when Shift and Caps Lock are inactive.
                key.label?.lowercase()
            }
            else -> {
                // Default case for any other keys, retain the original label.
                key.label
            }
        }
    }

    private fun updateSmallLabelState(key: Key): String? {
        // If preserveSmallLabelCase is true, return the small label as is.
        if (key.preserveSmallLabelCase == true) {
            return key.labelLongPress
        }

        val isMetaKeyToggled = key.keyCodeLongPress?.let { isMetaKeyToggled(it) } ?: false

        return when {
            key.keyCodeLongPress in metaKeyCodes && isMetaKeyToggled -> {
                // For meta keys (CAP, CTRL, SHIFT, ALT) toggled on, use uppercase.
                key.labelLongPress?.uppercase()
            }
            key.keyCodeLongPress in metaKeyCodes && !isMetaKeyToggled -> {
                // For meta keys toggled off, use lowercase.
                key.labelLongPress?.lowercase()
            }
            key.isModifier == true && key.keyCodeLongPress !in metaKeyCodes -> {
                // Modifier keys not in meta keys list (like Esc, Ent, Tab) retain their small label.
                key.labelLongPress
            }
            key.isModifier == false && (isShiftOn || isCapsLockOn) -> {
                // Non-modifier keys (ordinary characters) shift to uppercase when Shift or Caps Lock is active.
                key.labelLongPress?.uppercase()
            }
            key.isModifier == false -> {
                // Non-modifier keys (ordinary characters) shift to lowercase when Shift and Caps Lock are inactive.
                key.labelLongPress?.lowercase()
            }
            else -> {
                // Default case for any other keys, retain the original small label.
                key.labelLongPress
            }
        }
    }




    // Helper function to fetch drawable icons
    private fun getIconDrawable(iconName: String): Drawable? {
        return iconCache.getOrPut(iconName) {
            val resId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
            if (resId != 0) context.getDrawable(resId) else null
        }
    }

    fun invalidateAllKeys() {
        buffer = null
        invalidate() // Forces the entire view to be redrawn
    }

}


