plugins {
  id("java-library")
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish") version "0.33.0"
}

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
  }
  withSourcesJar()
}

mavenPublishing {
  configure(com.vanniktech.maven.publish.KotlinJvm())
}

dependencies {
  implementation(libs.arrowCore)
  implementation(libs.bouncyCastle)
  implementation(libs.bitcoinj)
  implementation(libs.okio)
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
  testRuntimeOnly(libs.slf4jSimple)
}
