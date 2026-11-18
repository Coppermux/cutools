package com.coppermux.cutools.ui.shell

import android.content.SharedPreferences

/**
 * Persists Floating F12 navigation button state (visibility and viewport position)
 * across application restarts.
 */
interface F12ButtonPreferences {
    fun isVisible(): Boolean
    fun setVisible(visible: Boolean)
    fun getPosition(): Pair<Float, Float>
    fun setPosition(xRatio: Float, yRatio: Float)
}

/**
 * Android [SharedPreferences] implementation of [F12ButtonPreferences].
 */
class SharedPreferencesF12ButtonPreferences(
    private val sharedPreferences: SharedPreferences,
) : F12ButtonPreferences {

    override fun isVisible(): Boolean =
        sharedPreferences.getBoolean(KEY_F12_VISIBLE, true)

    override fun setVisible(visible: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_F12_VISIBLE, visible).apply()
    }

    override fun getPosition(): Pair<Float, Float> {
        val x = sharedPreferences.getFloat(KEY_F12_POS_X, 1.0f)
        val y = sharedPreferences.getFloat(KEY_F12_POS_Y, 0.75f)
        return Pair(x, y)
    }

    override fun setPosition(xRatio: Float, yRatio: Float) {
        sharedPreferences.edit()
            .putFloat(KEY_F12_POS_X, xRatio)
            .putFloat(KEY_F12_POS_Y, yRatio)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "cutools_f12_settings"
        const val KEY_F12_VISIBLE = "f12_button_visible"
        const val KEY_F12_POS_X = "f12_position_x_ratio"
        const val KEY_F12_POS_Y = "f12_position_y_ratio"
    }
}

/**
 * In-memory test implementation of [F12ButtonPreferences].
 */
class InMemoryF12ButtonPreferences(
    private var visible: Boolean = true,
    private var xRatio: Float = 1.0f,
    private var yRatio: Float = 0.75f,
) : F12ButtonPreferences {

    override fun isVisible(): Boolean = visible

    override fun setVisible(visible: Boolean) {
        this.visible = visible
    }

    override fun getPosition(): Pair<Float, Float> = Pair(xRatio, yRatio)

    override fun setPosition(xRatio: Float, yRatio: Float) {
        this.xRatio = xRatio
        this.yRatio = yRatio
    }
}
