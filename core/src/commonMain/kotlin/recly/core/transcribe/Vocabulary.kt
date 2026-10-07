package recly.core.transcribe

import recly.core.model.Language

/**
 * docs/08 "Vocabulary": which of the user's words each adapter sends, and how — every field, cap and
 * condition checked against the provider's own API reference on 2026-10-07. Conservative on purpose:
 * where a provider does not document the field for a language or a model, or a combination could fail
 * a request that works without it, nothing is sent. A vocabulary must never cost a transcription.
 *
 * [terms] is the list the settings hold — trimmed, unique ignoring case, in the order entered — and
 * each mapping keeps that order, dropping what its provider would refuse rather than cutting it.
 */
internal object Vocabulary {
    /** Whether [provider] would use a vocabulary at all with [model] (null: the plan's default) and [language]. */
    fun applies(provider: String, model: String?, language: Language, diarize: Boolean): Boolean = when (provider) {
        // Keyterms are documented for both models of the routing list, but whether Universal-2 — where
        // Korean and every language outside Universal-3.5 Pro's set go — honours them is not.
        AssemblyAiProvider.NAME -> language in ASSEMBLYAI_LANGUAGES
        // `keyterm` with `detect_language` is documented neither way.
        DeepgramProvider.NAME -> language != Language.AUTO && deepgramParameter(model ?: DeepgramProvider.MODEL) != null
        OpenAiCompatProvider.OPENAI_NAME -> openAiTakesPrompt(model ?: if (diarize) OpenAiCompatProvider.GPT_4O_DIARIZE else OpenAiCompatProvider.WHISPER_1)
        // Whisper-family models only; Together's other models take the field and ignore it.
        OpenAiCompatProvider.GROQ_NAME -> WHISPER in (model ?: GROQ_MODEL)
        OpenAiCompatProvider.TOGETHER_NAME -> WHISPER in (model ?: TOGETHER_MODEL)
        // Context biasing is "optimized for English; support for other languages is experimental".
        OpenAiCompatProvider.MISTRAL_NAME -> language == Language.EN && (model ?: MISTRAL_MODEL).startsWith("voxtral")
        ElevenLabsProvider.NAME -> (model ?: ElevenLabsProvider.MODEL).startsWith("scribe_v2")
        // "Korean and English only"; `enko` is not named.
        ClovaProvider.NAME -> language in setOf(Language.KO, Language.EN, Language.AUTO)
        // "Only when transcribing Korean audio": `sommers`, or `whisper` with `language=ko`.
        RtzrProvider.NAME -> language == Language.KO && (model ?: RtzrProvider.SOMMERS) in setOf(RtzrProvider.SOMMERS, RtzrProvider.WHISPER)
        // A phrase list with no locale (the multilingual model) is documented neither way.
        AzureProvider.NAME -> language != Language.KO_EN && language != Language.AUTO
        // Add-on features are Korean only; this adapter sends `ko-KR` for Korean and for `auto`.
        DagloProvider.NAME -> language == Language.KO || language == Language.AUTO
        // Custom dictionary is on `enhanced` and `standard`, "not yet" on the newer models.
        SpeechmaticsProvider.NAME -> (model ?: SpeechmaticsProvider.ENHANCED) in setOf(SpeechmaticsProvider.ENHANCED, "standard")
        // A list Rev cannot use fails the whole job; it is documented for English, French, German,
        // Portuguese and Spanish — not for Korean, which `auto` and `ko-en` are sent as.
        RevProvider.NAME -> language in setOf(Language.EN, Language.FR, Language.DE, Language.PT, Language.ES)
        // Phoneme matching after the transcription: it can only replace words, never fail the job.
        GladiaProvider.NAME -> true
        else -> false
    }

    /** `keyterms_prompt`: Universal-2's 200 at most, six words and 50 characters each. */
    fun assemblyAi(terms: List<String>): List<String> =
        terms.filter { words(it) <= 6 && it.length <= 50 }.take(200)

    /**
     * Deepgram's parameter for [model]: `keyterm` on Nova-3 — more than 500 tokens fails the request, so
     * the list stops at [DEEPGRAM_BUDGET] estimated tokens — and `keywords`, one word each, on the
     * models before it. Never both. Null for a model that takes neither.
     */
    fun deepgram(terms: List<String>, model: String): Pair<String, List<String>>? = when (deepgramParameter(model)) {
        KEYTERM -> KEYTERM to withinBudget(terms, DEEPGRAM_BUDGET, separator = 0)
        KEYWORDS -> KEYWORDS to terms.filter { words(it) == 1 }.take(100)
        else -> null
    }

    /**
     * The Whisper-style `prompt`: the terms joined with ", " — the OpenAI guide's own form — up to
     * [PROMPT_BUDGET] estimated tokens, under the 224 Whisper reads. Null when nothing fits.
     */
    fun prompt(terms: List<String>): String? =
        withinBudget(terms, PROMPT_BUDGET, separator = 1).joinToString(", ").takeIf { it.isNotEmpty() }

