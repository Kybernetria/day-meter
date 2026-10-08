package com.example.dayprogress.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.reminder.CheckpointActionReceiver
import com.example.dayprogress.reminder.CheckpointSnooze
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Direct notification destination: choosing or cancelling never consumes the reminder. */
class SnoozeActivity : AppCompatActivity() {
    private var checkpointId = ""
    private var occurrenceDayId = ""
    private var deliveryToken = -1L
    private lateinit var choices: RadioGroup
    private lateinit var customInput: TextInputLayout
    private lateinit var customMinutes: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        val preferences = AppPreferences(this)
        setTheme(when (preferences.interfacePalette) {
            "violet" -> R.style.Theme_DayProgressWidget_Snooze_Violet
            "ember" -> R.style.Theme_DayProgressWidget_Snooze_Ember
            else -> R.style.Theme_DayProgressWidget_Snooze
        })
        super.onCreate(savedInstanceState)
        checkpointId = intent.getStringExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID).orEmpty()
        occurrenceDayId = intent.getStringExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID).orEmpty()
        deliveryToken = intent.getLongExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, -1L)
        val checkpoint = CheckpointSnooze.currentCheckpoint(this, checkpointId, occurrenceDayId, deliveryToken)
        if (checkpoint == null || intent.action != ACTION_PICK_SNOOZE) {
            closeUnavailable()
            return
        }
        setContentView(R.layout.activity_snooze)
        setFinishOnTouchOutside(true)
        findViewById<TextView>(R.id.snooze_checkpoint_label).text = checkpoint.displayLabel()
        choices = findViewById(R.id.snooze_choices)
        customInput = findViewById(R.id.snooze_custom_input)
        customMinutes = findViewById(R.id.snooze_custom_minutes)
        findViewById<View>(R.id.snooze_chain_note).visibility = if (checkpoint.predecessorId != null) View.VISIBLE else View.GONE
        val presets = preferences.snoozePresets
        val presetIds = listOf(R.id.snooze_preset_0, R.id.snooze_preset_1, R.id.snooze_preset_2,
            R.id.snooze_preset_3, R.id.snooze_preset_4, R.id.snooze_preset_5)
        presets.forEachIndexed { index, minutes ->
            choices.addView(MaterialRadioButton(this).apply {
                id = presetIds[index]
                tag = minutes
                text = getString(R.string.snooze_picker_minutes, minutes)
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }, index, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        choices.setOnCheckedChangeListener { _, checkedId ->
            customInput.visibility = if (checkedId == R.id.snooze_custom_option) View.VISIBLE else View.GONE
            customInput.error = null
            val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (checkedId == R.id.snooze_custom_option) {
                customMinutes.requestFocus()
                customMinutes.post { keyboard.showSoftInput(customMinutes, InputMethodManager.SHOW_IMPLICIT) }
            } else {
                customMinutes.clearFocus()
                keyboard.hideSoftInputFromWindow(customMinutes.windowToken, 0)
            }
        }
        val selected = savedInstanceState?.getInt(STATE_SELECTED_MINUTES)
            ?.takeIf { it in presets } ?: preferences.snoozeMinutes.takeIf { it in presets } ?: presets.first()
        val selectedId = (0 until choices.childCount).map(choices::getChildAt)
            .first { it.tag == selected }.id
        choices.check(if (savedInstanceState?.getBoolean(STATE_CUSTOM) == true) R.id.snooze_custom_option else selectedId)
        customMinutes.setText(savedInstanceState?.getString(STATE_CUSTOM_TEXT).orEmpty())
        findViewById<Button>(R.id.snooze_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.snooze_confirm).setOnClickListener { confirmSnooze() }
        customMinutes.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                confirmSnooze()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && CheckpointSnooze.currentCheckpoint(this, checkpointId, occurrenceDayId, deliveryToken) == null) {
            closeUnavailable()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::choices.isInitialized) {
            outState.putBoolean(STATE_CUSTOM, choices.checkedRadioButtonId == R.id.snooze_custom_option)
            outState.putInt(STATE_SELECTED_MINUTES, findViewById<RadioButton>(choices.checkedRadioButtonId)?.tag as? Int ?: -1)
            outState.putString(STATE_CUSTOM_TEXT, customMinutes.text?.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun confirmSnooze() {
        val minutes = if (choices.checkedRadioButtonId == R.id.snooze_custom_option) {
            customMinutes.text?.toString()?.trim()?.toIntOrNull()
        } else findViewById<RadioButton>(choices.checkedRadioButtonId)?.tag as? Int
        if (minutes == null || minutes !in 1..1440) {
            customInput.error = getString(R.string.snooze_picker_custom_error)
            customMinutes.requestFocus()
            return
        }
        if (CheckpointSnooze.snooze(this, checkpointId, occurrenceDayId, deliveryToken, minutes)) {
            finish()
        } else closeUnavailable()
    }

    private fun closeUnavailable() {
        Toast.makeText(this, R.string.snooze_picker_unavailable, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val ACTION_PICK_SNOOZE = "com.example.dayprogress.ACTION_PICK_SNOOZE"
        private const val STATE_SELECTED_MINUTES = "selected_minutes"
        private const val STATE_CUSTOM = "custom"
        private const val STATE_CUSTOM_TEXT = "custom_text"
    }
}
