import AVFoundation
import Foundation
import Speech

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
struct SpeechBenchmark {
    static func main() async throws {
        guard #available(macOS 26.0, *) else {
            emit(["event": "unavailable", "reason": "macOS 26 is required"])
            return
        }
        let supported = await SpeechTranscriber.supportedLocales.map(\.identifier).sorted()
        let installed = await SpeechTranscriber.installedLocales.map(\.identifier).sorted()
        emit(["event": "capabilities", "available": SpeechTranscriber.isAvailable,
              "supported": supported, "installed": installed])
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: "ko-KR")) else {
            emit(["event": "unavailable", "reason": "Korean is not supported on this device"])
            return
        }
        let setupStart = ProcessInfo.processInfo.systemUptime
        let preparationModule = SpeechTranscriber(locale: locale, preset: .transcription)
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [preparationModule]) {
            try await request.downloadAndInstall()
        }
        emit(["event": "prepared", "seconds": ProcessInfo.processInfo.systemUptime - setupStart,
              "locale": locale.identifier])
        guard CommandLine.arguments.count > 1 else { return }
        let samples = try JSONDecoder().decode([Sample].self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        for sample in samples {
            let start = ProcessInfo.processInfo.systemUptime
            let thermalStart = ProcessInfo.processInfo.thermalState.rawValue
            let transcriber = SpeechTranscriber(locale: locale, preset: .timeIndexedProgressiveTranscription)
            let analyzer = SpeechAnalyzer(modules: [transcriber])
            let resultTask = Task { () throws -> (String, [[String: Any]], Double?) in
                var segments: [[String: Any]] = []
                var texts: [String] = []
                var first: Double?
                for try await result in transcriber.results {
                    if first == nil { first = ProcessInfo.processInfo.systemUptime - start }
                    if result.isFinal {
                        let text = String(result.text.characters)
                        texts.append(text)
                        segments.append(["start": result.range.start.seconds,
                                         "end": result.range.end.seconds, "text": text])
                    }
                }
                return (texts.joined(separator: " "), segments, first)
            }
            do {
                let file = try AVAudioFile(forReading: URL(fileURLWithPath: sample.audio))
                _ = try await analyzer.analyzeSequence(from: file)
                try await analyzer.finalizeAndFinishThroughEndOfInput()
                let (text, segments, first) = try await resultTask.value
                var record: [String: Any] = [
                    "event": "sample", "id": sample.id, "dataset": sample.dataset,
                    "duration": sample.duration, "text": text, "segments": segments,
                    "wall_seconds": ProcessInfo.processInfo.systemUptime - start,
                    "thermal_start": thermalStart,
                    "thermal_end": ProcessInfo.processInfo.thermalState.rawValue,
                ]
                if let first { record["first_result_seconds"] = first }
                emit(record)
            } catch {
                await analyzer.cancelAndFinishNow()
                resultTask.cancel()
                emit(["event": "sample", "id": sample.id, "dataset": sample.dataset,
                      "duration": sample.duration, "error": String(describing: error),
                      "wall_seconds": ProcessInfo.processInfo.systemUptime - start])
            }
        }
    }
}
