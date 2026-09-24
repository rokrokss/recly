import CoreML
import Foundation
import WhisperKit

struct Sample: Decodable {
    let id: String
    let audio: String
    let duration: Double
    let dataset: String
}

func emit(_ value: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data([10]))
}

@main
struct Benchmark {
    static func main() async throws {
        let args = CommandLine.arguments
        guard args.count >= 4 else {
            fatalError("Usage: WhisperKitBenchmark MANIFEST MODEL_FOLDER TOKENIZER_FOLDER [cpuAndGPU]")
        }
        let compute: MLComputeUnits = args.count > 4 && args[4] == "cpuAndGPU" ? .cpuAndGPU : .cpuAndNeuralEngine
        let start = ProcessInfo.processInfo.systemUptime
        let config = WhisperKitConfig(
            modelFolder: args[2], tokenizerFolder: URL(fileURLWithPath: args[3]),
            computeOptions: ModelComputeOptions(audioEncoderCompute: compute, textDecoderCompute: compute),
            verbose: false, logLevel: .error, prewarm: false, load: true, download: false
        )
        let kit = try await WhisperKit(config)
        emit(["event": "loaded", "seconds": ProcessInfo.processInfo.systemUptime - start,
              "engine": "whisperkit", "compute_units": String(describing: compute),
              "whisperkit_version": "1.1.0"])
        let samples = try JSONDecoder().decode([Sample].self, from: Data(contentsOf: URL(fileURLWithPath: args[1])))
        let options = DecodingOptions(
            task: .transcribe, language: "ko", temperature: 0, temperatureFallbackCount: 0,
            topK: 1, detectLanguage: false, skipSpecialTokens: true, wordTimestamps: false,
            concurrentWorkerCount: 1
        )
        for sample in samples {
            let start = ProcessInfo.processInfo.systemUptime
            let thermal = ProcessInfo.processInfo.thermalState.rawValue
            do {
                let results = try await kit.transcribe(audioPath: sample.audio, decodeOptions: options)
                let segments = results.flatMap(\.segments).map { segment -> [String: Any] in
                    ["start": segment.start, "end": segment.end, "text": segment.text]
                }
                emit(["event": "sample", "id": sample.id, "dataset": sample.dataset,
                      "duration": sample.duration, "text": results.map(\.text).joined(separator: " "),
                      "segments": segments, "wall_seconds": ProcessInfo.processInfo.systemUptime - start,
                      "thermal_start": thermal, "thermal_end": ProcessInfo.processInfo.thermalState.rawValue])
            } catch {
                emit(["event": "sample", "id": sample.id, "dataset": sample.dataset,
                      "duration": sample.duration, "error": String(describing: error),
                      "wall_seconds": ProcessInfo.processInfo.systemUptime - start])
            }
        }
    }
}
