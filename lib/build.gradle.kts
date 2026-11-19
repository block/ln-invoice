plugins {
  id("java-library")
  id("org.jetbrains.kotlin.jvm")
  alias(libs.plugins.mavenPublish)
}

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(11)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xjdk-release=11")
  }
}

mavenPublishing {
  configure(com.vanniktech.maven.publish.KotlinJvm(
    javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
  ))
}

dependencies {
  api(libs.arrowCore)
  implementation(libs.bouncyCastle)
  implementation(libs.bitcoinj)
  api(libs.okio)
  implementation(libs.quiver)
  implementation(libs.slf4jApi)

  testImplementation(libs.guavaJre)
  testImplementation(libs.junitApi)
  testImplementation(libs.kotestAssertions)
  testImplementation(libs.kotestExtAssertionsArrow)
  testImplementation(libs.kotestExtPropertyArrow)
  testImplementation(libs.kotestFrameworkApi)
  testImplementation(libs.kotestJunitRunnerJvm)
  testImplementation(libs.kotestProperty)

  testRuntimeOnly(libs.junitEngine)
  testRuntimeOnly(libs.junitPlatformLauncher)
  testRuntimeOnly(libs.slf4jSimple)
}
