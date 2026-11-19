import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

repositories {
  mavenCentral()
}

plugins {
  alias(libs.plugins.kotlinGradlePlugin) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.versionsGradlePlugin)
  alias(libs.plugins.versionCatalogUpdateGradlePlugin)
  alias(libs.plugins.kotlinBinaryCompatibilityPlugin)
}

subprojects {
  apply(plugin = "org.jetbrains.dokka")

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }

  tasks.withType<Test> {
    useJUnitPlatform()
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<DokkaExtension> {
      moduleName.set(project.name)
      moduleVersion.set(providers.gradleProperty("VERSION_NAME"))
      dokkaSourceSets.matching { it.name == "main" }.configureEach {
        jdkVersion.set(11)
        includes.from("module.md")
        sourceLink {
          localDirectory.set(file("src/main/kotlin"))
          remoteUrl("https://github.com/block/ln-invoice/tree/main/${project.name}/src/main/kotlin")
          remoteLineSuffix.set("#L")
        }
      }
    }
  }
}

dependencies {
  dokka(project(":lib"))
}

dokka {
  moduleName.set("ln-invoice")
  moduleVersion.set(providers.gradleProperty("VERSION_NAME"))
  dokkaPublications.html {
    outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
  }
}

tasks.register("publishToMavenCentral") {
  group = "publishing"
  dependsOn(
    ":lib:publishToMavenCentral",
  )
}
