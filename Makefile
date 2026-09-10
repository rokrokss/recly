# The build commands from README "Build · test", so nobody has to remember the flags that matter
# (ARCHS=arm64 for simulators, -collect-test-diagnostics never for xctest, JDK 21 for Gradle).
# Thin wrappers only: the logic stays in Gradle and apple/scripts.

export JAVA_HOME ?= /opt/homebrew/opt/openjdk@21
export ANDROID_HOME ?= /opt/homebrew/share/android-commandlinetools

# Windows Make uses cmd.exe for recipes, which needs the native wrapper instead of ./gradlew.
ifeq ($(OS),Windows_NT)
GRADLE = gradlew.bat
else
GRADLE = ./gradlew
endif
WORKSPACE = apple/Rec.xcworkspace
XCODEBUILD = xcodebuild -workspace $(WORKSPACE) -collect-test-diagnostics never
# Simulator names change with each Xcode; override on the command line: make ios IOS_SIM="iPhone 17".
IOS_SIM ?= iPhone 17 Pro
WATCH_SIM ?= Apple Watch Series 11 (46mm)

.PHONY: help test core android-test windows-test apk aab android-release-apk windows-run windows-msi helper-test ios-archive ios-upload mac-release \
        mac mac-test ios watch spec skills ios-release-test

help:
	@echo "make test           core · android · windows unit tests (JVM)"
	@echo "make core           build the XCFramework and stage it into apple/RecKit"
	@echo "make mac            build Recly Mac"
	@echo "make mac-test       RecKit tests on macOS"
	@echo "make ios            build Recly for the iOS simulator   (IOS_SIM=\"$(IOS_SIM)\")"
	@echo "make watch          build Recly Watch for the watch simulator (WATCH_SIM=\"$(WATCH_SIM)\")"
	@echo "make ios-archive    App Store archive of Recly (iPhone + watch); needs the team in Local.xcconfig"
	@echo "make ios-upload     the same, uploaded to TestFlight"
	@echo "make ios-release-test  validate Apple packaging and OAuth checks using local fixtures"
	@echo "make mac-release    Developer ID + notarized DMG of Recly Mac (NOTARY_PROFILE=\"$(NOTARY_PROFILE)\")"
	@echo "make apk            phone debug APK"
	@echo "make aab            phone + watch release bundles for Play (needs the upload key)"
	@echo "make android-release-apk  phone + watch release APKs (needs the upload key)"
	@echo "make android-test   android unit tests only"
	@echo "make android-ui-test  focused Android interaction tests (connected test device)"
	@echo "make ios-ui-test    focused iPhone interaction tests (IOS_SIM= override)"
	@echo "make windows-test   windows shell unit tests only"
	@echo "make windows-run    run the Windows shell on this host"
	@echo "make windows-msi    Windows MSI (Windows hosts only)"
	@echo "make helper-test    Rust capture helper tests"
	@echo "make spec           validate spec/examples against the JSON Schemas"
	@echo "make skills         zip the two agent skills for the Claude app (build/skills/)"

# ---- Core · Android · Windows (JVM)

test:
	$(GRADLE) :core:jvmTest :android:app:testDebugUnitTest :android:wear:testDebugUnitTest \
	          :android:recording:testDebugUnitTest :android:datalayer:testDebugUnitTest :windows:app:test

android-test:
	$(GRADLE) :android:app:testDebugUnitTest :android:wear:testDebugUnitTest \
	          :android:recording:testDebugUnitTest :android:datalayer:testDebugUnitTest

apk:
	$(GRADLE) :android:app:assembleDebug

aab:
	$(GRADLE) :android:app:bundleRelease :android:wear:bundleRelease

android-release-apk:
	$(GRADLE) :android:app:assembleRelease :android:wear:assembleRelease

windows-test:
	$(GRADLE) :windows:app:test

windows-run:
	$(GRADLE) :windows:app:run

windows-msi:
	$(GRADLE) :windows:app:packageMsi

helper-test:
	cd windows/capture-helper && cargo test

# ---- Apple (macOS host)

core:
	./apple/scripts/build-core.sh

mac:
	$(XCODEBUILD) -scheme 'Recly Mac' -destination 'platform=macOS' build

mac-test:
	$(XCODEBUILD) -scheme RecKit -destination 'platform=macOS' test

ios-archive:
	./apple/scripts/release-ios.sh

ios-release-test:
	bash -n apple/scripts/release-ios.sh
	bash -n apple/scripts/release-mac.sh
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s apple/scripts/tests -v

ios-upload:
	UPLOAD=1 ./apple/scripts/release-ios.sh

# The notarytool keychain profile: xcrun notarytool store-credentials <name> --apple-id … --team-id …
NOTARY_PROFILE ?= recly

mac-release:
	NOTARIZE=1 NOTARY_PROFILE="$(NOTARY_PROFILE)" ./apple/scripts/release-mac.sh

ios:
	./apple/scripts/build-sim.sh Recly "iOS Simulator" "$(IOS_SIM)" CODE_SIGNING_ALLOWED=NO build

watch:
	./apple/scripts/build-sim.sh "Recly Watch" "watchOS Simulator" "$(WATCH_SIM)" CODE_SIGNING_ALLOWED=NO build

# ---- Spec

spec:
	cd spec && npm ci && npm run validate

# ---- Skills

# The Claude app takes a skill as a ZIP whose root is the skill folder. `windows-release.yml`
# attaches these two to the GitHub release for the tag, so a phone can download them.
skills:
	mkdir -p build/skills
	cd skills && for skill in recly-notes recly-notion; do \
	  rm -f ../build/skills/$$skill.zip && zip -qr ../build/skills/$$skill.zip $$skill -x '*.DS_Store'; \
	done
	ls -l build/skills

# Focused interaction checks on isolated test devices.
.PHONY: android-ui-test ios-ui-test
ANDROID_TEST_CLASS ?= app.recly.android.ui.MobileUxTest,app.recly.android.ui.UiStandardsTest,app.recly.android.ui.TranscriptReaderTest,app.recly.android.ui.HighContrastThemeTest
android-ui-test:
	$(GRADLE) :android:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$(ANDROID_TEST_CLASS)

IOS_TESTS ?= ReclyUITests/MobileUxTests
ios-ui-test:
	./apple/scripts/build-sim.sh Recly "iOS Simulator" "$(IOS_SIM)" CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- test -only-testing:$(IOS_TESTS)
