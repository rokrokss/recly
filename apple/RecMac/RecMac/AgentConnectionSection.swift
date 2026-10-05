import RecKit
import SwiftUI

/// docs/12 "Agent connection": the settings block that runs recly-events (events/) on this Mac so
/// a ChatGPT agent hears about each new transcript. Off by default. On, it asks for what `serve`
/// needs — a Google sign-in and an OpenAI tunnel — and says in one sentence how the server is.
struct AgentConnectionSection: View {
    @ObservedObject var agent: AgentEventsController
    @Environment(\.blueprint) private var blueprint
    @Environment(\.openURL) private var openURL
    @State private var tunnelId = ""
    @State private var tunnelKey = ""

    /// The set-up guide: the OpenAI tunnel and key, the ChatGPT app, the subscription prompt.
    static let guide = URL(string: "https://github.com/rokrokss/recly/blob/main/events/README.md")!

    var body: some View {
        VStack(spacing: 0) {
            SectionHeader(loc("Agent connection")).padding(.horizontal, Space.m)
            SwitchRow(
                title: loc("Tell ChatGPT about new transcripts"),
                subtitle: agent.phase == .unavailable ? loc("Not in this build") : nil,
                isOn: Binding(get: { agent.enabled }, set: { agent.enabled = $0 })
            )
            .disabled(agent.phase == .unavailable)
            if agent.enabled, agent.phase != .unavailable {
                googleRow
                SectionBlock {
                    BlueprintField(loc("Tunnel ID"), text: $tunnelId, mono: true, placeholder: "tunnel_…")
                    BlueprintField(loc("Tunnel key"), text: $tunnelKey, mono: true, secure: true, placeholder: "sk-…")
                    BlueprintButton(loc("Save"), tone: .quiet) {
                        agent.saveTunnel(id: tunnelId, key: tunnelKey)
                        tunnelKey = ""
                    }
                    .disabled(tunnelId.trimmingCharacters(in: .whitespaces).isEmpty)
                    if agent.saveFailed {
                        Text(verbatim: loc("Could not save the tunnel"))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.danger)
                    }
                }
                status
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

    /// A working sign-in has no button: the row offers Connect without one, and Connect again only
    /// once Google has refused the stored one.
    @ViewBuilder
    private var googleRow: some View {
        if !agent.googleSignedIn {
            connectRow(loc("Not connected"), button: loc("Connect"))
        } else if agent.googleEnded {
            connectRow(loc("Sign-in ended"), button: loc("Connect again"))
        } else {
            SectionRow(title: loc("Google Drive"), subtitle: loc("Connected"))
        }
    }

    private func connectRow(_ subtitle: String, button: String) -> some View {
        SectionRow(title: loc("Google Drive"), subtitle: subtitle) {
            BlueprintButton(button, tone: .quiet) { agent.connectGoogle() }
                .disabled(agent.phase == .signingIn)
        }
    }

    /// One sentence for where things are; the loader only while something is under way.
    @ViewBuilder
    private var status: some View {
        switch agent.phase {
        case .off, .unavailable:
            EmptyView()
        case .signingIn:
            working(loc("Finish signing in in your browser"))
        case .starting:
            working(loc("Starting"))
        case .connecting:
            working(loc("Connecting to the tunnel"))
        case .needsSetup:
            SectionRow(title: loc("Connect Google Drive and save a tunnel to start"))
        case .running(let subscription):
            SectionRow(title: loc("Running — ChatGPT can reach this Mac"), subtitle: Self.text(subscription))
        case .tunnelError:
            SectionRow(title: loc("The tunnel is not connecting. Check the tunnel ID and key."))
        case .googleEnded:
            SectionRow(title: loc("Google sign-in ended. Connect again."))
        case .elsewhere:
            SectionRow(title: loc("Already running outside Recly"))
        case .gaveUp:
            SectionRow(title: loc("Stopped after repeated errors. Turn it off and on to try again."))
        }
    }

    /// The square loader beside the sentence (docs/09 "Screen principles"), in the row it replaces.
    private static func text(_ subscription: AgentEventsSubscription) -> String {
        switch subscription {
        case .active: loc("Your agent is subscribed")
        case .none: loc("No agent has subscribed yet")
        case .ended: loc("The subscription ended. Ask your agent to subscribe again.")
        }
    }

    private func working(_ text: String) -> some View {
        SectionBlock {
            LoadingText(text: text, font: blueprint.fonts.bodySmall, color: blueprint.palette.text)
        }
    }
}
