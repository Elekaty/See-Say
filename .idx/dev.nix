# Project IDX / Firebase Studio environment for See and Say V1.0.
# JDK 17, Android SDK 34, emulator, and an API 34 Google APIs image.
{ pkgs, ... }:

let
  licensedPkgs = import pkgs.path {
    inherit (pkgs) system;
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };

  androidComposition = licensedPkgs.androidenv.composeAndroidPackages {
    cmdLineToolsVersion = "13.0";
    platformToolsVersion = "35.0.2";
    buildToolsVersions = [ "34.0.0" ];
    platformVersions = [ "34" ];
    includeEmulator = true;
    emulatorVersion = "35.1.4";
    includeSystemImages = true;
    systemImageTypes = [ "google_apis" ];
    abiVersions = [ "x86_64" ];
    includeNDK = false;
    includeSources = false;
    useGoogleAPIs = false;
    useGoogleTVAddOns = false;
    cmakeVersions = [ ];
  };

  androidSdk = androidComposition.androidsdk;
  androidSdkRoot = "${androidSdk}/libexec/android-sdk";
in
{
  channel = "stable-24.11";

  packages = [
    pkgs.jdk17
    pkgs.unzip
    androidSdk
  ];

  env = {
    JAVA_HOME = "${pkgs.jdk17}";
    ANDROID_HOME = androidSdkRoot;
    ANDROID_SDK_ROOT = androidSdkRoot;
    ANDROID_AVD_HOME = "/home/user/.android/avd";
    PATH = [
      "${androidSdk}/bin"
      "${androidSdkRoot}/platform-tools"
      "${androidSdkRoot}/emulator"
    ];
  };

  idx = {
    extensions = [
      "fwcd.kotlin"
      "redhat.java"
      "vscjava.vscode-gradle"
    ];

    workspace = {
      onCreate = {
        create-avd = ''
          set -eu
          export JAVA_HOME="${pkgs.jdk17}"
          export ANDROID_HOME="${androidSdkRoot}"
          export ANDROID_SDK_ROOT="${androidSdkRoot}"
          export ANDROID_AVD_HOME="/home/user/.android/avd"
          mkdir -p "$ANDROID_AVD_HOME"
          if ! echo no | "${androidSdk}/bin/avdmanager" create avd \
            --force \
            --name SeeAndSayChronologer \
            --package "system-images;android-34;google_apis;x86_64" \
            --device pixel_6; then
            echo no | "${androidSdk}/bin/avdmanager" create avd \
              --force \
              --name SeeAndSayChronologer \
              --package "system-images;android-34;google_apis;x86_64"
          fi
        '';
      };

      onStart = {
        start-emulator = ''
          export JAVA_HOME="${pkgs.jdk17}"
          export ANDROID_HOME="${androidSdkRoot}"
          export ANDROID_SDK_ROOT="${androidSdkRoot}"
          export ANDROID_AVD_HOME="/home/user/.android/avd"
          if ! "${androidSdk}/bin/adb" devices | grep -q 'emulator-'; then
            "${androidSdk}/bin/emulator" \
              -avd SeeAndSayChronologer \
              -port 5554 \
              -no-snapshot \
              -no-boot-anim \
              -gpu swiftshader_indirect \
              >/tmp/seeandsay-emulator.log 2>&1 &
          fi
        '';
      };
    };

    previews = {
      enable = true;
      previews = {
        android = {
          manager = "android";
          activity = "com.seeandsay.chronologer.MainActivity";
          command = [ "./gradlew" "installDebug" ];
        };
      };
    };
  };
}
