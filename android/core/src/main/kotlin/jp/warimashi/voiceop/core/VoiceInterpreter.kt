package jp.warimashi.voiceop.core

/**
 * 音声認識結果をバッファに反映し、読み上げる文言を決める（設計書5〜8章）。
 * Android に依存しない純粋なロジックなので、単体テストで挙動を確認できる。
 */
object VoiceInterpreter {

    enum class Action { NONE, SEND }

    data class Outcome(
        val buffer: EntryBuffer,
        /** 読み上げる文言。 */
        val speech: String,
        /** SEND のとき、呼び出し側が buffer.toEntry() を送信し、成功したらバッファをクリアする。 */
        val action: Action = Action.NONE,
        /** エラー音を鳴らすべき結果か。 */
        val error: Boolean = false,
    )

    const val NOT_UNDERSTOOD = "聞き取れませんでした。もう一度お願いします"
    const val REASON_PROMPT = "理由をどうぞ"

    /**
     * @param candidates SpeechRecognizer が返した認識候補（確からしい順）
     * @param customers 登録済み顧客名（Firebase の warashi_customers）
     */
    fun handle(buffer: EntryBuffer, candidates: List<String>, customers: List<String>): Outcome {
        val parsed = candidates.map { it to UtteranceParser.parse(it) }
        val first = parsed.firstOrNull { it.second != null }?.second
            ?: return Outcome(buffer, NOT_UNDERSTOOD, error = true)

        return when (first) {
            is Utterance.Command -> command(buffer, first.command)
            is Utterance.Updates -> {
                // 顧客名は他の認識候補も照合に使う（同音異字の揺れ対策）
                val customerAlternatives = parsed.mapNotNull { (_, u) ->
                    ((u as? Utterance.Updates)?.updates
                        ?.firstOrNull { it.field == Field.CUSTOMER }?.value as? FieldValue.Single)?.value
                }
                apply(buffer, first.updates, customerAlternatives, customers)
            }
        }
    }

    private fun command(buffer: EntryBuffer, command: Vocabulary.Command): Outcome = when (command) {
        Vocabulary.Command.CANCEL -> Outcome(EntryBuffer(), "取り消しました")
        Vocabulary.Command.READBACK ->
            if (buffer.isEmpty) Outcome(buffer, "まだ何も入力されていません")
            else Outcome(buffer, (listOf(readback(buffer), status(buffer)) + reasonPrompt(buffer)).joinToString("。"))
        Vocabulary.Command.SEND -> {
            val missing = buffer.missingRequired
            when {
                missing.isNotEmpty() ->
                    Outcome(buffer, "送信できません。${missing.joinToString("、") { it.speech }}が足りません", error = true)
                !buffer.isSendable ->
                    Outcome(buffer, "送信できません。その組み合わせは選べません。割増を言い直してください", error = true)
                else -> Outcome(buffer, "", action = Action.SEND)
            }
        }
    }

