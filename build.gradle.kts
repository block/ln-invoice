import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.support.kotlinCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinGradlePlugin) apply false
  alias(libs.plugins.kotlinBinaryCompatibilityPlugin) apply false
  alias(libs.plugins.mavenPublishGradlePlugin) apply false
  alias(libs.plugins.versionsGradlePlugin)
  alias(libs.plugins.versionCatalogUpdateGradlePlugin)
  alias(libs.plugins.dokka)
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

buildscript {
  repositories {
    mavenCentral()
  }
}

subprojects {
  buildscript {
    repositories {
      mavenCentral()
      gradlePluginPortal()
    }
  }

  repositories {
    mavenCentral()
  }

  apply(plugin = "java")
  apply(plugin = "kotlin")
  apply(plugin = rootProject.project.libs.plugins.kotlinBinaryCompatibilityPlugin.get().pluginId)
  apply(plugin = rootProject.project.libs.plugins.mavenPublishGradlePlugin.get().pluginId)

  configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
  }

  plugins.withId("com.vanniktech.maven.publish.base") {
    val publishingExtension = extensions.getByType(PublishingExtension::class.java)
    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
      pomFromGradleProperties()
      publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.DEFAULT, true)
      signAllPublications()
    }

    publishingExtension.publications.create<MavenPublication>("maven") {
      from(components["java"])
    }
  }

  apply(plugin = "version-catalog")

  // Only apply if the project has the kotlin plugin added:
  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      allWarningsAsErrors.set(true)
    }
  }

  plugins.withType<KotlinPluginWrapper> {
    tasks.withType<GenerateModuleMetadata> {
      suppressedValidationErrors.add("enforced-platform")
    }

    dependencies {
      add("testImplementation", project.rootProject.libs.junitApi)
      add("testRuntimeOnly", project.rootProject.libs.junitEngine)
    }
  }

  tasks.withType<Test> {
    dependsOn("apiCheck")
    useJUnitPlatform()
    testLogging {
      events("started", "passed", "skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
      showStandardStreams = false
    }
  }

  apply(plugin = "com.github.ben-manes.versions")

  tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    revision = "release"
    resolutionStrategy {
      componentSelection {
        all {
          if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
            reject("Release candidate")
          }
        }
      }
    }
  }
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}

// this needs to be defined here for the versionCatalogUpdate
tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
  revision = "release"
  resolutionStrategy {
    componentSelection {
      all {
        if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
          reject("Release candidate")
        }
      }
    }
  }
}

versionCatalogUpdate {
  /**
   * Use @pin and @keep in gradle/lib.versions.toml instead of defining here
   */
  sortByKey.set(true)
}
