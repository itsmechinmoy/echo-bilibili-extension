plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    implementation(project(":ext"))
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.okhttp)
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:2.2.10")
    }
    // Kotlin stdlib and kotlinx libraries must NOT be bundled in the extension DEX —
    // they must be resolved from the Echo host app's classloader at runtime.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    exclude(group = "com.squareup.okhttp3")
    exclude(group = "com.squareup.okio")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xlambdas=class"
        )
    }
}

val extType: String by project
val extId: String by project
val extClass: String by project

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val verCode = gitCount
val verName = "v$gitHash"

val outputDir = file("${layout.buildDirectory.asFile.get()}/generated/proguard")
val generatedProguard = file("${outputDir}/generated-rules.pro")

tasks.register("generateProguardRules") {
    doLast {
        outputDir.mkdirs()
        generatedProguard.writeText(
            """
                -dontoptimize
                -dontobfuscate
                -keep,allowoptimization class dev.brahmkshatriya.echo.extension.$extClass
                -keep class dev.brahmkshatriya.echo.extension.** { *; }
                -keep class kotlin.jvm.functions.** { *; }
                -keep interface kotlin.jvm.functions.** { *; }
                -keep class * implements kotlin.jvm.functions.Function* { *; }
                -keepclassmembers class * implements kotlin.jvm.functions.Function* {
                    public <methods>;
                }
                -keepclassmembers class * {
                    @kotlinx.serialization.Serializable <fields>;
                    @kotlinx.serialization.Serializable <init>(...);
                }
                -keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
                -dontwarn **
                """.trimMargin()
        )
    }
}

tasks.named("preBuild") {
    dependsOn("generateProguardRules")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.brahmkshatriya.echo.extension.$extId"
        minSdk = 24
        targetSdk = 36

        manifestPlaceholders.apply {
            put("type", "dev.brahmkshatriya.echo.${extType}")
            put("id", extId)
            put("class_path", "dev.brahmkshatriya.echo.extension.${extClass}")
            put("version", verName)
            put("version_code", verCode.toString())
            put("icon_url", extIconUrl ?: "")
            put("app_name", "Echo : $extName Extension")
            put("name", extName)
            put("description", extDescription ?: "")
            put("author", extAuthor)
            put("author_url", extAuthorUrl ?: "")
            put("repo_url", extRepoUrl ?: "")
            put("update_url", extUpdateUrl ?: "")
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                generatedProguard.absolutePath
            )
        }
    }
}

fun execute(vararg command: String): String = runCatching {
    providers.exec {
        commandLine(*command)
    }.standardOutput.asText.get().trim()
}.getOrElse { "1" }