    private fun apply(
        start: EntryBuffer,
        updates: List<FieldUpdate>,
        customerAlternatives: List<String>,
        customers: List<String>,
    ): Outcome {
        var b = start
        val said = ArrayList<String>()
        val errors = ArrayList<String>()
        var spokenSurcharge: FieldValue? = null
        /** 理由の読み上げは割増の後にする（「2割、理由、大きさ」の順）。 */
        var reasonSaid: String? = null
        var itemOrSizeChanged = false

        for (u in updates) {
            val v = u.value
            when (u.field) {
                Field.NUMBER -> {
                    b = b.copy(plate = (v as? FieldValue.Single)?.value)
                    said += if (v is FieldValue.Single) "ナンバー ${spellDigits(v.value)}" else "ナンバー、空欄"
                }
                Field.ITEM -> {
                    val value = (v as? FieldValue.Single)?.value
                    itemOrSizeChanged = itemOrSizeChanged || value != b.item
                    b = b.copy(item = value)
                    said += value?.let { Vocabulary.itemTerm(it)?.speech ?: it } ?: "品目、空欄"
                }
                Field.SIZE -> {
                    val value = (v as? FieldValue.Single)?.value
                    itemOrSizeChanged = itemOrSizeChanged || value != b.size
                    b = b.copy(size = value)
                    said += value?.let { Vocabulary.sizeTerm(it)?.speech ?: it } ?: "サイズ、空欄"
                }
                Field.SURCHARGE -> spokenSurcharge = v
                Field.REASON -> {
                    var values = (v as? FieldValue.Multi)?.values.orEmpty()
                    if (values.size > EntryBuffer.MAX_REASONS) {
                        values = values.take(EntryBuffer.MAX_REASONS)
                        errors += "理由は${EntryBuffer.MAX_REASONS}つまでです"
                    }
                    b = b.copy(reasons = values)
                    reasonSaid = if (values.isEmpty()) "理由、空欄"
                    else "理由、" + values.joinToString("、") { Vocabulary.reasonTerm(it)?.speech ?: it }
                }
                Field.REJECT -> {
                    val values = (v as? FieldValue.Multi)?.values.orEmpty()
                    b = b.copy(rejects = values)
                    said += if (values.isEmpty()) "拒否、空欄"
                    else "拒否、" + values.joinToString("、") { Vocabulary.rejectTerm(it)?.speech ?: it }
                }
                Field.CUSTOMER -> {
                    if (v is FieldValue.Single) {
                        val heard = listOf(v.value) + customerAlternatives.filter { it != v.value }
                        val m = CustomerMatcher(customers).match(heard)!!
                        b = b.copy(customer = m.name, customerRegistered = m.registered)
                        // 自動補正の誤りに気づけるよう、必ず読み上げる（設計書8章）
                        said += "客先、${m.name}" + if (m.registered) "" else "、登録にない名前です"
                    } else {
                        b = b.copy(customer = null, customerRegistered = true)
                        said += "客先、空欄"
                    }
                }
            }
        }

        // ---- 割増の決定と組み合わせ検証（設計書4章） ----
        val fixed = SurchargeRules.fixedFor(b.item)
        val bothKnown = b.item != null && b.size != null
        val spoken = spokenSurcharge
        when {
            spoken is FieldValue.Single -> {
                val invalid = (bothKnown && !SurchargeRules.isAllowed(b.item, b.size, spoken.value)) ||
                    (fixed != null && spoken.value != fixed)
                if (invalid) {
                    b = b.copy(surcharge = fixed, surchargeAuto = fixed != null)
                    errors += "${Vocabulary.surchargeTerm(spoken.value)?.speech}、その組み合わせは選べません。割増を言い直してください"
                } else {
                    b = b.copy(surcharge = spoken.value, surchargeAuto = false)
                    said += Vocabulary.surchargeTerm(spoken.value)?.speech ?: spoken.value
                }
            }
            spoken == FieldValue.Clear -> {
                b = b.copy(surcharge = fixed, surchargeAuto = fixed != null)
                said += "割増、空欄"
            }
            fixed != null -> {
                if (b.surcharge != fixed) said += "割増なし"
                b = b.copy(surcharge = fixed, surchargeAuto = true)
            }
            itemOrSizeChanged && b.surcharge != null -> {
                // 石・瓦で自動設定していた割増、または新しい組み合わせで選べない割増は外す
                if (b.surchargeAuto || (bothKnown && !SurchargeRules.isAllowed(b.item, b.size, b.surcharge))) {
                    b = b.copy(surcharge = null, surchargeAuto = false)
                    errors += "割増が合わなくなりました。割増を言い直してください"
                }
            }
        }

        reasonSaid?.let { said += it }

        val parts = ArrayList<String>()
        if (said.isNotEmpty()) parts += said.joinToString("、")
        parts += errors
        if (errors.isEmpty()) {
            parts += status(b)
            parts += reasonPrompt(b)
        }
        return Outcome(b, parts.joinToString("。"), error = errors.isNotEmpty())
    }

    /** バッファ全体の読み上げ文。 */
    fun readback(b: EntryBuffer): String {
        val p = ArrayList<String>()
        p += if (b.plate != null) "ナンバー ${spellDigits(b.plate)}" else "ナンバーなし"
        b.item?.let { p += Vocabulary.itemTerm(it)?.speech ?: it }
        b.size?.let { p += Vocabulary.sizeTerm(it)?.speech ?: it }
        b.surcharge?.let { p += Vocabulary.surchargeTerm(it)?.speech ?: it }
        if (b.reasons.isNotEmpty()) p += "理由、" + b.reasons.joinToString("、") { Vocabulary.reasonTerm(it)?.speech ?: it }
        b.customer?.let { p += "客先、$it" }
        if (b.rejects.isNotEmpty()) p += "拒否、" + b.rejects.joinToString("、") { Vocabulary.rejectTerm(it)?.speech ?: it }
        return p.joinToString("、")
    }

    /** 送信できる状態か、あと何が足りないか。 */
    fun status(b: EntryBuffer): String {
        val missing = b.missingRequired
        return when {
            missing.isEmpty() && b.isSendable -> "送信できます"
            missing.isEmpty() -> "割増を言い直してください"
            else -> "あと、" + missing.joinToString("、") { it.speech }
        }
    }

    /** 割増が2割・4割・不良で理由が未入力なら「理由をどうぞ」（[SurchargeRules.PROMPTS_REASON]）。 */
    private fun reasonPrompt(b: EntryBuffer): List<String> =
        if (SurchargeRules.promptsReason(b)) listOf(REASON_PROMPT) else emptyList()

    /** 「1234」を1桁ずつ読ませる（「せんにひゃく…」と読まれないように）。 */
    private fun spellDigits(s: String): String = s.toList().joinToString(" ")
}
