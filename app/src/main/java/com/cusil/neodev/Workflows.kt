package com.cusil.neodev

object Workflows {

    private fun header(name: String, jdk: Int, useWrapper: Boolean): String {
        val gradleSetup = if (useWrapper) "" else """
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9
"""
        return """name: $name

on:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: $jdk
$gradleSetup"""
    }

    private fun gradleCmd(useWrapper: Boolean, task: String): String =
        if (useWrapper) "chmod +x gradlew && ./gradlew $task --no-daemon --stacktrace"
        else "gradle $task --no-daemon --stacktrace"

    /** Builds a debug APK and uploads it as an artifact. */
    fun androidApk(useWrapper: Boolean = false, jdk: Int = 17): String =
        header("Build Android APK", jdk, useWrapper) + """
      - name: Keep the same signing key between builds
        uses: actions/cache@v4
        with:
          path: ~/.android/debug.keystore
          key: debug-keystore-${'$'}{{ github.repository }}

      - name: Build debug APK
        run: ${gradleCmd(useWrapper, "assembleDebug")}

      - uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: "**/build/outputs/apk/debug/*.apk"
"""

    /** Builds a JAR (Paper plugin, Fabric mod, library) and uploads it as an artifact. */
    fun gradleJar(useWrapper: Boolean = false, jdk: Int = 21): String =
        header("Build JAR", jdk, useWrapper) + """
      - name: Build JAR
        run: ${gradleCmd(useWrapper, "build")}

      - uses: actions/upload-artifact@v4
        with:
          name: jar-build
          path: "**/build/libs/*.jar"
"""
}