    /** Mistral `context_bias`: an item matches `^[^,\s]+$`, so spaces become `_` and commas go; 100 at most. */
    fun mistral(terms: List<String>): List<String> =
        terms.map { it.replace(",", "").trim().replace(WHITESPACE, "_") }.filter { it.isNotEmpty() }.distinct().take(100)

    /** ElevenLabs `keyterms`: under 50 characters, five words, none of `<>{}[]\`; 100 at most (more adds a minimum bill). */
    fun elevenLabs(terms: List<String>): List<String> =
        terms.filter { it.length < 50 && words(it) <= 5 && it.none { c -> c in "<>{}[]\\" } }.take(100)

    /**
     * CLOVA `boostings`: the official example's shape — one entry whose `words` is the terms joined with
     * ", " — with commas taken out of the terms and one-character terms left out (one syllable is not
     * boosted). Null when nothing is left.
     */
    fun clova(terms: List<String>): String? =
        terms.map { it.replace(",", "").trim() }.filter { it.length > 1 }.distinct().take(1000)
            .joinToString(", ").takeIf { it.isNotEmpty() }

    /**
     * RTZR `keywords`, 500 of at most 20 characters: on `sommers` complete Hangul syllables only; on
     * `whisper` Hangul, Latin letters, digits and spaces.
     */
    fun rtzr(terms: List<String>, model: String): List<String> = terms.filter { term ->
        term.length <= 20 && when (model) {
            RtzrProvider.SOMMERS -> term.all { it in '가'..'힣' }
            else -> term.all { it in '가'..'힣' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' }
        }
    }.take(500)

    /** Azure `phraseList.phrases`: the documented guidance is no more than 2,000. */
    fun azure(terms: List<String>): List<String> = terms.take(2000)

    /** Daglo `keywordBoost.keywords`: no documented cap; the settings' own applies. */
    fun daglo(terms: List<String>): List<String> = terms

    /** Speechmatics `additional_vocab`: up to six words each (longer ones are dropped there anyway), 1,000 at most. */
    fun speechmatics(terms: List<String>): List<String> = terms.filter { words(it) <= 6 }.take(1000)

    /**
     * Rev `custom_vocabularies`: no digits, at most 12 words, no word over 34 characters, a letter of the
     * language — Basic Latin alone for English, Latin letters with their accents for the others; 500 at most.
     */
    fun rev(terms: List<String>, language: Language): List<String> = terms.filter { term ->
        term.none { it.isDigit() } && words(term) <= 12 && term.split(WHITESPACE).all { it.length <= 34 } &&
            term.any { it.isLetter() } &&
            term.all { if (language == Language.EN) it.code < 0x80 else it.code < 0x80 || it in 'À'..'ſ' }
    }.take(500)

    /** Gladia `custom_vocabulary_config.vocabulary`: plain strings; 500 at most (Gladia publishes none). */
    fun gladia(terms: List<String>): List<String> = terms.take(500)

    /**
     * A token count no tokenizer here would beat: half a token for each ASCII character, three for any
     * other — a byte-level BPE spends at most one token per byte, and Hangul is three bytes in UTF-8.
     */
    fun estimatedTokens(text: String): Int = (text.sumOf { if (it.code < 0x80) 1 else 6 } + 1) / 2

    /** [terms] in order while they fit [budget], each with [separator] tokens for what joins it to the next. */
    private fun withinBudget(terms: List<String>, budget: Int, separator: Int): List<String> {
        var spent = 0
        return terms.takeWhile { term ->
            spent += estimatedTokens(term) + separator
            spent <= budget
        }
    }

    private fun deepgramParameter(model: String): String? = when {
        model.startsWith("nova-3") -> KEYTERM
        model.startsWith("nova-2") || model == "nova" || model.startsWith("enhanced") || model.startsWith("base") -> KEYWORDS
        else -> null
    }

    private fun openAiTakesPrompt(model: String): Boolean =
        model == OpenAiCompatProvider.WHISPER_1 ||
            (model.startsWith("gpt-4o-transcribe") && "diarize" !in model) ||
            model.startsWith("gpt-4o-mini-transcribe")

    private fun words(term: String): Int = term.split(WHITESPACE).count { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
    private const val WHISPER = "whisper"
    private const val GROQ_MODEL = "whisper-large-v3-turbo"
    private const val TOGETHER_MODEL = "openai/whisper-large-v3"
    private const val MISTRAL_MODEL = "voxtral-mini-latest"
    private const val KEYTERM = "keyterm"
    private const val KEYWORDS = "keywords"

    /** Deepgram fails a request whose keyterms pass 500 tokens; the estimate stays well under it. */
    private const val DEEPGRAM_BUDGET = 400

    /** Whisper reads 224 tokens of prompt; the estimate stays under it. */
    private const val PROMPT_BUDGET = 200

    /** Universal-3.5 Pro's languages (the explicit ones Recly offers); the rest are routed to Universal-2. */
    private val ASSEMBLYAI_LANGUAGES = setOf(
        Language.EN, Language.ES, Language.FR, Language.DE, Language.IT, Language.PT, Language.AR, Language.NL,
        Language.HI, Language.JA, Language.ZH_CN, Language.ZH_TW, Language.TR, Language.VI,
    )
}
