# Public App Store copy for resubmission

The English copy to enter in App Store Connect is in [app-store-metadata.en.txt](app-store-metadata.en.txt). Changing this file or pushing it to Git does not change the copy in App Store Connect automatically.

## Where to enter it

| App Store Connect location | Field | Section in the copy file |
|---|---|---|
| Recly → App Information → English (U.S.) | Name | `NAME` |
| Same screen | Subtitle | `SUBTITLE` |
| Recly → iOS App → 0.1.0 → English (U.S.) | Promotional Text | `PROMOTIONAL TEXT` |
| Same screen | Description | `DESCRIPTION` |
| Same screen | Keywords | `KEYWORDS` |

Enter only the body of each section, without its heading. If the name and subtitle already match the copy, leave them. 0.1.0 was the first release, so it had no `What's New`. Versions after the 2026-10-01 release are updates, so enter `What's New` as well.

If English is the only registered language, China also shows the English metadata. Changing only the Chinese localization does not change every piece of copy shown in the China storefront. Check the primary language and every localization that can be shown in China together.

## Screenshots and app previews

In the images and videos for every device size and localization, check for any remaining ChatGPT/OpenAI/GPT names, logos, model names or screens of that integration. If such an image exists, replace it with screens from the build actually being submitted, such as the recording, list and Drive screens. New screens must show the app's actual behavior as it is.

The first paragraph of the public description states that local recording, saving and playback work without connecting Google. It also states that the Google connection is for Drive access and that transcription currently requires the connection (the original is uploaded and then transcribed). Recording, saving to the user's Drive and optional transcription (on the device or with a provider the user chooses) are the center of the description. The app name is `Recly: Record for Your AI`, expressing the direction of using recordings and transcription results with the AI the user chooses. The `AI` in the name does not refer to any specific provider, and the public description states the setup, external accounts and pricing conditions of the optional features.

## Review Notes and privacy policy

The 2026-09-14 review message required disabling that feature in mainland China and removing ChatGPT/OpenAI/GPT references from the public metadata. Do not respond by only changing names while still offering the feature. The feature restriction in build 13 is recorded in [design §15](recly.md#china-mainland-app-store).

The regional notice in the public description is `Transcription provider availability varies by region.`. The private review Notes name OpenAI to explain what was blocked in China. Use the regional behavior item of the [review preparation document](app-review.md), and reflect the results of verification with a real StoreKit account. Also keep the privacy policy's description of the actual processing providers and regional restrictions accurate.

Do not include review accounts or keys in the public copy or the repository. Do not mark behavior or videos not checked on a device as verified. Distinguish a change to the public copy only from a change to the app code. This resubmission includes app UI and behavior changes, so do not select the earlier review build `0.1.0 (13)` as it is; upload and select a new archive that includes the changes. Check the version and build number in App Store Connect.

## Lengths and scope of checks

The current English copy is name 25/30 characters, subtitle 28/30 characters, promotional text 118/170 characters, description 1088/4,000 characters and keywords 81/100 bytes. The name uses the generic `AI` expression, and the public copy contains no ChatGPT/OpenAI/GPT references.

The saved state in the actual store, the uploaded screenshots and the behavior with a mainland China StoreKit account must be checked before resubmission.

## Sources

- [Apple app information fields](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/): name, subtitle and their length limits.
- [Apple version information fields](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/): public copy and screenshots, private review Notes, no What's New for the first version.
- [Apple localization display rules](https://developer.apple.com/help/app-store-connect/manage-app-information/localize-app-information/): display based on the primary language and the user's language.
