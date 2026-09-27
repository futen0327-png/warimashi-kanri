import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android に依存しない発話解釈・割増検証・顧客照合ロジック（JVM 単体テスト可能）
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}
