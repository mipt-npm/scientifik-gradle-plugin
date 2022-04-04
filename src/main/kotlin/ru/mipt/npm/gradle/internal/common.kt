package ru.mipt.npm.gradle.internal

import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.maven
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder

internal fun LanguageSettingsBuilder.applySettings(
    kotlinVersion: KotlinVersion
) {
    val versionString = "${kotlinVersion.major}.${kotlinVersion.minor}"
    languageVersion = versionString
    apiVersion = versionString
    progressiveMode = true

    optIn("kotlin.RequiresOptIn")
    optIn("kotlin.ExperimentalUnsignedTypes")
    optIn("kotlin.ExperimentalStdlibApi")
    optIn("kotlin.time.ExperimentalTime")
    optIn("kotlin.contracts.ExperimentalContracts")
    optIn("kotlin.js.ExperimentalJsExport")

    enableLanguageFeature("ContextReceivers")
}

internal fun RepositoryHandler.applyRepos() {
    mavenCentral()
    maven("https://repo.kotlin.link")
}

internal fun Copy.fromJsDependencies(configurationName: String) = project.run {
    val configuration = configurations[configurationName]
        ?: error("Configuration with name $configurationName could not be resolved.")
    val projectDeps = configuration.allDependencies.filterIsInstance<ProjectDependency>().map {
        it.dependencyProject
    }
    projectDeps.forEach { dep ->
        dep.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            dep.tasks.findByName("jsProcessResources")?.let { task ->
                dependsOn(task)
                from(task)
            }
        }
        dep.pluginManager.withPlugin("org.jetbrains.kotlin.js") {
            dep.tasks.findByName("processResources")?.let { task ->
                dependsOn(task)
                from(task)
            }
        }
    }
}

internal fun KotlinMultiplatformExtension.bundleJsBinaryAsResource(bundleName: String = "js/bundle.js") {
    js {
        binaries.executable()
        browser {
            webpackTask {
                outputFileName = bundleName
            }
        }
    }

    jvm {
        val processResourcesTaskName =
            compilations[org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.MAIN_COMPILATION_NAME]
                .processResourcesTaskName

        val jsBrowserDistribution = project.tasks.getByName("jsBrowserDistribution")

        project.tasks.getByName<ProcessResources>(processResourcesTaskName) {
            duplicatesStrategy = DuplicatesStrategy.WARN
            dependsOn(jsBrowserDistribution)
            from(jsBrowserDistribution)
        }
    }

}

//
//internal fun Copy.copyJVMResources(configuration: Configuration): Unit = project.afterEvaluate {
//    val projectDeps = configuration.allDependencies
//        .filterIsInstance<ProjectDependency>()
//        .map { it.dependencyProject }
//
//    projectDeps.forEach { dep ->
//        dep.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
//            dep.tasks.findByName("jvmProcessResources")?.let { task ->
//                dependsOn(task)
//                from(task)
//            }
//        }
//        dep.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
//            dep.tasks.findByName("processResources")?.let { task ->
//                dependsOn(task)
//                from(task)
//            }
//        }
//    }
//}