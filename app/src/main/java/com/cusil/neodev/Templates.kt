package com.cusil.neodev

object Templates {

    val kinds: List<Pair<String, String>> = listOf(
        "android" to "Android app (Kotlin + Compose), with APK build workflow",
        "paper" to "Paper / Spigot plugin (Java + Gradle), with JAR build workflow",
        "skript" to "Skript script for a Minecraft server",
        "python" to "Python script",
        "empty" to "Empty project"
    )

    private fun pkgOf(name: String): String {
        val core = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }
        val safe = if (core.first().isLetter()) core else "app$core"
        return "com.example.$safe"
    }

    fun files(kind: String, name: String): Map<String, String> = when (kind) {
        "android" -> android(name)
        "paper" -> paper(name)
        "skript" -> skript(name)
        "python" -> python(name)
        else -> mapOf("README.md" to "# $name\n\nCreated with NeoDev.\n")
    }

    private fun android(name: String): Map<String, String> {
        val pkg = pkgOf(name)
        val path = pkg.replace('.', '/')
        val files = LinkedHashMap<String, String>()
        files["README.md"] = "# $name\n\nAndroid app created with NeoDev.\nOpen the Build screen, run the workflow, then download the APK.\n"
        files[".gitignore"] = ".gradle/\nbuild/\nlocal.properties\n*.apk\n"
        files["settings.gradle.kts"] = """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "$name"
include(":app")
""".trimStart()
        files["build.gradle.kts"] = """
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
""".trimStart()
        files["gradle.properties"] = "org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\nkotlin.code.style=official\n"
        files["app/build.gradle.kts"] = """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "$pkg"
    compileSdk = 34

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
""".trimStart()
        files["app/src/main/AndroidManifest.xml"] = """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="$name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimStart()
        files["app/src/main/java/$path/MainActivity.kt"] = """
package $pkg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var count by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("$name", style = MaterialTheme.typography.headlineMedium)
                    Button(onClick = { count++ }) {
                        Text("Clicked " + count + " times")
                    }
                }
            }
        }
    }
}
""".trimStart()
        files[".github/workflows/neodev-apk.yml"] = Workflows.androidApk(useWrapper = false, jdk = 17)
        return files
    }

    private fun paper(name: String): Map<String, String> {
        val pkg = pkgOf(name)
        val path = pkg.replace('.', '/')
        val files = LinkedHashMap<String, String>()
        files["README.md"] = "# $name\n\nPaper plugin created with NeoDev.\nRun the build workflow, then download the JAR.\n"
        files[".gitignore"] = ".gradle/\nbuild/\n*.jar\n"
        files["settings.gradle.kts"] = "rootProject.name = \"$name\"\n"
        files["build.gradle.kts"] = """
plugins {
    java
}

group = "$pkg"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
""".trimStart()
        files["src/main/resources/plugin.yml"] = "name: $name\nversion: 1.0.0\nmain: $pkg.Main\napi-version: '1.21'\n"
        files["src/main/java/$path/Main.java"] = """
package $pkg;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("$name enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("$name disabled");
    }
}
""".trimStart()
        files[".github/workflows/neodev-jar.yml"] = Workflows.gradleJar(useWrapper = false, jdk = 21)
        return files
    }

    private fun skript(name: String): Map<String, String> = mapOf(
        "README.md" to "# $name\n\nSkript created with NeoDev. Put the .sk file in plugins/Skript/scripts/.\n",
        "scripts/$name.sk" to "command /hello:\n    trigger:\n        send \"Hello from $name!\" to player\n"
    )

    private fun python(name: String): Map<String, String> = mapOf(
        "README.md" to "# $name\n\nPython project created with NeoDev.\n",
        "main.py" to "def main():\n    print(\"Hello from $name\")\n\n\nif __name__ == \"__main__\":\n    main()\n"
    )
}
