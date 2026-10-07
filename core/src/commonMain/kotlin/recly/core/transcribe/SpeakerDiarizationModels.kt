package recly.core.transcribe

/**
 * docs/15 "Android · Windows local transcription models": what the Android and Windows engines diarize
 * with — sherpa-onnx's `OfflineSpeakerDiarization` over pyannote segmentation-3.0 (MIT) and the 3D-Speaker
 * ERes2Net base speaker embedding (Apache-2.0), both from the hosts the speech model already comes from.
 * Pinned to a commit, a size and a SHA-256 (checked 2026-10-07), downloaded with the speech model by
 * [LocalModelStore] into a directory of their own. The Apple apps bundle their own models (FluidAudio).
 */
object SpeakerDiarizationModels {
    /** Named after what is in it, so a different model never lands on top of these files. */
    const val DIRECTORY = "diarization-pyannote-3.0-eres2net-base"
    const val SEGMENTATION = "segmentation.int8.onnx"
    const val EMBEDDING = "embedding.onnx"

    val files: List<ModelFile> = listOf(
        ModelFile(
            SEGMENTATION,
            "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
            1_540_506,
            "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
        ),
        ModelFile(
            EMBEDDING,
            "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            39_593_761,
            "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b",
        ),
    )
}
