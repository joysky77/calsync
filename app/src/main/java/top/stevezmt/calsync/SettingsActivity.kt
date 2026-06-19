package top.stevezmt.calsync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingsActivity : AppCompatActivity() {

    private lateinit var keywordsEdit: EditText
    private lateinit var saveBtn: Button
    private var radioGroupPreferFuture: android.widget.RadioGroup? = null
    private var radioAuto: android.widget.RadioButton? = null
    private var radioPrefer: android.widget.RadioButton? = null
    private var radioDisable: android.widget.RadioButton? = null
    private lateinit var permBtn: Button
    private lateinit var notifyBtn: Button
    private lateinit var relativeWordsEdit: EditText
    private lateinit var customRulesEdit: EditText
    private lateinit var reminderMinutesEdit: EditText
    private var selectAppsBtn: Button? = null
    private var selectedAppsText: android.widget.TextView? = null
    private var fillRuleTemplateBtn: Button? = null
    private var resetRelativeWordsBtn: Button? = null

    private var parseEngineInput: MaterialAutoCompleteTextView? = null
    private var eventEngineInput: MaterialAutoCompleteTextView? = null
    private var aiModelPathEdit: EditText? = null
    private var pickAiModelBtn: Button? = null
    private var aiPromptEdit: EditText? = null
    private var aiPromptLayout: android.view.View? = null
    private var aiSection: android.view.View? = null
    private var externalAiSection: android.view.View? = null
    private var externalAiKeyEdit: EditText? = null
    private var externalAiUrlEdit: EditText? = null
    private var externalAiModelEdit: EditText? = null
    
    private var guessBeforeParseSwitch: com.google.android.material.materialswitch.MaterialSwitch? = null
    private var fabSave: com.google.android.material.floatingactionbutton.FloatingActionButton? = null

    private val pickAiModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        val uriStr = uri.toString()
        aiModelPathEdit?.setText(uriStr)
        SettingsStore.setAiGgufModelUri(this, uriStr)
        Toast.makeText(this, "已选择模型文件", Toast.LENGTH_SHORT).show()
    }

    private val requestCalendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "已授予日历写入权限", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "日历写入权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.settings_toolbar)?.let { setSupportActionBar(it) }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        keywordsEdit = findViewById(R.id.edit_keywords)
        radioGroupPreferFuture = findViewById(R.id.radio_prefer_future)
        radioAuto = findViewById(R.id.radio_auto)
        radioPrefer = findViewById(R.id.radio_prefer)
        radioDisable = findViewById(R.id.radio_disable)
        saveBtn = findViewById(R.id.btn_save)
        permBtn = findViewById(R.id.btn_request_permission)
        notifyBtn = findViewById(R.id.btn_open_notification_access)
        relativeWordsEdit = findViewById(R.id.edit_relative_words)
        customRulesEdit = findViewById(R.id.edit_custom_rules)
        reminderMinutesEdit = findViewById(R.id.edit_reminder_minutes)
        selectAppsBtn = findViewById(R.id.btn_select_apps)
        selectedAppsText = findViewById(R.id.text_selected_apps)
        fillRuleTemplateBtn = findViewById(R.id.btn_fill_rule_template)
        resetRelativeWordsBtn = findViewById(R.id.btn_reset_relative_words)

        parseEngineInput = findViewById(R.id.input_parse_engine)
        eventEngineInput = findViewById(R.id.input_event_engine)
        aiModelPathEdit = findViewById(R.id.edit_ai_model_path)
        pickAiModelBtn = findViewById(R.id.btn_pick_ai_model)
        aiPromptEdit = findViewById(R.id.edit_ai_prompt)
        aiPromptLayout = findViewById(R.id.ai_prompt_layout)
        aiSection = findViewById(R.id.ai_section)
        
        externalAiSection = findViewById(R.id.external_ai_section)
        externalAiKeyEdit = findViewById(R.id.edit_external_ai_key)
        externalAiUrlEdit = findViewById(R.id.edit_external_ai_url)
        externalAiModelEdit = findViewById(R.id.edit_external_ai_model)
        
        guessBeforeParseSwitch = findViewById(R.id.switch_guess_before_parse)
        fabSave = findViewById(R.id.fab_save)

        loadSettings()

        saveBtn.setOnClickListener { saveAllSettings() }
        fabSave?.setOnClickListener { saveAllSettings() }
        permBtn.setOnClickListener { requestCalendarPermission.launch(Manifest.permission.WRITE_CALENDAR) }
        notifyBtn.setOnClickListener { openNotificationAccessSettings() }
        selectAppsBtn?.setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }

        fillRuleTemplateBtn?.setOnClickListener {
            if (customRulesEdit.text.isNullOrBlank()) {
                customRulesEdit.setText("(\\d{1,2}月\\d{1,2}日)|(周[一二三四五六日天]\\d{1,2}[:：]\\d{2})|(下周[一二三四五六日天]?)")
            }
        }

        resetRelativeWordsBtn?.setOnClickListener {
            SettingsStore.resetRelativeWords(this)
            relativeWordsEdit.setText(SettingsStore.getRelativeDateWords(this).joinToString(","))
        }

        pickAiModelBtn?.setOnClickListener { pickAiModelLauncher.launch(arrayOf("*/*")) }
        
        setupParsingEngineUi()
        syncUiForEngineCoupling()
    }

    private fun loadSettings() {
        keywordsEdit.setText(SettingsStore.getKeywords(this).joinToString(","))
        relativeWordsEdit.setText(SettingsStore.getRelativeDateWords(this).joinToString(","))
        customRulesEdit.setText(SettingsStore.getCustomRules(this).joinToString(","))
        reminderMinutesEdit.setText(SettingsStore.getReminderMinutes(this).toString())
        guessBeforeParseSwitch?.isChecked = SettingsStore.isGuessBeforeParseEnabled(this)
        
        aiModelPathEdit?.setText(SettingsStore.getAiGgufModelUri(this) ?: "")
        aiPromptEdit?.setText(SettingsStore.getAiSystemPrompt(this))
        
        externalAiKeyEdit?.setText(SettingsStore.getExternalAiKey(this) ?: "")
        externalAiUrlEdit?.setText(SettingsStore.getExternalAiUrl(this))
        externalAiModelEdit?.setText(SettingsStore.getExternalAiModel(this))

        when (SettingsStore.getPreferFutureOption(this)) {
            0 -> radioAuto?.isChecked = true
            1 -> radioPrefer?.isChecked = true
            2 -> radioDisable?.isChecked = true
        }
        updateSelectedAppsSummary()
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        if (BuildConfig.FLAVOR == "foss") return false
        return try {
            packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateSelectedAppsSummary() {
        val names = SettingsStore.getSelectedSourceAppNames(this)
        selectedAppsText?.text = if (names.isEmpty()) "全部" else names.joinToString(", ")
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }
    }

    private fun setupParsingEngineUi() {
        val isGmsAvailable = isGooglePlayServicesAvailable()
        val engines = ParseEngine.entries.filter { BuildConfig.FLAVOR != "foss" || it != ParseEngine.EXTERNAL_AI }
        val adapter = object : android.widget.ArrayAdapter<ParseEngine>(this, R.layout.item_engine_option, engines) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_engine_option, parent, false)
                val item = getItem(position)
                view.findViewById<android.widget.TextView>(R.id.text_title).text = item?.displayName
                view.findViewById<android.widget.TextView>(R.id.text_description).text = item?.description
                view.alpha = if (item == ParseEngine.ML_KIT && !isGmsAvailable) 0.5f else 1.0f
                return view
            }
        }
        parseEngineInput?.setAdapter(adapter)
        parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)
        parseEngineInput?.setOnItemClickListener { _, _, pos, _ ->
            val picked = engines[pos]
            if (picked == ParseEngine.AI_GGUF || picked == ParseEngine.EXTERNAL_AI) {
                showAiWarningDialog { confirmed ->
                    if (confirmed) {
                        SettingsStore.setParsingEngine(this, picked)
                        eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)
                        syncUiForEngineCoupling()
                    } else {
                        parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)
                    }
                }
            } else {
                SettingsStore.setParsingEngine(this, picked)
                eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)
                syncUiForEngineCoupling()
            }
        }

        val eventEngines = EventParseEngine.entries.filter { BuildConfig.FLAVOR != "foss" || it != EventParseEngine.EXTERNAL_AI }
        val eventAdapter = object : android.widget.ArrayAdapter<EventParseEngine>(this, R.layout.item_engine_option, eventEngines) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_engine_option, parent, false)
                val item = getItem(position)
                view.findViewById<android.widget.TextView>(R.id.text_title).text = item?.displayName
                view.findViewById<android.widget.TextView>(R.id.text_description).text = item?.description
                view.alpha = if (item == EventParseEngine.ML_KIT && !isGmsAvailable) 0.5f else 1.0f
                return view
            }
        }
        eventEngineInput?.setAdapter(eventAdapter)
        eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)
        eventEngineInput?.setOnItemClickListener { _, _, pos, _ ->
            val picked = eventEngines[pos]
            SettingsStore.setEventParsingEngine(this, picked)
            parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)
            syncUiForEngineCoupling()
        }
    }

    private fun syncUiForEngineCoupling() {
        val current = SettingsStore.getParsingEngine(this)
        val isGguf = current == ParseEngine.AI_GGUF
        val isExternal = current == ParseEngine.EXTERNAL_AI
        
        aiSection?.visibility = if (isGguf) android.view.View.VISIBLE else android.view.View.GONE
        externalAiSection?.visibility = if (isExternal) android.view.View.VISIBLE else android.view.View.GONE
        aiPromptLayout?.visibility = if (isGguf || isExternal) android.view.View.VISIBLE else android.view.View.GONE
        
        eventEngineInput?.isEnabled = !(isGguf || isExternal)
    }

    private fun saveAllSettings() {
        SettingsStore.setKeywords(this, keywordsEdit.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
        SettingsStore.setRelativeDateWords(this, relativeWordsEdit.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
        SettingsStore.setCustomRules(this, customRulesEdit.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
        SettingsStore.setReminderMinutes(this, reminderMinutesEdit.text.toString().toIntOrNull() ?: 10)
        SettingsStore.setGuessBeforeParseEnabled(this, guessBeforeParseSwitch?.isChecked == true)
        
        val preferOpt = when {
            radioAuto?.isChecked == true -> 0
            radioPrefer?.isChecked == true -> 1
            radioDisable?.isChecked == true -> 2
            else -> 1
        }
        SettingsStore.setPreferFutureOption(this, preferOpt)

        SettingsStore.setAiSystemPrompt(this, aiPromptEdit?.text?.toString() ?: "")
        SettingsStore.setExternalAiKey(this, externalAiKeyEdit?.text?.toString())
        SettingsStore.setExternalAiUrl(this, externalAiUrlEdit?.text?.toString() ?: "https://api.deepseek.com")
        SettingsStore.setExternalAiModel(this, externalAiModelEdit?.text?.toString() ?: "deepseek-chat")

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        syncUiForEngineCoupling()
    }

    private fun showAiWarningDialog(callback: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("AI 功能警告")
            .setMessage("使用 AI 引擎（本地或外部）可能会消耗较多资源或将数据发送至云端。本地模型解析速度较慢且可能不稳定，外部 API 需要网络权限且依赖第三方服务。是否继续？")
            .setPositiveButton("确认") { _, _ -> callback(true) }
            .setNegativeButton("取消") { _, _ -> callback(false) }
            .show()
    }
}
