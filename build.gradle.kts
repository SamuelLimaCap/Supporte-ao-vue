import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.samuellimacap"
version = "1.3.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm(providers.gradleProperty("webstormVersion").orElse("2024.2.6").get())
        bundledPlugin("JavaScript")
        bundledPlugin("org.jetbrains.plugins.vue")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false
    pluginVerification {
        ides {
            create(IntelliJPlatformType.WebStorm, "2024.2.6")
            local("/snap/webstorm/current")
        }
    }
    pluginConfiguration {
        name = "Vue Helper"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
        }
    }
}
