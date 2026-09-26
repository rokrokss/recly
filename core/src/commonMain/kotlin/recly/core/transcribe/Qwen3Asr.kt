package recly.core.transcribe

import recly.core.model.Language
import recly.core.model.wire

/**
 * docs/05 "고정 처리 설정 도입": what the Android and Windows engines run — Qwen3-ASR 0.6B INT8 (Apache-2.0) as
 * exported for sherpa-onnx, and the Silero VAD (MIT) that cuts a recording into the pieces it reads.
 * Every file is pinned to a commit and a hash; docs/15 lists the two hosts they come from.
 */
object Qwen3Asr {
    const val NAME = "qwen3-asr"
    /** What settings call it: a product name, not translated. */
    const val DISPLAY_NAME = "Qwen3-ASR 0.6B"
    /** Changes whenever a file or the runtime does, so a checkpoint from another model is not resumed. */
    const val REVISION = "qwen3-asr-0.6b-int8-2026-03-25+sherpa-onnx-1.13.8"
    /** Named after the revision, so a new model never lands on top of the old one's files. */
    const val DIRECTORY = "qwen3-asr-0.6b-int8-2026-03-25"
    const val VAD = "silero_vad.onnx"
    const val TOKENIZER = "tokenizer"

    private const val HF = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"

    val files: List<ModelFile> = listOf(
        ModelFile("conv_frontend.onnx", "$HF/conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
        ModelFile("encoder.int8.onnx", "$HF/encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
        ModelFile("decoder.int8.onnx", "$HF/decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
        ModelFile("$TOKENIZER/vocab.json", "$HF/tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        ModelFile("$TOKENIZER/merges.txt", "$HF/tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        ModelFile("$TOKENIZER/tokenizer_config.json", "$HF/tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
        ModelFile(VAD, "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )

    /** The spoken languages the model knows, by the English name its prompt takes. Ukrainian is not one. */
    private val names = mapOf(
        Language.KO to "Korean", Language.EN to "English", Language.JA to "Japanese",
        Language.ZH_CN to "Chinese", Language.ZH_TW to "Chinese", Language.ES to "Spanish",
        Language.FR to "French", Language.DE to "German", Language.PT to "Portuguese",
        Language.AR to "Arabic", Language.HI to "Hindi", Language.RU to "Russian",
        Language.IT to "Italian", Language.ID to "Indonesian", Language.TR to "Turkish",
        Language.VI to "Vietnamese", Language.TH to "Thai", Language.NL to "Dutch", Language.PL to "Polish",
    )

    val languages: List<Language> = TranscriptionLanguages.explicit.filter { it in names }

    /** The prompt's language for a wire code: empty lets the model detect it, null means it cannot. */
    fun hint(language: String): String? = when (language) {
        Language.AUTO.wire, Language.KO_EN.wire -> ""
        else -> names.entries.firstOrNull { it.key.wire == language }?.value
    }
}
