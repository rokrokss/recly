# `:android:app` — Google OAuth setup

The app **builds and installs** with a placeholder client ID too. Only sign-in fails
(`GetCredentialException: no credentials available`). To see a real sign-in, do the following once.

## 1. GCP console (docs/recly.md §6, ADR-009)

1. Enable the **Drive API** on the project.
2. OAuth consent screen: **External / Production**. The scope is `drive.file`, **that one only**
   (adding another scope makes it sensitive and a verification procedure comes with it).
3. Credentials → create **two** OAuth client IDs.

### (a) The Android client

Credential Manager and `AuthorizationClient` use it to verify the caller. There is no value to put
into the app.

| Item | Value |
|---|---|
| Type | Android |
| Package name | `app.recly` |
| SHA-1 certificate fingerprint | the output of the command below |

The SHA-1 of the debug key:

```sh
keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore \
        -storepass android -keypass android | grep SHA1
```

Make one more client the same way for the release key (with Play app signing, the SHA-1 under Play
Console → Setup → App signing). The phone and the watch have to share the package name and signing
key (docs/11), so there is no separate client for the watch.

### (b) The Web client

This is the value that goes into `GetGoogleIdOption.setServerClientId()`. Create it with type **Web
application** and copy the client ID (`...apps.googleusercontent.com`). No redirect URI is needed.

> `google-services.json` is **not** downloaded. Firebase is not used, and Credential Manager and
> `AuthorizationClient` do not read that file.

## 2. Injecting it into the app

The web client ID is not a secret, but it differs per developer, so it is not committed.
`android/app/build.gradle.kts` reads it in the following order and generates
`R.string.google_server_client_id`.

1. The repo root's `local.properties` (gitignored):

   ```properties
   google.serverClientId=1234567890-xxxxxxxx.apps.googleusercontent.com
   ```

2. The environment variable `REC_GOOGLE_SERVER_CLIENT_ID` (for CI):

   ```sh
   REC_GOOGLE_SERVER_CLIENT_ID=1234567890-xxxxxxxx.apps.googleusercontent.com ./gradlew :android:app:assembleDebug
   ```

3. With neither, `REPLACE_ME.apps.googleusercontent.com`.

After changing the value, run `:android:app:assembleDebug` again so the resource is regenerated.

## 3. Checking

App → **Sign in with Google** → pick an account → consent to the Drive permission. Record a short
clip and stop it: the upload job reaching `DONE` in the List tab is the proof the app can write to
Drive (the auth half of S3).
