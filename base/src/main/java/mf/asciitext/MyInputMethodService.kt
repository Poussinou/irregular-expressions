@file:Suppress("DEPRECATION")

package mf.asciitext

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.Keyboard.KEYCODE_DONE
import android.inputmethodservice.KeyboardView
import android.inputmethodservice.KeyboardView.OnKeyboardActionListener
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import android.text.InputType
import android.text.TextUtils
import android.text.method.MetaKeyKeyListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatImageButton
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import mf.asciitext.fonts.AppFont
import mf.asciitext.fonts.AvailableFonts.getEnabledFonts
import java.util.concurrent.TimeUnit

/**
 * This class sets up and handles virtual keyboard events
 */
class MyInputMethodService : InputMethodService(), OnKeyboardActionListener {

    // set true to also process hard keyboard click events
    private val PROCESS_HARD_KEYS = true
    private val DOUBLETAP_MAX_DELAY_MS = 500L
    private val VIBRATION_DURATION_MS = 25L
    private val LONG_PRESS = 200L
    private val DEFAULT_KBD_LAYOUT = "1" // 1 = qwerty, 2 = azerty
    private val DEFAULT_VIBRATIONS = false

    // Primary vs. secondary keyboards
    private val ALPHA_KEYBOARD_KEYCODE = -10
    private val SECONDARY_KBD_KEYCODE = -11
    private val KEYCODE_SPACE = 32

    // All available font styles
    private var fonts = getEnabledFonts()

    // Font with "no style"
    private val REGULAR_FONT_INDEX = -1

    // Keyboard variations
    private val ALPHA_KBD = 0
    private val NUMBER_KBD = 1
    private val MATH_KBD = 2

    // UI Elements
    private var keyboardView: KeyboardView? = null
    private var keyboard: Keyboard? = null
    private var fontStyleToggle: AppCompatImageButton? = null
    private var fontPicker: RecyclerView? = null
    private var adapter: FontPickerAdapter? = null

    // keyboard default state
    private val mComposing = StringBuilder()
    private var keyboardChoice = ALPHA_KBD
    private var fontIndex = REGULAR_FONT_INDEX
    private var lastSelectedStyleIndex = REGULAR_FONT_INDEX
    private var reverseCursorDirection = false
    private var mMetaState: Long = 0

    // SHIFT key related variables
    private var uppercaseNextKeyOnly = false
    private var lastShift: Long = 0

    // SPACE bar variables
    private var spaceDown: Long = 0
    private var pickerInflated: Boolean = false

    // ENTER key variables
    private var mEnterKeyIndex: Int = -1

    // user preferences
    private var keyVibrations = DEFAULT_VIBRATIONS
    private var keyboardLayout = DEFAULT_KBD_LAYOUT

