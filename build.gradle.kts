// Top-level Gradle build file for project 'video'.
// Android Gradle 插件通过 buildscript classpath 提供给各模块使用。

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.0")
    }
}
