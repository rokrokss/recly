import RecKit
import SwiftUI

/// docs/12 "Agent connection": the settings block that runs recly-events (events/) on this Mac so
/// a ChatGPT agent hears about each new transcript. Off by default, and only where recordings go to
/// Google Drive. It runs on this Mac's own Drive connection, so the one thing it asks for is an
/// OpenAI tunnel. The switch only decides whether it runs: the tunnel can be set up or changed with it
/// off, and the set-up guide is always there. On, one line under the switch says how the server is.
struct AgentConnectionSection: View {
    @ObservedObject var agent: AgentEventsController
    @Environment(\.blueprint) private var blueprint
    @Environment(\.openURL) private var openURL
    @State private var tunnelId = ""
    @State private var tunnelKey = ""
    /// "Change tunnel" was chosen: the fields are back over a saved tunnel.
    @State private var changingTunnel = false

    /// The set-up guide: the OpenAI tunnel and key, the ChatGPT app, the subscription prompt.
    /// The guide has a Korean page; every other app language gets the English one.
    static var guide: URL {
        URL(string: AppLanguage.resolvedCode == "ko" ? "https://recly.dev/agent.ko" : "https://recly.dev/agent")!
    }

    var body: some View {
        VStack(spacing: 0) {
            SectionHeader(loc("Agent connection")).padding(.horizontal, Space.m)
            SwitchRow(
                title: loc("Tell ChatGPT about new recordings"),
                subtitle: switchNote,
                isOn: Binding(get: { agent.enabled && !blocked }, set: { agent.enabled = $0 })
            )
            .disabled(blocked)
            if !blocked {
                // Off, the phase says nothing: the line is there only while the switch is on.
                status
                tunnelRow
                SectionFootnote(loc("Runs recly-events on this Mac. It reads only the names and links of new transcripts in your Drive and tells your ChatGPT agent through your own OpenAI tunnel."))
                HStack {
                    BlueprintButton(loc("Set-up guide"), tone: .quiet) { openURL(Self.guide) }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, Space.m)
                .padding(.bottom, Space.s)
            }
        }
        .onAppear {
            tunnelId = agent.tunnelId
            agent.refreshNow()
        }
        .onChange(of: agent.tunnelId) { _, saved in
            if tunnelId.isEmpty { tunnelId = saved }
        }
    }

    /// A build without the program, or a storage recly-events cannot watch: the switch says which,
    /// in the place of its second line, and cannot be turned.
    private var blocked: Bool {
        agent.phase == .unavailable || agent.phase == .notDrive
    }

    private var switchNote: String? {
        switch agent.phase {
        case .unavailable: loc("Not in this build")
        case .notDrive: loc("Works only when recordings are stored in Google Drive")
        default: nil
        }
    }

    /// One line under the switch, and only what the rows below do not already say: nothing while the
    /// tunnel row asks for its fields. The loader only while something is under way (docs/09 "Screen
    /// principles").
    @ViewBuilder
    private var status: some View {
        switch agent.phase {
        case .off, .unavailable, .notDrive, .needsSetup:
            EmptyView()
        case .needsDrive:
            line(loc("Connect Google Drive above to start"))
        case .starting:
            working(loc("Starting"))
        case .connecting:
            working(loc("Connecting to the tunnel"))
        case .running(let subscription):
            line(Self.text(subscription))
        case .tunnelError:
            line(loc("The tunnel is not connecting. Check the tunnel ID and key."), danger: true)
        case .elsewhere:
            line(loc("Already running outside Recly"))
        case .gaveUp:
            line(loc("Stopped after repeated errors. Turn it off and on to try again."), danger: true)
        }
    }

    /// What a running server means for the user: it works, or what to do next — never what is
    /// merely possible.
    private static func text(_ subscription: AgentEventsSubscription) -> String {
        switch subscription {
        case .active: loc("Your subscribed agent hears about each new transcript")
        case .none: loc("Add the app in ChatGPT and ask your agent to subscribe")
        case .ended: loc("The subscription ended. Ask your agent to subscribe again.")
        }
    }

    /// docs/05 "Secrets": a saved tunnel is a row that says so, as a saved transcription key is; the
    /// fields come back only to take a new one, and a key left empty keeps the saved one.
    @ViewBuilder
    private var tunnelRow: some View {
        if !agent.tunnelId.isEmpty, agent.tunnelKeySaved, !changingTunnel {
            SectionRow(
                title: loc("OpenAI tunnel"),
                subtitle: "\(BlueprintChip.selectionMark) \(RecKitStrings.localized("Saved on this device"))",
                subtitleColor: blueprint.palette.success
            ) {
                BlueprintButton(loc("Change tunnel"), tone: .quiet) {
                    tunnelId = agent.tunnelId
                    tunnelKey = ""
                    changingTunnel = true
                }
            }
        } else {
            SectionBlock {
                Text(verbatim: loc("OpenAI tunnel"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                BlueprintField(loc("Tunnel ID"), text: $tunnelId, mono: true, placeholder: "tunnel_…")
                BlueprintField(
                    loc("Tunnel key"), text: $tunnelKey, mono: true, secure: true,
                    placeholder: agent.tunnelKeySaved ? loc("Leave empty to keep the saved key") : "sk-…"
                )
                HStack(spacing: Space.s) {
                    Spacer(minLength: 0)
                    if changingTunnel {
                        BlueprintButton(loc("Cancel"), tone: .quiet) {
                            tunnelKey = ""
                            changingTunnel = false
                        }
                    }
                    BlueprintButton(loc("Save"), tone: .quiet) {
                        agent.saveTunnel(id: tunnelId, key: tunnelKey) { changingTunnel = false }
                        tunnelKey = ""
                    }
                    .disabled(!canSave)
                }
                if agent.saveFailed {
                    Text(verbatim: loc("Could not save the tunnel"))
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.danger)
                }
            }
        }
    }

    /// An ID, and a key unless one is saved already.
    private var canSave: Bool {
        !tunnelId.trimmingCharacters(in: .whitespaces).isEmpty
            && (agent.tunnelKeySaved || !tunnelKey.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    private func line(_ text: String, danger: Bool = false) -> some View {
        SectionBlock {
            Text(verbatim: text)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(danger ? blueprint.palette.danger : blueprint.palette.text)
        }
    }

    /// The square loader beside the sentence (docs/09 "Screen principles"), in the row it replaces.
    private func working(_ text: String) -> some View {
        SectionBlock {
            LoadingText(text: text, font: blueprint.fonts.bodySmall, color: blueprint.palette.text)
        }
    }
}