    // called initially when inflating keyboard
    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.keyboard_view, null)
        val ctx = layout.context
        initPreferences()

        /* initialize keyboard */
        keyboardView = layout.findViewById(R.id.keyboard_view)
        keyboardView?.setOnKeyboardActionListener(this)
        if (keyboardChoice == NUMBER_KBD) {
            enableSymbolicKeyboard()
        } else {
            enableAlphaKeyboard()
        }
        for (i in 0..(keyboard!!.keys).size) {
            if (keyboard!!.keys[i].codes.contains(KEYCODE_DONE)) {
                mEnterKeyIndex = i
                break
            }
        }

        /* setup font picker recyclerView */

        fontPicker = layout.findViewById(R.id.fontPicker)
        adapter = FontPickerAdapter(fonts, onFontSelection())
        val layoutManager = GridLayoutManager(
            ctx, 1, LinearLayoutManager.HORIZONTAL, false
        )
        fontPicker?.layoutManager = layoutManager
        fontPicker?.adapter = adapter
        adapter!!.setSelectedFont(fontIndex)

        /* setup UI icon buttons */

        fontStyleToggle = layout.findViewById(R.id.font_button)
        fontStyleToggle?.setOnClickListener(onFontButtonClick())
        setFontStyleIcon(fontIndex == REGULAR_FONT_INDEX)
        val settingsButton: View = layout.findViewById(R.id.settings_button)
        settingsButton.setOnClickListener(onSettingsClick(ctx))

        return layout
    }

    /**
     * This is the main point where we do our initialization of the input method
     * to begin operating on an application.  At this point we have been
     * bound to the client, and are now receiving all of the detailed information
     * about the target of our edits.
     */
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)

        // Reset our state.  We want to do this even if restarting, because
        // the underlying state of the text editor could have changed in any way.
        mComposing.setLength(0)
        if (!restarting) mMetaState = 0

        when (attribute.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> {
                // Numbers and dates default to the symbols keyboard, with
                // no extra features.
                keyboardChoice = NUMBER_KBD
            }
            InputType.TYPE_CLASS_PHONE -> {
                // Phones will also default to the symbols keyboard, though
                // often you will want to have a dedicated phone keyboard.
                keyboardChoice = NUMBER_KBD
            }
            InputType.TYPE_CLASS_TEXT -> {
                // This is general text editing.  We will default to the
                // normal alphabetic keyboard, and assume that we should
                // be doing predictive text (showing candidates as the
                // user types).
                keyboardChoice = ALPHA_KBD
            }
            else -> {
                // For all unknown input types, default to the alphabetic
                // keyboard with no special features.
                // mCurKeyboard = mQwertyKeyboard
                keyboardChoice = ALPHA_KBD
            }
        }
        setImeOptions(attribute.imeOptions)
    }

    /**
     * Reload user preferences every time keyboard is inflated
     * as these preferences may have changed
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        initPreferences()
        fonts = getEnabledFonts()
        adapter!!.updateFonts(fonts)
    }

    /**
     * Handle keyboard key presses; if key is held down this fires multiple times
     */
    override fun onKey(primaryCode: Int, keyCodes: IntArray) {
        if (currentInputConnection != null) {
            when (primaryCode) {
                SECONDARY_KBD_KEYCODE -> toggleExtendedKeyboardView()
                ALPHA_KEYBOARD_KEYCODE -> enableAlphaKeyboard()
                Keyboard.KEYCODE_DELETE -> handleDeleteKeyPress()
                Keyboard.KEYCODE_SHIFT -> handleShiftKeyPress()
                Keyboard.KEYCODE_DONE -> handleDoneKeyPress()
                KEYCODE_SPACE -> return
                else -> encodeCharacter(primaryCode)
            }
        }
    }

    /**
     * Fired each time key is pressed; if key is held down this fires only once
     */
    override fun onPress(i: Int) {
        vibrate(this)
        if (i == KEYCODE_SPACE) onSpaceKeyDown()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (PROCESS_HARD_KEYS && event != null) return translateKeyDown(keyCode, event)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // If we want to do transformations on text being entered with a hard
        // keyboard, we need to process the up events to update the meta key
        // state we are tracking.
        if (PROCESS_HARD_KEYS) {
            mMetaState = MetaKeyKeyListener.handleKeyUp(mMetaState, keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onRelease(i: Int) {
        if (i == KEYCODE_SPACE) onSpaceKeyRelease()
    }

    /**
     * This translates incoming hard key events in to edit operations on an
     * InputConnection.  It is only needed when using the
     * PROCESS_HARD_KEYS option.
     */
    private fun translateKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        mMetaState = MetaKeyKeyListener.handleKeyDown(mMetaState, keyCode, event)
        val c = event.getUnicodeChar(MetaKeyKeyListener.getMetaState(mMetaState))
        mMetaState = MetaKeyKeyListener.adjustMetaAfterKeypress(mMetaState)
        if (c == 0 || currentInputConnection == null) {
            return false
        }
        if (c == KEYCODE_SPACE)
        {
            encodeCharacter(KEYCODE_SPACE)
            return true
        }
        onKey(c, IntArray(0))
        return true
    }

    /**
     * Switch between numeric and symbolic keyboard
     */
    private fun toggleExtendedKeyboardView() {
        if (keyboardChoice == NUMBER_KBD) {
            keyboard = Keyboard(this, R.xml.keyboard_math)
            keyboardChoice = MATH_KBD
        } else {
            keyboard = Keyboard(this, R.xml.keyboard_extended)
            keyboardChoice = NUMBER_KBD
        }
        keyboardView!!.keyboard = keyboard
        keyboardView!!.invalidateAllKeys()
    }

    private fun enableSymbolicKeyboard() {
        keyboard = Keyboard(this, R.xml.keyboard_extended)
        keyboardChoice = NUMBER_KBD
        keyboardView!!.keyboard = keyboard
        keyboardView!!.invalidateAllKeys()
    }

    /**
     * Change view to alphabetic keyboard
     */
    private fun enableAlphaKeyboard() {
        val keyLayout = if (keyboardLayout == DEFAULT_KBD_LAYOUT)
            R.xml.keyboard_qwerty else R.xml.keyboard_azerty
        keyboard = Keyboard(this, keyLayout)
        keyboardChoice = ALPHA_KBD
        keyboard!!.isShifted = false
        uppercaseNextKeyOnly = false
        setShiftKeyIcon()
        keyboardView!!.keyboard = keyboard
        keyboardView!!.invalidateAllKeys()
    }

    /**
     * Special handler for space down
     */
    private fun onSpaceKeyDown() {
        spaceDown = SystemClock.uptimeMillis()
        pickerInflated = false
    }

    /**
     * Short press on space bar results in space key press
     * Long press inflates keyboard picker
     */
    private fun onSpaceKeyRelease() {
        val spaceUp = SystemClock.uptimeMillis()
        if (spaceUp - spaceDown < LONG_PRESS)
            encodeCharacter(KEYCODE_SPACE)
        else if (!pickerInflated) {
            showKeyboardPicker()
            // prevent continuously inflating this menu
            pickerInflated = true
        }
    }

    /**
     * When user clicks shift key
     */
    private fun handleShiftKeyPress() {
        val now = SystemClock.uptimeMillis()
        val quickPress = now - lastShift < DOUBLETAP_MAX_DELAY_MS

        if (keyboard!!.isShifted && !quickPress) {
            uppercaseNextKeyOnly = false
            keyboard!!.isShifted = false
        } else {
            uppercaseNextKeyOnly = !quickPress
            keyboard!!.isShifted = true
        }
        setShiftKeyIcon()
        keyboardView!!.invalidateAllKeys()
        lastShift = now
    }

    /**
     * When shift is supposed to uppercase next letter only,
     * this method will reset to keyboard lowercase. Call it
     * after key press has occurred.
     */
    private fun unsetShift() {
        keyboard!!.isShifted = false
        uppercaseNextKeyOnly = false
        lastShift = 0L
        setShiftKeyIcon()
        keyboardView!!.invalidateAllKeys()
    }

    /**
     * Handle keyboard delete key press
     */
    private fun handleDeleteKeyPress() {
        val inputConnection = currentInputConnection
        val selectedText = inputConnection.getSelectedText(0)
        if (TextUtils.isEmpty(selectedText)) {
            if (reverseCursorDirection) {
                inputConnection.deleteSurroundingText(0, 1)
            } else
                inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    /**
     * Clicking on done key inserts a line break
     */
    private fun handleDoneKeyPress() {
        val inputConnection = currentInputConnection
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    /**
     * Default keyboard key press handler
     *
     * @param primaryCode pressed key code
     */
    private fun encodeCharacter(primaryCode: Int) {
        val inputConnection = currentInputConnection
        var code = primaryCode.toChar()
        if (Character.isLetter(code) && keyboard!!.isShifted) {
            code = Character.toUpperCase(code)
        }
        var text = code.toString()
        if (fontIndex >= 0 && fontIndex < fonts.size) {
            val style = fonts[fontIndex]
            text = if (style.isSequenceAware) {
                val seq = inputConnection.getTextBeforeCursor(5, 0)
                style.encode(text, seq).toString()
            } else {
                style.encode(text).toString()
            }
        }
        inputConnection.commitText(text, 1)

        // adjust cursor position when typing right to left
        if (reverseCursorDirection)
            inputConnection.commitText("", -text.length)

        if (uppercaseNextKeyOnly) unsetShift()
    }

    /**
     * Handle font change
     * Keep track of previous selection before current to enable
     * style on-off toggle;
     */
    private fun onFontSelection(): FontPickerAdapter.OnItemClickListener {
        return object : FontPickerAdapter.OnItemClickListener {
            override fun onItemClick(item: AppFont?, index: Int) {
                val previous = lastSelectedStyleIndex
                fontIndex = index
                lastSelectedStyleIndex = index
                setFontStyleIcon(false)
                setCursorDirection(fontIndex)
                adapter!!.setSelectedFont(fontIndex)
                adapter!!.notifyItemChanged(fontIndex)
                adapter!!.notifyItemChanged(previous)
            }
        }
    }

    /**
     * Determine if cursor should move left -> right
     * or left <- right following keyboard input
     */
    private fun setCursorDirection(fontIndex: Int) {
        reverseCursorDirection = if (fontIndex >= 0 && fontIndex < fonts.size) {
            val style = fonts[fontIndex]
            style.isReversed
        } else false
    }

    /**
     * Settings button click launches settings activity
     */
    private fun onSettingsClick(context: Context): View.OnClickListener {
        return View.OnClickListener {
            val intent = Intent(context, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    /**
     * Clicking font icon toggles styled font on and off.
     * OFF: type in regular letter case
     * ON: restore previous styled font (if selection exists)
     */
    private fun onFontButtonClick(): View.OnClickListener {
        return View.OnClickListener {
            val disable = fontIndex != REGULAR_FONT_INDEX
            fontIndex = if (disable) REGULAR_FONT_INDEX else lastSelectedStyleIndex
            setCursorDirection(fontIndex)
            setFontStyleIcon(disable)
            adapter!!.setSelectedFont(fontIndex)
        }
    }

    /**
     * Update font icon based on if custom styles are enabled/disabled
     *
     * @param disable - custom styles are disabled
     */
    private fun setFontStyleIcon(disable: Boolean) {
        fontStyleToggle!!.setImageResource(
            if (disable) R.drawable.ic_font_off
            else R.drawable.ic_format_font
        )
        fontPicker!!.alpha = if (disable) 0.5f else 1.0f
    }

    /**
     * Update keyboard shift key icon to match caps lock state
     */
    private fun setShiftKeyIcon() {
        val keys = keyboard!!.keys
        val shiftIndex = keyboard!!.shiftKeyIndex
        if (shiftIndex >= 0 && shiftIndex < keys.size) {
            val currentKey = keys[shiftIndex]
            val icon = when {
                uppercaseNextKeyOnly -> R.drawable.ic_arrow_up_bold
                keyboard!!.isShifted -> R.drawable.ic_keyboard_caps_filled
                else -> R.drawable.ic_arrow_up_bold_outline
            }
            currentKey.icon = resources.getDrawable(icon)
        }
    }

    /**
     * Generate key vibration effect
     **/
    private fun vibrate(ctx: Context) {
        if (!keyVibrations) return
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrationEffect = VibrationEffect.createOneShot(
                VIBRATION_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE
            )
            vibrator.vibrate(vibrationEffect)
        } else {
            vibrator.vibrate(TimeUnit.MILLISECONDS.toMillis(VIBRATION_DURATION_MS))
        }
    }

    /**
     * Initialize user preference variables
     */
    private fun initPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        keyVibrations = prefs.getBoolean("key_vibrations", DEFAULT_VIBRATIONS)
        keyboardLayout = prefs.getString("kbd_layout", DEFAULT_KBD_LAYOUT).toString()
    }

    /**
     * Show keyboard switcher
     */
    private fun showKeyboardPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    /**
     * This looks at the ime options given by the current editor, to set the
     * appropriate label on the keyboard's enter key (if it has one).
     */
    private fun setImeOptions(options: Int) {

        val mEnterKey =
            (if (keyboard != null && mEnterKeyIndex >= 0 && mEnterKeyIndex < keyboard!!.keys.size)
                keyboard!!.keys[mEnterKeyIndex] else null)
                ?: return

        when (options and (EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            EditorInfo.IME_ACTION_GO -> {
                mEnterKey.iconPreview = null
                mEnterKey.icon = null
                mEnterKey.label = resources.getText(R.string.label_go_key)
            }
            EditorInfo.IME_ACTION_NEXT -> {
                mEnterKey.iconPreview = null
                mEnterKey.icon = null
                mEnterKey.label = resources.getText(R.string.label_next_key)
            }
            EditorInfo.IME_ACTION_SEARCH -> {
                mEnterKey.icon = resources.getDrawable(R.drawable.ic_keybaord_search)
                mEnterKey.label = null
            }
            EditorInfo.IME_ACTION_SEND -> {
                mEnterKey.iconPreview = null
                mEnterKey.icon = null
                mEnterKey.label = resources.getText(R.string.label_send_key)
            }
            else -> {
                mEnterKey.icon = resources.getDrawable(R.drawable.ic_keyboard_return)
                mEnterKey.label = null
            }
        }
        if (keyboardView != null) keyboardView!!.invalidateKey(mEnterKeyIndex)
    }

    override fun onText(charSequence: CharSequence) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}