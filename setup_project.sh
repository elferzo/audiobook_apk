#!/bin/bash
# Разворачивает плоский репозиторий в структуру Android-проекта (запускается в GitHub Actions)
set -e

SRC=app/src/main/java/com/kvu/offline
mkdir -p "$SRC"
mkdir -p app/src/main/res/drawable
mkdir -p app/src/main/assets

cp MainActivity.kt "$SRC/"
cp PlayerService.kt "$SRC/"
cp DownloadService.kt "$SRC/"
cp Store.kt "$SRC/"

cp AndroidManifest.xml app/src/main/
cp ic_launcher.xml app/src/main/res/drawable/
cp extract.js app/src/main/assets/
cp debug.keystore app/

cp root_build_gradle.txt build.gradle
cp app_build_gradle.txt app/build.gradle
cp settings_gradle.txt settings.gradle

echo "Project structure ready"
