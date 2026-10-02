# Preparing the iOS new-submission review

2026-10-01: 0.1.0 passed review and was released on the App Store. From the next submission on, the review is an update review.

A resubmission preparation document matched to the current code. Check the actual submitted version and build number in the archive and App Store Connect and fill them into the blanks below. After completing the physical-device checks and the blanks below, put the same content into the review reply in App Store Connect and into App Review Information → Notes. Do not write in advance that the video or the physical-device verification is complete.

For the public name, subtitle, description and keywords, use the [store copy guide](app-store-copy.md) and the [English copy](app-store-metadata.en.txt). Do not put review accounts or keys in the public copy.

## Complete before submission

- Publish the changed privacy policy at the public URL, and confirm that the English (`https://recly.dev/policy/privacy-policy`) and Korean (`https://recly.dev/policy/privacy-policy.ko`) links in the app's settings actually open. Register the canonical English address as the privacy policy URL in App Store Connect.
- Set the build numbers of the iPhone app, the embedded Watch app and the widgets to the same number not yet used in App Store Connect, then make a new build with `make ios-archive`. The project's current default `CURRENT_PROJECT_VERSION` is `33`, and the release script does not raise it automatically. The command builds the current core and checks the Google client ID and the callback scheme inside the archive. This check does not replace a sign-in on a physical device, which confirms the iOS bundle ID, the OAuth publishing status and the test-user restriction in the Google console.
- Verify the build to be submitted on a real iPhone running the latest official iOS. Go through recording while locked → stop and enter a name → list → playback → Drive upload and open → optional transcription → checking the result.
- Check this behavior: when saving Settings → Recording processing → External API, the confirmation screen for a new target, “Send recordings to {provider}?” (Don't allow means nothing is saved, Allow & save means it is saved); no further confirmation when saving the same target again; the same confirmation when saving after importing settings; jobs waiting after permission is withdrawn; and earlier uploads not repeated after permission is granted again in Settings.
- Check connecting and disconnecting Google Drive, the choice between local and Drive deletion of a recording, and deleting API keys individually in the recording processing settings. There is no Recly account creation of its own, and the app does not provide a Google account deletion feature either.
- If the submission includes Apple Watch, also verify recording on a real Watch, transfer to the iPhone, and waiting for permission and resuming. Record the supported devices, OS, app version, build number and results.
- Start the screen recording by launching the app from the home screen. Show the actual main flow and the configured optional features, and do not expose passwords, API keys or personal recordings. Confirm that the reviewer can open the video URL without a separate access request.
- Basic recording works without an API key. If reviewing transcription needs a provider account or API access, provide dedicated test credentials and exact setup steps in the private fields of App Review Information. Do not put keys in this repository, a settings export file or the video.
- Compare the App Privacy answers with the actual data processing and retention terms of Google and the optional transcription providers. Do not settle on Apple's “Data Not Collected” on the basis of “there is no developer server” alone.

- Verify the mainland China (`CHN`), United States (`USA`) and region-lookup-failure states. Check that OpenAI is blocked in the list, editing, import and running existing jobs, that the other transcription providers remain, and that the waiting does not repeat Drive uploads that already completed. Record StoreKit real-account/sandbox checks separately from unit tests that use an injected region.

## Review reply drafts

Fill the bracketed items below with actual verification results, and do not write that features not verified on that build were demonstrated.

1. **Physical-device demonstration**

   Video: [accessible video URL]. Tested build: [version/build], device: [physical device], OS: [version]. The recording starts with launching the app and demonstrates [the flows actually shown].

2. **Purpose and audience**

   Recly is a general-purpose audio recorder for individuals who want to retain recordings in their own Google Drive and optionally transcribe them on device or with a selected external API. It supports personal notes, interviews and meetings that the user is authorized to record. Recly does not operate a developer-hosted backend or a Recly account system.

3. **Access and setup**

   Launch the app, grant microphone access when prompted, record, stop and enter a title. Open the recording list to play the recording and view its processing status. Use Settings → Google Drive → Connect Drive to authorize uploads to your own Drive. Recording processing offers local transcription, an external API or Off; the user selects the provider and explicitly permits transmission on iPhone before those requests are sent. Permission can be withdrawn in Settings → Privacy.

   Optional-feature review setup and credentials: [exact steps and location of private demo credentials, or explain which optional features are configured for review]. Required sample files: [none for microphone recording; list any files actually used for review]. Settings → Google Drive → Disconnect Drive revokes Google access and clears this device’s connection after one confirmation, while retaining recordings and settings. API keys can be removed individually from the Settings → Recording processing → External API → API keys; recordings can be deleted from the recording list.

4. **External services**

   Google OAuth provides authorization to the user's Google Drive. Google Drive stores recordings, metadata and any transcription result files. Optional speech-to-text integrations are AssemblyAI, NAVER Clova Speech, RTZR, OpenAI, Groq, Together AI, Mistral, ElevenLabs, Deepgram, Microsoft Azure Speech, Daglo, Speechmatics, Rev AI and Gladia. Only the provider selected in processing settings or an existing queued recording receives the transcription request. Apple WatchConnectivity transfers recordings between the user's paired Watch and iPhone. There is no Recly-operated payment processor or in-app purchase flow in the current code; external providers may charge the user's own account for API usage.

5. **Regional behavior**

   The iOS app disables the OpenAI transcription integration for the China mainland App Store storefront. It checks the StoreKit storefront before transcription and before each provider request; existing jobs and imported settings cannot bypass the restriction. If the storefront cannot be determined, the affected step waits without transmitting to the provider. The provider is also unavailable in the provider picker and save flow. Apple Watch recordings are processed by the paired iPhone under the same restriction. Local recording and Google Drive upload are independent of this restriction. The interface is available in 12 languages (English, Korean, Japanese, Simplified and Traditional Chinese, Spanish, French, German, Portuguese, Arabic, Hindi and Russian). [Verify CHN, a supported non-China storefront and unavailable-storefront behavior on the submitted build before sending.]

6. **Regulated services and third-party material**

   Recly is a general-purpose recording utility and does not itself provide medical, financial or other regulated professional services. Recordings are supplied by the user. Open-source dependency notices are included with the app. [Attach any additional authorization documents if the submitted app or store metadata contains protected third-party material requiring them.]

## Basis

- [Privacy section of Apple's App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/#privacy): requires a privacy policy accessible within the app and explicit permission before sharing with third parties and AI services.
- [Apple App Privacy details](https://developer.apple.com/app-store/app-privacy-details/): reviewing the collected data types according to the actual data processing of the app and third parties.
- Code and product contract: [design §15](recly.md), [privacy policy](policy/privacy-policy.ko.md), [development and build commands](development.md).


## 2026-09-18 · Guideline 4.8 reconsideration reply

Send the text below after confirming recording, saving and playback without Google connected on a real iPhone with the build actually being submitted. Replace `[submitted version/build]` with the actual number, and attach a demonstration filmed while not signed in. Do not claim in advance that the video and the physical-device verification are complete. The iPhone and Mac apps removed the Google Sign-In SDK and switched to OAuth that requests only `drive.file`. They do not request account identification permissions (`openid`, `email`, `profile`), and treat the connection as complete only after the Drive permission and the token storage are confirmed. This change is meant to separate signing in to an app account from approving Drive permission; it does not guarantee approval.

> Hello App Review Team,
>
> Thank you for your feedback. Recly does not create or authenticate a Recly user account. Google OAuth authorizes access to the user's own Google Drive for storing and accessing recording files. The iPhone and Mac apps request only the `drive.file` scope, without `openid`, `email`, or `profile`, and do not create a Recly login session.
>
> Users can record and play locally stored audio without connecting Google. Google authorization is required for Drive features and recording processing that depends on Drive, including transcription in the current version.
>
> In the submitted build [submitted version/build], Settings identifies this integration as Google Drive and states that users can record locally and connect Drive to upload. The connection is configured in Settings, and the recording list distinguishes local storage from pending Drive uploads.
>
> To verify, launch without connecting Google, record and save audio, then open it from the List tab for playback. Settings → Google Drive is where the optional storage connection is configured.
>
> We respectfully request reconsideration of Guideline 4.8 because Google authentication authorizes access to the user’s own Drive content and does not establish or authenticate a primary account with Recly.

Demonstration material: [enter the submitted build, device and OS actually verified, and an accessible video URL].


## 2026-09-26 · Guideline 5.1.1(i)·5.1.2(i) reply (sending to third-party AI services)

Finding: before sending personal data (recordings) to a third-party AI service, the app does not disclose what it sends and to whom, and does not obtain permission. The policy must also disclose collection, use and sharing, and the third parties' equal protection.

Cause: the review builds (17, 18) asked “Allow & save” only inside the workflow editor, and the switch to the fixed processing plan on 2026-09-25 dropped that confirmation (permission was asked only in the job waiting after a recording). On 2026-09-26 the confirmation screen at the time settings are saved was restored and strengthened (§15).

Before sending, film the confirmation screen on a real iPhone with the new build and fill in the `[ ]`. The confirmation screen can be reached without an API key.

The third parties' “equal protection” (2026-09-26 user decision: keep the 14 providers and disclose the per-provider differences in the policy): checking each provider's API terms against official documents, the ones that by default use the data only to provide the service and do not train on it are OpenAI, Groq, Azure and RTZR; Deepgram joins them because the app sends `mip_opt_out=true` with every request. Policy §3(2) does not claim equal protection by every provider; it discloses each provider's retention and training terms and links to its policy. Because of this, the app may be rejected again under 5.1.1(i).

> Hello App Review Team,
>
> Thank you for the review. Recly sends a recording to a third-party AI service only when the user chooses one: a speech-to-text provider such as OpenAI or ElevenLabs, selected in Settings → Recording processing → External API and used with the user's own API key. New installations use on-device transcription or no transcription, and send nothing to any AI service.
>
> In build [version/build], saving External API with a provider the user has not yet allowed on this iPhone first shows “Send recordings to [Provider]?”. This screen:
> - identifies the recipient: the provider by name, described as a third-party AI speech recognition service that Recly does not operate, with its endpoint;
> - explains what is sent and why: the full audio of each recording, with the language and speaker settings, for transcription; retention and training depend on the provider;
> - links to the provider's privacy policy and explains how to withdraw permission (Settings → Privacy);
> - asks for permission with “Don't allow” and “Allow & save”. If the user does not allow, the settings are not saved and nothing is sent.
>
> The app also checks this permission before every request to a provider, including for recordings received from Apple Watch, so no audio reaches a provider the user has not allowed. Permission can be withdrawn at any time in Settings → Privacy.
>
> Our privacy policy (https://recly.dev/policy/privacy-policy), section 3(2), identifies these providers as third-party AI services and describes what is sent, how, and why, and the in-app permission. For each provider it links the provider’s privacy policy and summarizes what its API terms say about retention and model training, including which providers use the audio only to provide transcription by default.
>
> To see the permission screen: Settings → Recording processing → External API → choose a provider → Save. No API key is needed to reach it. [Screenshot or video URL]

Put the same gist in App Review Information → Notes as well (third-party AI transmission only when External API is chosen, permission at saving, the path to check it).



## 2026-09-29 · Guideline 5.1.1(i)·5.1.2(i) reply to the repeated rejection

Finding: build 26 (0.1.0) got the same wording, with “The issues we previously identified still need your attention.” It does not say which requirement remains.

Diagnosis: build 26 contained the confirmation screen at saving (48e0fff) and the per-provider terms disclosure (c09bbb3). Of the four requirements, the one literally not met was the policy. 5.1.1(i) requires **confirming** that third parties provide “same or equal protection”, but policy §3(2) said that the providers do not all protect data at the same level. When build 26 was submitted, no Resolution Center reply was sent; only the Notes were written.

Response (2026-09-29 user decision):
- The iPhone app offers only the eight providers that do not use recordings for training (recly.md §15 “iPhone providers”). Policy §3(2) “Providers offered on iPhone” confirms equal protection.
- On every platform, requests to AssemblyAI go to its EU region.
- ElevenLabs can be allowed only after the user checks the box confirming that training is turned off.
- This time, send **both** the Resolution Center reply and the Notes.

Before sending:
- The AssemblyAI EU live-call check must pass. Check that Korean requests go to `universal-2` and that speaker diarization works.
- Film the ElevenLabs confirmation screen on a real iPhone (or the simulator) with the new build and fill in the `[ ]`.
- Check that the policy is published on recly.dev.

> Hello App Review Team,
>
> Thank you for the follow-up. We have revised both the app and the privacy policy in build [version/build].
>
> Recly sends a recording to a third-party AI service only when the user chooses an external speech-to-text provider in Settings → Recording processing → External API, using their own API key. New installations transcribe on device and send nothing to any AI service.
>
> **What is sent, to whom, and permission (5.1.2(i)).** Saving External API with a provider not yet allowed on this iPhone first shows “Send recordings to [Provider]?”. This screen:
> - names the recipient: the provider, described as a third-party AI speech recognition service that Recly does not operate, with its endpoint;
> - states what is sent and why: the full audio of each recording, with the language and speaker settings, for transcription;
> - links to the provider’s privacy information and explains how to withdraw permission (Settings → Privacy);
> - asks for permission with “Don’t allow” and “Allow & save”. If the user declines, the settings are not saved and nothing is sent.
>
> For ElevenLabs, the screen also explains that ElevenLabs uses recordings to improve its models unless this is turned off in the ElevenLabs account. It links to ElevenLabs’ instructions, and keeps “Allow & save” disabled until the user confirms it is turned off. The app checks the permission again before every request to a provider, including for recordings received from Apple Watch.
>
> **Privacy policy (5.1.1(i)).** Our privacy policy (https://recly.dev/policy/privacy-policy), section 3(2), states what the app sends, how and why, and names each third-party AI provider. In this build the iPhone app offers only providers that do not use recordings to train their models. The policy’s “Providers offered on iPhone” paragraph confirms that these providers provide the same or equal protection of user data as the policy, and gives the basis for each:
> - OpenAI, Groq, Microsoft Azure AI Speech and RTZR, by default;
> - Deepgram, through the opt-out the app sends with every request;
> - AssemblyAI, through its EU servers, which the app uses for every request;
> - NAVER CLOVA Speech, under terms that allow engine improvement only with the customer’s consent;
> - ElevenLabs, once training is turned off in the user’s account, as confirmed on the permission screen.
>
> Providers that do not meet this standard have been removed from the iPhone app.
>
> **To see the permission screen:** Settings → Recording processing → External API → choose a provider (for example ElevenLabs) → Save. No API key is needed. Screenshots: [URL]

App Review Information → Notes (gist):

> Third-party AI: Recly sends audio to a speech-to-text provider only when the user selects External API in Settings → Recording processing, using their own API key; the default is on-device transcription. Before saving a provider, the app shows “Send recordings to [Provider]?” (recipient, data sent, purpose, provider privacy link, withdrawal) with “Don’t allow” / “Allow & save”; ElevenLabs additionally requires confirming that model training is off in the ElevenLabs account. The iPhone app offers only providers that do not train on recordings; see privacy policy §3(2) “Providers offered on iPhone”. Path: Settings → Recording processing → External API → pick a provider → Save (no key needed).
