import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.protobuf.javalite)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}

// Manual end-to-end check against a real TV from a desktop:
//   ./gradlew :atvremote:cli --args="<tv-ip> pair"
tasks.register<JavaExec>("cli") {
    group = "verification"
    description = "Pairs with and sends commands to a real Android TV from the desktop."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.sudomastery.atv.CliKt")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
