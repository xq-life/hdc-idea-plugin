import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    // Maven Central is unreachable from some networks (it stalls instead of failing fast), and the
    // signPlugin task resolves the marketplace-zip-signer CLI from it. JetBrains' cache-redirector
    // mirrors Maven Central, so it is listed first and mavenCentral() stays as the fallback.
    maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
    mavenCentral()
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName"))
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks {
    wrapper {
        gradleVersion = "8.7"
    }

    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set(providers.gradleProperty("pluginUntilBuild"))
        changeNotes.set(
            """
            <ul>
              <li><b>1.1.0</b></li>
              <li>Device rows show an explicit connection state (Connected / Connecting / Failed / Not connected) with an animated indicator; a failed connection keeps its reason on hover</li>
              <li>Connect and Disconnect are visually distinct: Connect is the primary action, Disconnect carries a tinted chip instead of the success colour</li>
              <li>Auto-refresh switch shows the active interval and its on/off state, with quick interval choices (Off / 5s / 10s / 30s / 60s); polling pauses while the tool window is hidden, and a last-updated timestamp is shown</li>
              <li>Local-network discovery derives the real network range from each interface's netmask instead of assuming /24, and probes several ports per host &mdash; so devices reached over a random Wi-Fi debugging port (for example 38343) are found</li>
              <li>Additional scan ports are configurable under Settings &gt; Tools &gt; HDC Wi-Fi; ports of previously connected devices are added automatically</li>
              <li>Scanning logs the ranges, ports and target count it covered, and can be cancelled</li>
              <li>Simplified Chinese user interface for the tool window and the settings page; action verbs such as Connect, Disconnect and Scan for devices stay in English</li>
              <li>Command output wraps to the panel width</li>
              <li>New plugin and tool window icon</li>
              <li>Optional serial auto-connect for saved and newly discovered devices (off by default)</li>
              <li>The device list no longer rebuilds when nothing changed, so scroll position is preserved</li>
              <li><b>1.0.0</b></li>
              <li>Initial release: HDC Wi-Fi device management for IntelliJ-based IDEs</li>
              <li>Wi-Fi connect/disconnect (hdc tconn / hdc tconn -remove) with a visual device panel</li>
              <li>Shell, file send/recv and hilog viewers per device</li>
              <li>Configurable HDC binary path with auto-detection</li>
            </ul>
            """.trimIndent()
        )
    }

    buildSearchableOptions {
        enabled = false
    }

    test {
        useJUnit()
    }

    // Marketplace publishing: set INTELLIJ_PUBLISH_TOKEN and run ./gradlew publishPlugin.
    // The first version of a plugin has to be uploaded through plugins.jetbrains.com by hand;
    // publishPlugin only works for updates after that.
    publishPlugin {
        token.set(providers.environmentVariable("INTELLIJ_PUBLISH_TOKEN").orElse(""))
    }

    // Marketplace signing. The key pair is kept out of the repository in `signing/`; the password
    // comes from PRIVATE_KEY_PASSWORD when set (CI) and otherwise from the local password file.
    // Without both files signPlugin is skipped and the uploaded zip stays unsigned, which makes
    // every IDE show an unsigned-plugin warning on install.
    val signingDir = layout.projectDirectory.dir("signing")
    fun localSigningPassword(): String =
        signingDir.file("PRIVATE_KEY_PASSWORD.txt").asFile
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()

    signPlugin {
        certificateChainFile.set(signingDir.file("chain.crt"))
        privateKeyFile.set(signingDir.file("private.pem"))
        password.set(
            providers.environmentVariable("PRIVATE_KEY_PASSWORD")
                .orElse(providers.provider { localSigningPassword() })
        )
    }
}
