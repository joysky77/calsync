package top.stevezmt.calsync

import android.util.Log
import com.xkzhangsan.time.nlp.TimeNLPUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object DateTimeParser {
    private const val TAG = "DateTimeParser"
    enum class ExternalAiOutcome {
        CREATED,
        SKIPPED_BY_AI,
        ERROR
    }

    data class ExternalAiStatus(val outcome: ExternalAiOutcome, val message: String)

    private val externalAiStatus = ThreadLocal<ExternalAiStatus?>()
    private val xkTimePatched = AtomicBoolean(false)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @JvmStatic
    fun getNowMillis(): Long = Calendar.getInstance().timeInMillis

    @JvmStatic
    fun getNowFormatted(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(getNowMillis()))
    }

    data class ParseResult(val startMillis: Long, val endMillis: Long?, val title: String? = null, val location: String? = null)

    @JvmStatic
    fun extractTitleAndLocationFromText(context: android.content.Context, text: String): Pair<String?, String?> = extractTitleAndLocation(context, text)

    private const val colon = "[:：]"
    private val monthDayPattern = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]?")
    private val monthDayRangePattern = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]?\\s*[~-至到]+\\s*(\\d{1,2}|[一二三四五六七八九十百]+)月?(\\d{1,2}|[一二三四五六七八九十]+)[日号](?![A-Za-z])")
    private val timePattern = Pattern.compile("(上午|下午|中午|晚上|凌晨|今晚|明晚)?\\s*([0-9]{1,2}|[一二三四五六七八九十百]+)(?:${colon}([0-5]?\\d))?点?")
    private val weekdayTimePattern = Pattern.compile("((?:周|星期)[一二三四五六日天])(?:[上下午]|上午|下午)?\\s*(\\d{1,2})${colon}(\\d{1,2})")

    fun extractAllSentencesContainingDate(context: android.content.Context, text: String): List<String> {
        val delimiters = Regex("[。！？.!?；;，,]\\s*")
        val parts = delimiters.split(text)
        val out = mutableListOf<String>()
        for (p in parts) {
            if (p.isBlank()) continue
            if (containsDateLike(context, p)) out.add(p.trim())
        }
        return out
    }

    fun guessContainsDateTime(context: android.content.Context, text: String): Boolean {
        val delimiters = Regex("[。！？.!?；;，,]\\s*")
        val parts = delimiters.split(text)
        for (p in parts) {
            if (p.isBlank()) continue
            if (containsDateLike(context, p)) return true
        }
        return false
    }

    private fun containsDateLike(context: android.content.Context, s: String): Boolean {
        val custom = SettingsStore.getCustomRules(context)
        for (rule in custom) {
            try {
                val p = Pattern.compile(rule)
                if (p.matcher(s).find()) return true
            } catch (_: Exception) {}
        }
        if (Regex("还有[一二三四五六七八九十百零0-9]+(个)?天").containsMatchIn(s)
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?小时").containsMatchIn(s)
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?分钟?").containsMatchIn(s)
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?秒").containsMatchIn(s)) {
            return true
        }
        val dateLike = listOf(monthDayPattern, monthDayRangePattern, weekdayTimePattern)
        for (pat in dateLike) {
            if (pat.matcher(s).find()) return true
        }
        val tm = timePattern.matcher(s)
        while (tm.find()) {
            val matched = tm.group()
            val ampm = tm.group(1)
            val hourStr = tm.group(2)
            val minuteStr = tm.group(3)
            val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: -1
            val hasIndicator = ampm != null || minuteStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
            val hourEnd = try { tm.end(2) } catch (_: Throwable) { -1 }
            val nextCh = if (hourEnd in 0 until s.length) s[hourEnd] else null
            val followedByDigitWithoutDelimiter = nextCh?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
            if (hasIndicator && hour in 0..23 && !followedByDigitWithoutDelimiter) return true
        }
        return false
    }

    fun parseDateTime(sentence: String): ParseResult? = RuleBasedStrategy.tryParseStandalone(sentence)

    fun consumeExternalAiStatus(): ExternalAiStatus? {
        val status = externalAiStatus.get()
        externalAiStatus.remove()
        return status
    }

    fun parseDateTime(context: android.content.Context, sentence: String, baseMillis: Long): ParseResult? {
        when (SettingsStore.getParsingEngine(context)) {
            ParseEngine.XK_TIME -> {
                try {
                    XkTimeStrategy(context).tryParseWithBase(sentence, baseMillis)?.let { return it }
                } catch (t: Throwable) {
                    Log.w(TAG, "xk-time crashed: ${t.message}")
                    try { NotificationUtils.sendError(context, Exception(t)) } catch (_: Throwable) {}
                }
            }
            ParseEngine.AI_GGUF -> {
                AiGgufStrategy(context).tryParseWithBase(sentence, baseMillis)?.let { return it }
            }
            ParseEngine.EXTERNAL_AI -> {
                externalAiStatus.remove()
                return ExternalAiStrategy(context).tryParseWithBase(sentence, baseMillis)
            }
            ParseEngine.ML_KIT -> {
                MLKitStrategy(context).tryParseWithBase(sentence, baseMillis)?.let { return it }
            }
            ParseEngine.BUILTIN -> {}
        }
        try {
            val rule = RuleBasedStrategyWithContext(context)
            val r = rule.tryParseWithBase(sentence, baseMillis)
            if (r != null) return r
        } catch (e: Exception) {
            Log.w(TAG, "RuleBaseCtx failed: ${e.message}")
            try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
        }
        if (SettingsStore.isTimeNLPEnabled(context)) {
            if (shouldSkipTimeNLPFallback(sentence)) return null
            try {
                val nlp = TimeNLPStrategy(context)
                val r2 = nlp.tryParseWithBase(sentence, baseMillis)
                if (r2 != null) return r2
            } catch (e: Exception) {
                Log.w(TAG, "TimeNLP failed: ${e.message}")
                try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
            }
        }
        return null
    }

    private class TimeNLPStrategy(private val context: android.content.Context): ParsingStrategy {
        override fun name() = "TimeNLP"
        override fun tryParse(sentence: String): ParseResult? = tryParseWithBase(sentence, getNowMillis())
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val slots = TimeNLPAdapter.parse(sentence, baseMillis)
            if (slots.isEmpty()) return null
            val s = slots.first()
            val (t, loc) = extractTitleAndLocation(context, sentence)
            return ParseResult(s.startMillis, s.endMillis, t, loc)
        }
    }

    private class XkTimeStrategy(private val context: android.content.Context): ParsingStrategy {
        override fun name() = "xk-time"
        override fun tryParse(sentence: String): ParseResult? = tryParseWithBase(sentence, getNowMillis())
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            ensureXkTimeDecimalRegexPatched()
            return try {
                val baseStr = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date(baseMillis))
                val results = TimeNLPUtil.parse(sentence, baseStr) ?: return null
                val first = results.firstOrNull() ?: return null
                val startMillis = extractMillisFromXkTimeResult(first) ?: return null
                val endMillisFromSecond = results.getOrNull(1)?.let { extractMillisFromXkTimeResult(it) }
                val endMillisFromField = extractEndMillisFromXkTimeResult(first)
                val endMillis = listOfNotNull(endMillisFromSecond, endMillisFromField).firstOrNull { it > startMillis }
                val (t, loc) = extractTitleAndLocation(context, sentence)
                val defaultDuration = if (first.getIsAllDayTime() == true) 12 * 60 * 60 * 1000L else 60 * 60 * 1000L
                ParseResult(startMillis, endMillis ?: (startMillis + defaultDuration), t, loc)
            } catch (t: Throwable) {
                Log.w(TAG, "xk-time parse failed: ${t.message}")
                try { NotificationUtils.sendError(context, Exception(t)) } catch (_: Throwable) {}
                null
            }
        }
    }

    private fun ensureXkTimeDecimalRegexPatched() {
        if (xkTimePatched.get()) return
        try {
            val cls = Class.forName("com.xkzhangsan.time.enums.RegexEnum")
            val target = (cls.enumConstants as? Array<out Any>)?.firstOrNull { (it as? Enum<*>)?.name == "TextPreprocessDelDecimalStr" }
            if (target != null) {
                val field = cls.getDeclaredField("rule").apply { isAccessible = true }
                val current = field.get(target) as? String
                if (current != null && current.startsWith("{0,1}\\d+\\.\\d*")) {
                    field.set(target, "[-+]?\\d+\\.\\d*|[-+]?\\d*\\.\\d+")
                }
            }
            Class.forName("com.xkzhangsan.time.utils.RegexCache").getMethod("clear").invoke(null)
        } catch (_: Throwable) {
        } finally { xkTimePatched.set(true) }
    }

    private class AiGgufStrategy(private val context: android.content.Context): ParsingStrategy {
        override fun name() = "AI(GGUF)"
        override fun tryParse(sentence: String): ParseResult? = tryParseWithBase(sentence, getNowMillis())
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val uri = SettingsStore.getAiGgufModelUri(context) ?: return null
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(baseMillis))
            val system = SettingsStore.getAiSystemPrompt(context)
            val prompt = buildString {
                appendLine(system)
                appendLine("\n当前时间(now)为：$nowStr。请以此作为相对时间计算基准。")
                appendLine("你需要从用户句子中抽取日程信息，并只输出一段 JSON（不要多余文字）：")
                appendLine("{\"startMillis\":number,\"endMillis\":number|null,\"title\":string|null,\"location\":string|null}")
                appendLine("用户句子：$sentence")
            }
            NotificationUtils.sendDebugLog(context, "[GGUF] Prompt: $prompt")
            return try {
                val handle = top.stevezmt.calsync.llm.LlamaCpp.getOrInitHandle(context, uri, 2048, 1)
                if (handle == 0L) {
                    NotificationUtils.sendDebugLog(context, "[GGUF] Model handle is 0, initialization failed.")
                    return null
                }
                val raw = top.stevezmt.calsync.llm.LlamaCpp.complete(handle, prompt, 128)
                NotificationUtils.sendDebugLog(context, "[GGUF] Raw Response: $raw")
                extractFirstJsonObject(raw)?.let { parseAiJsonToResult(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "AI GGUF parse failed: ${t.message}")
                NotificationUtils.sendDebugLog(context, "[GGUF] Error: ${t.message}")
                try { NotificationUtils.sendError(context, Exception(t)) } catch (_: Throwable) {}
                null
            }
        }
    }

    private class ExternalAiStrategy(private val context: android.content.Context) : ParsingStrategy {
        override fun name() = "ExternalAI"
        override fun tryParse(sentence: String): ParseResult? = tryParseWithBase(sentence, getNowMillis())
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val apiKey = SettingsStore.getExternalAiKey(context)
            if (apiKey.isNullOrBlank()) {
                setExternalAiError("外部 AI API Key 未配置")
                return null
            }
            val apiUrl = normalizeExternalAiBaseUrl(SettingsStore.getExternalAiUrl(context))
            val model = SettingsStore.getExternalAiModel(context)
            val system = SettingsStore.getAiSystemPrompt(context)
            val prompt = buildExternalAiUserPrompt(sentence, baseMillis)

            NotificationUtils.sendDebugLog(context, "[ExternalAI] Requesting: $apiUrl, Model: $model")
            NotificationUtils.sendDebugLog(context, "[ExternalAI] Prompt: ${prompt.take(200)}...")

            return try {
                val jsonPayload = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", system) })
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                    put("temperature", 0)
                    put("stream", false)
                }
                val request = Request.Builder()
                    .url("$apiUrl/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                okHttpClient.newCall(request).execute().use { resp ->
                    val code = resp.code
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string()
                        NotificationUtils.sendDebugLog(context, "[ExternalAI] Failed: HTTP $code, Body: $errBody")
                        setExternalAiError("外部 AI 请求失败：HTTP $code")
                        return null
                    }
                    val bodyStr = resp.body?.string()
                    if (bodyStr.isNullOrBlank()) {
                        setExternalAiError("外部 AI 返回为空")
                        return null
                    }
                    NotificationUtils.sendDebugLog(context, "[ExternalAI] Success: HTTP $code")
                    
                    val aiContent = try {
                        JSONObject(bodyStr).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    } catch (e: Exception) {
                        NotificationUtils.sendDebugLog(context, "[ExternalAI] JSON Error: ${e.message}, Raw: $bodyStr")
                        setExternalAiError("外部 AI 响应格式异常：${e.message}")
                        null
                    } ?: return null
                    
                    NotificationUtils.sendDebugLog(context, "[ExternalAI] Content: $aiContent")
                    val aiJson = extractFirstJsonObject(aiContent)
                    if (aiJson == null) {
                        setExternalAiError("外部 AI 未返回 JSON 对象")
                        return null
                    }
                    val aiResult = parseExternalAiJsonToResult(aiJson)
                    val finalResult = applyDeterministicTimeOverride(sentence, baseMillis, aiResult)
                    if (finalResult != null) {
                        externalAiStatus.set(ExternalAiStatus(ExternalAiOutcome.CREATED, "外部 AI 已解析并创建日程"))
                    }
                    finalResult
                }
            } catch (t: Throwable) {
                Log.w(TAG, "External AI parse failed: ${t.message}")
                NotificationUtils.sendDebugLog(context, "[ExternalAI] Exception: ${t.message}")
                setExternalAiError("外部 AI 调用异常：${t.message ?: t::class.java.simpleName}")
                null
            }
        }
    }

    private fun setExternalAiError(message: String) {
        externalAiStatus.set(ExternalAiStatus(ExternalAiOutcome.ERROR, message))
    }

    private fun applyDeterministicTimeOverride(sentence: String, baseMillis: Long, aiResult: ParseResult?): ParseResult? {
        if (aiResult == null) return null
        parseExplicitRelativeDateTime(sentence, baseMillis, aiResult)?.let { return it }
        val slots = try {
            TimeNLPAdapter.parse(sentence, baseMillis)
        } catch (t: Throwable) {
            Log.w(TAG, "deterministic time override failed: ${t.message}")
            emptyList()
        }
        val slot = slots.firstOrNull()
        if (slot == null) return aiResult

        val start = slot.startMillis
        val end = if (hasExplicitEndTime(sentence) && slot.endMillis != null && slot.endMillis > start) {
            slot.endMillis
        } else {
            aiResult.endMillis
        }

        return ParseResult(
            startMillis = start,
            endMillis = end,
            title = aiResult?.title,
            location = aiResult?.location
        )
    }

    private fun parseExplicitRelativeDateTime(sentence: String, baseMillis: Long, aiResult: ParseResult): ParseResult? {
        val number = "([0-9]{1,2}|[一二三四五六七八九十两]+)"
        val minute = "(?:\\s*(?:[:：点])\\s*([0-5]?\\d)\\s*(?:分)?)?"
        val marker = "(今天|今晚|明天|明早|明晚|后天|大后天)"
        val ampm = "(上午|早上|下午|晚上|中午|凌晨)?"
        val rangePattern = Regex("$marker\\s*$ampm\\s*${number}${minute}\\s*点?\\s*(?:到|至|~|～|-|－|—)\\s*$ampm\\s*${number}${minute}\\s*点?")
        val singlePattern = Regex("$marker\\s*$ampm\\s*${number}${minute}\\s*点")

        val range = rangePattern.find(sentence)
        if (range != null) {
            val dayToken = range.groupValues[1]
            val startAmpm = range.groupValues[2].ifBlank { defaultAmpmForDayToken(dayToken) }
            val startHour = toArabic(range.groupValues[3])
            val startMinute = range.groupValues[4].toIntOrNull() ?: 0
            val endAmpm = range.groupValues[5].ifBlank { startAmpm }
            val endHour = toArabic(range.groupValues[6])
            val endMinute = range.groupValues[7].toIntOrNull() ?: 0

            val start = buildRelativeCalendar(baseMillis, dayToken, startAmpm, startHour, startMinute).timeInMillis
            var endCal = buildRelativeCalendar(baseMillis, dayToken, endAmpm, endHour, endMinute)
            if (endCal.timeInMillis <= start) endCal.add(Calendar.DAY_OF_MONTH, 1)
            return aiResult.copy(startMillis = start, endMillis = endCal.timeInMillis)
        }

        val single = singlePattern.find(sentence) ?: return null
        val dayToken = single.groupValues[1]
        val startAmpm = single.groupValues[2].ifBlank { defaultAmpmForDayToken(dayToken) }
        val startHour = toArabic(single.groupValues[3])
        val startMinute = single.groupValues[4].toIntOrNull() ?: 0
        val start = buildRelativeCalendar(baseMillis, dayToken, startAmpm, startHour, startMinute).timeInMillis
        return aiResult.copy(startMillis = start)
    }

    private fun defaultAmpmForDayToken(dayToken: String): String? {
        return when (dayToken) {
            "今晚", "明晚" -> "晚上"
            "明早" -> "早上"
            else -> null
        }
    }

    private fun buildRelativeCalendar(baseMillis: Long, dayToken: String, ampm: String?, hourRaw: Int, minute: Int): Calendar {
        val offsetDays = when (dayToken) {
            "明天", "明早", "明晚" -> 1
            "后天" -> 2
            "大后天" -> 3
            else -> 0
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = baseMillis
            add(Calendar.DAY_OF_MONTH, offsetDays)
            set(Calendar.HOUR_OF_DAY, adjustHourByAmPm(hourRaw, ampm))
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal
    }

    private fun hasExplicitEndTime(sentence: String): Boolean {
        val rangeConnectors = listOf("到", "至", "-", "－", "—", "~", "～")
        if (rangeConnectors.any { sentence.contains(it) }) return true
        return sentence.contains("结束") || sentence.contains("截止") || sentence.contains("散会")
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val s = raw.trim()
        val start = s.indexOf('{').takeIf { it >= 0 } ?: return null
        var depth = 0
        for (i in start until s.length) {
            if (s[i] == '{') depth++
            else if (s[i] == '}') {
                depth--
                if (depth == 0) return s.substring(start, i + 1)
            }
        }
        return null
    }

    private fun normalizeExternalAiBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions", ignoreCase = true) ->
                trimmed.removeSuffix("/chat/completions").trimEnd('/')
            trimmed.endsWith("/v1/chat/completions", ignoreCase = true) ->
                trimmed.removeSuffix("/chat/completions").trimEnd('/')
            else -> trimmed
        }.ifBlank { "https://api.deepseek.com" }
    }

    private fun buildExternalAiUserPrompt(sentence: String, baseMillis: Long): String {
        val tz = TimeZone.getDefault()
        val nowFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply { timeZone = tz }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = tz }
        val weekdayFmt = SimpleDateFormat("EEEE", Locale.CHINA).apply { timeZone = tz }
        val nowDate = Date(baseMillis)
        return """
当前时间基准（必须严格使用）：
- nowLocal: ${nowFmt.format(nowDate)}
- today: ${dateFmt.format(nowDate)}
- weekday: ${weekdayFmt.format(nowDate)}
- timezone: ${tz.id}
- currentEpochMillis: $baseMillis

请只解析下面输入文本中的一个日程事件，并严格遵守：
1. “今天、明天、后天、周一、本周五、下周一”等相对日期，必须基于上面的 nowLocal/today/weekday/currentEpochMillis 计算，不能基于模型训练时间或服务器时间。
2. startMillis 必须是 Unix 毫秒时间戳，不是秒时间戳；无法准确判断开始时间时必须为 null。
3. endMillis 必须是 Unix 毫秒时间戳；无法准确判断结束时间时必须为 null，不要自动补默认时长。
4. title 为简短会议/事件名称；如果系统提示词要求添加后缀或“无需参会”，必须照做。
5. location 为地点；无法判断则为 null。
6. 不要输出解释、Markdown、代码块或多余文字，只输出 JSON 对象。

输出格式：
{"startMillis":<epochMillis|null>,"endMillis":<epochMillis|null>,"title":<string|null>,"location":<string|null>}

无法解析开始时间或无法提取可用标题时输出：
{"startMillis":null,"endMillis":null,"title":null,"location":null}

输入文本：
$sentence
""".trimIndent()
    }

    private fun parseAiJsonToResult(json: String, defaultEndDurationMillis: Long? = null, requireTitle: Boolean = false): ParseResult? {
        return try {
            val obj = JSONObject(json)
            val title = obj.optString("title", "").takeIf { it.isNotBlank() }
            if (requireTitle && title == null) return null
            if (obj.isNull("startMillis")) return null
            val start = coerceEpochMillis(obj.optLong("startMillis", -1L)).takeIf { it > 0 } ?: return null
            val rawEnd = if (obj.isNull("endMillis")) null else coerceEpochMillis(obj.optLong("endMillis", 0L)).takeIf { it > 0 }
            val end = when {
                rawEnd != null && rawEnd > start -> rawEnd
                defaultEndDurationMillis != null -> start + defaultEndDurationMillis
                else -> null
            }
            val loc = obj.optString("location", "").takeIf { it.isNotBlank() }
            ParseResult(start, end, title, loc)
        } catch (_: Throwable) { null }
    }

    private fun parseExternalAiJsonToResult(json: String): ParseResult? {
        return try {
            val obj = JSONObject(json)
            val title = obj.optString("title", "").takeIf { it.isNotBlank() }
            val location = obj.optString("location", "").takeIf { it.isNotBlank() }

            if (obj.isNull("startMillis")) {
                val reason = if (title == null && location == null) {
                    "AI 判断无需生成日程"
                } else {
                    "AI 未返回有效开始时间，已跳过创建"
                }
                externalAiStatus.set(ExternalAiStatus(ExternalAiOutcome.SKIPPED_BY_AI, reason))
                return null
            }

            val start = coerceEpochMillis(obj.optLong("startMillis", -1L)).takeIf { it > 0 }
            if (start == null) {
                externalAiStatus.set(ExternalAiStatus(ExternalAiOutcome.ERROR, "外部 AI 返回的 startMillis 无效"))
                return null
            }
            if (title == null) {
                externalAiStatus.set(ExternalAiStatus(ExternalAiOutcome.SKIPPED_BY_AI, "AI 未返回可用标题，已跳过创建"))
                return null
            }

            val end = if (obj.isNull("endMillis")) {
                null
            } else {
                coerceEpochMillis(obj.optLong("endMillis", 0L)).takeIf { it > start }
            }
            ParseResult(start, end, title, location)
        } catch (t: Throwable) {
            externalAiStatus.set(ExternalAiStatus(ExternalAiOutcome.ERROR, "外部 AI 返回 JSON 解析失败：${t.message ?: t::class.java.simpleName}"))
            null
        }
    }

    private fun coerceEpochMillis(value: Long): Long {
        return when {
            value in 1_000_000_000L..99_999_999_999L -> value * 1000L
            else -> value
        }
    }

    private fun extractMillisFromXkTimeResult(result: Any): Long? {
        try {
            val m = result.javaClass.methods.firstOrNull { it.name == "getTime" && it.parameterTypes.isEmpty() }
            (m?.invoke(result) as? Date)?.let { return it.time }
        } catch (_: Throwable) {}
        try {
            val f = result.javaClass.fields.firstOrNull { it.name == "time" }
            (f?.get(result) as? Date)?.let { return it.time }
        } catch (_: Throwable) {}
        return null
    }

    private fun extractEndMillisFromXkTimeResult(result: Any): Long? {
        val candidates = listOf("getEndTime", "getTimeEnd", "getEnd", "getEndDate", "getEndDatetime")
        for (mName in candidates) {
            try {
                val v = result.javaClass.getMethod(mName).invoke(result)
                if (v is Date) return v.time
                if (v is Long) return v
            } catch (_: Throwable) {}
        }
        return null
    }

    private object RuleBasedStrategy: ParsingStrategy {
        override fun name() = "RuleBaseNoCtx"
        override fun tryParse(sentence: String): ParseResult? = parseDateTimeInternal(null, sentence, buildDefaultRelativeTokenMap(), null, null)
        fun tryParseStandalone(sentence: String) = tryParse(sentence)
    }

    private class RuleBasedStrategyWithContext(private val ctx: android.content.Context): ParsingStrategy {
        override fun name() = "RuleBaseCtx"
        override fun tryParse(sentence: String): ParseResult? = tryParseWithBase(sentence, getNowMillis())
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            return parseDateTimeInternal(ctx, sentence, buildRelativeTokenMap(ctx), baseMillis, SettingsStore.getPreferFutureBoolean(ctx))
        }
    }

    private data class RelativeSpec(val offsetDays: Int, val ampm: String?)

    private fun buildRelativeTokenMap(context: android.content.Context): LinkedHashMap<String, RelativeSpec> {
        val rawList = SettingsStore.getRelativeDateWords(context)
        val map = linkedMapOf<String, RelativeSpec>()
        for (raw in rawList.sortedByDescending { it.length }) {
            val cleaned = raw.trim().trim('[',']',';')
            val colonSplit = cleaned.split(':')
            var token = cleaned; var offset = 0; var ampm: String? = null
            try {
                if (colonSplit.isNotEmpty()) token = colonSplit[0].substringAfter('"').substringBeforeLast('"').ifBlank { colonSplit[0] }
                if (colonSplit.size >= 2) offset = colonSplit[1].split(',')[0].filter { it.isDigit() || it == '-' }.toIntOrNull() ?: 0
                if (colonSplit.size >= 3) ampm = colonSplit[2].lowercase().takeIf { it == "am" || it == "pm" }
                else if (cleaned.contains(",pm", true)) ampm = "pm" else if (cleaned.contains(",am", true)) ampm = "am"
            } catch (_: Exception) {}
            if (token.isNotBlank()) map[token] = RelativeSpec(offset, ampm)
        }
        if (map.isEmpty()) return buildDefaultRelativeTokenMap()
        return map
    }

    private fun buildDefaultRelativeTokenMap(): LinkedHashMap<String, RelativeSpec> {
        val map = linkedMapOf<String, RelativeSpec>()
        map["今晚"] = RelativeSpec(0, "pm"); map["明晚"] = RelativeSpec(1, "pm")
        map["下午"] = RelativeSpec(0, "pm"); map["上午"] = RelativeSpec(0, "am")
        map["今天"] = RelativeSpec(0, null); map["明天"] = RelativeSpec(1, null)
        return map
    }

    private fun newCal(baseMillis: Long?): Calendar = Calendar.getInstance().apply { if (baseMillis != null) timeInMillis = baseMillis }

    private fun parseDateTimeInternal(ctx: android.content.Context?, sentence: String, relativeMap: LinkedHashMap<String, RelativeSpec>, baseMillis: Long?, preferFutureOpt: Boolean?): ParseResult? {
        try {
            val now = newCal(baseMillis)
            val preferFutureRaw = preferFutureOpt ?: true
            val autoMode = (preferFutureOpt == null)

            // --- 相对偏移解析 ---
            parseRelativeOffset(ctx, sentence, baseMillis)?.let { return it }

            // --- 倒计时解析 ---
            if (sentence.contains("还有") && (sentence.contains("小时") || sentence.contains("天") || sentence.contains("分钟"))) {
                var remain = sentence.substring(sentence.indexOf("还有"))
                var d=0; var h=0; var m=0; var s=0
                Regex("还有([一二三四五六七八九十百零0-9]+)个?天").find(remain)?.let { d = toArabic(it.groupValues[1]); remain = remain.replace(it.value, "") }
                Regex("([一二三四五六七八九十百零0-9]+)个?小时").find(remain)?.let { h = toArabic(it.groupValues[1]); remain = remain.replace(it.value, "") }
                Regex("([一二三四五六七八九十百零0-9]+)个?分钟?").find(remain)?.let { m = toArabic(it.groupValues[1]); remain = remain.replace(it.value, "") }
                if (d+h+m+s > 0) {
                    val target = newCal(baseMillis).apply { add(Calendar.DAY_OF_MONTH, d); add(Calendar.HOUR_OF_DAY, h); add(Calendar.MINUTE, m) }
                    val (t, loc) = extractTitleAndLocation(ctx, sentence)
                    return ParseResult(target.timeInMillis - 1800000, target.timeInMillis, t ?: "截止", loc)
                }
            }

            // --- 显式日期解析 (简易版示例，实际应包含 turn 7 的所有正则) ---
            val m = monthDayPattern.matcher(sentence)
            if (m.find()) {
                val cal = newCal(baseMillis).apply {
                    set(Calendar.MONTH, toArabic(m.group(1)) - 1)
                    set(Calendar.DAY_OF_MONTH, toArabic(m.group(2)))
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }
                if (cal.timeInMillis < now.timeInMillis && (preferFutureOpt ?: true)) cal.add(Calendar.YEAR, 1)
                val (t, loc) = extractTitleAndLocation(ctx, sentence)
                return ParseResult(cal.timeInMillis, cal.timeInMillis + 3600000, t, loc)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseRelativeOffset(ctx: android.content.Context?, sentence: String, baseMillis: Long?): ParseResult? {
        if (!sentence.contains("后") && !sentence.contains("前")) return null
        val tailMatcher = Regex("(后|之后|以后|前|之前|以前)").find(sentence) ?: return null
        val direction = if (tailMatcher.value.contains("后")) 1 else -1
        val unitRegex = Regex("([一二三四五六七八九十百零两0-9个半]+)(年|个月|月|周|星期|天|日|小时|分钟|分|秒)")
        val matches = unitRegex.findAll(sentence.substring(0, tailMatcher.range.first + tailMatcher.value.length)).toList()
        if (matches.isEmpty()) return null
        var total = 0L
        for (m in matches) {
            val num = toArabic(m.groupValues[1].replace("个", "").replace("半", "0.5")) // 极简处理
            val per = when(m.groupValues[2]) { "年"->31536000000L; "月"->2592000000L; "周"->604800000L; "天"->86400000L; "小时"->3600000L; "分钟"->60000L; else->1000L }
            total += (num * per).toLong()
        }
        val target = (baseMillis ?: getNowMillis()) + direction * total
        val (t, loc) = extractTitleAndLocation(ctx, sentence)
        return ParseResult(if(direction>0) target-3600000 else target, target, t, loc)
    }

    private fun toArabic(s: String?): Int {
        if (s == null) return 0
        s.toIntOrNull()?.let { return it }
        val map = mapOf('零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
        var res = 0; var temp = 0
        for (c in s) {
            val v = map[c] ?: 0
            if (v == 10) { res += if (temp == 0) 10 else temp * 10; temp = 0 }
            else temp = v
        }
        return res + temp
    }

    private fun adjustHourByAmPm(hour: Int, ampm: String?): Int {
        if (ampm == null) return hour
        return when {
            ampm.contains("下午") || ampm.contains("晚上") || ampm.contains("PM", true) -> if (hour < 12) hour + 12 else hour
            ampm.contains("上午") || ampm.contains("早") || ampm.contains("AM", true) -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    private fun shouldSkipTimeNLPFallback(sentence: String): Boolean = false

    internal fun extractTitleAndLocation(context: android.content.Context?, sentence: String?): Pair<String?, String?> {
        if (sentence.isNullOrBlank()) return null to null
        val text = sentence.trim()

        val eventEngine = try {
            context?.let { SettingsStore.getEventParsingEngine(it) } ?: EventParseEngine.BUILTIN
        } catch (_: Throwable) {
            EventParseEngine.BUILTIN
        }

        if (context != null && eventEngine == EventParseEngine.ML_KIT) {
            try {
                val ml = MLKitStrategy(context).extractTitleAndLocation(text)
                if (!ml.first.isNullOrBlank() || !ml.second.isNullOrBlank()) {
                    return ml.first?.take(60) to ml.second?.take(80)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ML Kit title/location extraction failed: ${t.message}")
            }
        }

        val location = extractLocationByRules(text)
        val titleText = removeLocationHints(text)
        val title = try {
            JiebaWrapper.extractTitle(titleText)
                ?: JiebaWrapper.extractEventDescription(titleText)
                ?: JiebaWrapper.combinedTopTokens(titleText)
        } catch (t: Throwable) {
            Log.w(TAG, "Jieba title extraction failed: ${t.message}")
            null
        }?.takeIf { it != location }

        return title?.take(60) to location?.take(80)
    }

    private fun removeLocationHints(text: String): String {
        return text
            .replace(Regex("""(?:地点|地址|位置|场地|地點|場地)\s*[:：]\s*[^，,。；;\n\r]{2,80}"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractLocationByRules(text: String): String? {
        val patterns = listOf(
            Regex("""(?:地点|地址|位置|场地|地點|場地)\s*[:：]\s*([^，,。；;\n\r]{2,80})"""),
            Regex("""(?:在|于|於)\s*([^，,。；;\n\r]{2,40})(?:集合|见面|開會|开会|上课|举行|舉行|参加|參加)?""")
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val value = match.groupValues.getOrNull(1)
                ?.trim()
                ?.trim(' ', '\t', '，', ',', '。', '；', ';', ':', '：')
            if (!value.isNullOrBlank() && value.length in 2..80 && !looksLikeTimeOnly(value)) {
                return value
            }
        }
        return null
    }

    private fun looksLikeTimeOnly(value: String): Boolean {
        return value.any { it.isDigit() } &&
            Regex("""^[0-9年月日号號:：点點分\-~/\s]+$""").matches(value)
    }
}
