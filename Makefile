# The build commands from README "Build · test", so nobody has to remember the flags that matter
# (ARCHS=arm64 for simulators, -collect-test-diagnostics never for xctest, JDK 21 for Gradle).
# Thin wrappers only: the logic stays in Gradle and apple/scripts.

export JAVA_HOME ?= /opt/homebrew/opt/openjdk@21
export ANDROID_HOME ?= /opt/homebrew/share/android-commandlinetools

GRADLE = ./gradlew
WORKSPACE = apple/Rec.xcworkspace
XCODEBUILD = xcodebuild -workspace $(WORKSPACE) -collect-test-diagnostics never
# Simulator names change with each Xcode; override on the command line: make ios IOS_SIM="iPhone 17".
IOS_SIM ?= iPhone 17 Pro
WATCH_SIM ?= Apple Watch Series 11 (46mm)

.PHONY: help test core android-test windows-test apk windows-run windows-msi helper-test \
        mac mac-test ios watch spec

help:
	@echo "make test           core · android · windows unit tests (JVM)"
	@echo "make core           build the XCFramework and stage it into apple/RecKit"
	@echo "make mac            build Recly Mac"
	@echo "make mac-test       RecKit tests on macOS"
	@echo "make ios            build Recly for the iOS simulator   (IOS_SIM=\"$(IOS_SIM)\")"
	@echo "make watch          build Recly Watch for the watch simulator (WATCH_SIM=\"$(WATCH_SIM)\")"
	@echo "make apk            phone debug APK"
	@echo "make android-test   android unit tests only"
	@echo "make windows-test   windows shell unit tests only"
	@echo "make windows-run    run the Windows shell on this host"
	@echo "make windows-msi    Windows MSI (Windows hosts only)"
	@echo "make helper-test    Rust capture helper tests"
	@echo "make spec           validate spec/examples against the JSON Schemas"

# ---- Core · Android · Windows (JVM)

test:
	$(GRADLE) :core:jvmTest :android:app:testDebugUnitTest :android:wear:testDebugUnitTest \
	          :android:recording:testDebugUnitTest :android:datalayer:testDebugUnitTest :windows:app:test

android-test:
	$(GRADLE) :android:app:testDebugUnitTest :android:wear:testDebugUnitTest \
	          :android:recording:testDebugUnitTest :android:datalayer:testDebugUnitTest

apk:
	$(GRADLE) :android:app:assembleDebug

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

ios:
	./apple/scripts/build-sim.sh Recly "iOS Simulator" "$(IOS_SIM)" CODE_SIGNING_ALLOWED=NO build

watch:
	./apple/scripts/build-sim.sh "Recly Watch" "watchOS Simulator" "$(WATCH_SIM)" CODE_SIGNING_ALLOWED=NO build

# ---- Spec

spec:
	cd spec && npm ci && npm run validate
