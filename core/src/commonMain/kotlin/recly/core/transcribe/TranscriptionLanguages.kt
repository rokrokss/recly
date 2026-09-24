package recly.core.transcribe

import recly.core.model.Language
import recly.core.model.wire

/** Language choices and request codes for the shipped batch adapters. */
object TranscriptionLanguages {
    val all: List<Language> = Language.entries.toList()
    val explicit: List<Language> = all.filter { it != Language.AUTO && it != Language.KO_EN }

    fun supported(provider: String, model: String? = null): List<Language> {
        val choices = when (provider) {
            "clova" -> listOf(Language.KO, Language.EN, Language.KO_EN, Language.JA, Language.ZH_CN, Language.ZH_TW)
            // Keep the existing adapter's verified contract until its other language codes are confirmed.
            "daglo" -> listOf(Language.KO, Language.EN, Language.KO_EN)
            "mistral" -> all.filter { it !in listOf(Language.ID, Language.TR, Language.VI, Language.TH, Language.PL, Language.UK) }
            "rtzr" -> if (model == "sommers") listOf(Language.KO, Language.JA) else all
            "groq" -> if (model?.endsWith(".en") == true) listOf(Language.EN) else all
            "deepgram" -> when {
                model?.startsWith("nova-2-") == true && model != "nova-2-general" -> listOf(Language.EN)
                model == "nova-2" || model == "nova-2-general" -> all.filter { it != Language.AR }
                else -> all
            }
            "rev" -> all.filter { it != Language.AUTO }
            "assemblyai", "azure", "elevenlabs", "gladia", "speechmatics", "openai", "together" -> all
            else -> emptyList()
        }
        return choices
    }

    /** Chinese script is a locale preference; APIs using ISO 639 receive Mandarin's base code. */
    fun code(language: Language): String = when (language) {
        Language.ZH_CN, Language.ZH_TW -> "zh"
        Language.KO_EN -> "ko"
        else -> language.wire
    }

    fun localeTag(language: Language): String = when (language) {
        Language.KO, Language.KO_EN -> "ko-KR"
        Language.EN -> "en-US"
        Language.JA -> "ja-JP"
        Language.ZH_CN -> "zh-CN"
        Language.ZH_TW -> "zh-TW"
        Language.ES -> "es-ES"
        Language.FR -> "fr-FR"
        Language.DE -> "de-DE"
        Language.PT -> "pt-BR"
        Language.AR -> "ar-SA"
        Language.HI -> "hi-IN"
        Language.RU -> "ru-RU"
        Language.IT -> "it-IT"
        Language.ID -> "id-ID"
        Language.TR -> "tr-TR"
        Language.VI -> "vi-VN"
        Language.TH -> "th-TH"
        Language.NL -> "nl-NL"
        Language.PL -> "pl-PL"
        Language.UK -> "uk-UA"
        Language.AUTO -> ""
    }

    fun preferred(deviceLocale: String): Language {
        val tag = deviceLocale.replace('_', '-').lowercase()
        val parts = tag.split('-')
        if (parts.first() == "zh") return when {
            "hans" in parts -> Language.ZH_CN
            "hant" in parts || parts.any { it in listOf("tw", "hk", "mo") } -> Language.ZH_TW
            else -> Language.ZH_CN
        }
        return explicit.firstOrNull { it.wire == tag.substringBefore('-') } ?: Language.EN
    }
}
