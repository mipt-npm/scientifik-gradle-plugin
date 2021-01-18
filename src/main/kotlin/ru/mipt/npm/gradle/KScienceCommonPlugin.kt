package ru.mipt.npm.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinJsProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import ru.mipt.npm.gradle.internal.applyRepos
import ru.mipt.npm.gradle.internal.applySettings
import ru.mipt.npm.gradle.internal.fromJsDependencies

open class KScienceCommonPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        //Common configuration
        registerKScienceExtension()
        repositories.applyRepos()

        // apply dokka for all projects
        if (!plugins.hasPlugin("org.jetbrains.dokka")) {
            plugins.apply("org.jetbrains.dokka")
        }

        //Configuration for K-JVM plugin
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            //logger.info("Applying KScience configuration for JVM project")
            configure<KotlinJvmProjectExtension> {
                explicitApiWarning()

                sourceSets["main"].apply {
                    languageSettings.applySettings()
                }

                sourceSets["test"].apply {
                    languageSettings.applySettings()
                    dependencies {
                        implementation(kotlin("test-junit5"))
                        implementation("org.junit.jupiter:junit-jupiter:5.6.1")
                    }
                }
            }
        }

        pluginManager.withPlugin("org.jetbrains.kotlin.js") {
            //logger.info("Applying KScience configuration for JS project")
            configure<KotlinJsProjectExtension> {
                explicitApiWarning()

                js(IR) {
                    browser()
                }

                sourceSets["main"].apply {
                    languageSettings.applySettings()
                }

                sourceSets["test"].apply {
                    languageSettings.applySettings()
                    dependencies {
                        implementation(kotlin("test-js"))
                    }
                }
            }

            (tasks.findByName("processResources") as? Copy)?.apply {
                fromJsDependencies("runtimeClasspath")
            }

        }

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configure<KotlinMultiplatformExtension> {
                explicitApiWarning()

                jvm {
                    compilations.all {
                        kotlinOptions {
                            useIR = true
                            jvmTarget = KScienceVersions.JVM_TARGET.toString()
                        }
                    }
                }

                js(IR) {
                    browser()
                }

                sourceSets.invoke {
                    val commonMain by getting
                    val commonTest by getting {
                        dependencies {
                            implementation(kotlin("test-common"))
                            implementation(kotlin("test-annotations-common"))
                        }
                    }
                    val jvmMain by getting
                    val jvmTest by getting {
                        dependencies {
                            implementation(kotlin("test-junit5"))
                            implementation("org.junit.jupiter:junit-jupiter:5.6.1")
                        }
                    }
                    val jsMain by getting
                    val jsTest by getting {
                        dependencies {
                            implementation(kotlin("test-js"))
                        }
                    }
                }

                afterEvaluate {
                    targets.all {
                        sourceSets.all {
                            languageSettings.applySettings()
                        }
                    }
                }

                (tasks.findByName("jsProcessResources") as? Copy)?.apply {
                    fromJsDependencies("jsRuntimeClasspath")
                }
            }
        }

        afterEvaluate {
            extensions.findByType<JavaPluginExtension>()?.apply {
                targetCompatibility = KScienceVersions.JVM_TARGET
            }

            tasks.apply {
                withType<KotlinJvmCompile> {
                    kotlinOptions {
                        useIR = true
                        jvmTarget = KScienceVersions.JVM_TARGET.toString()
                    }
                }
                withType<Test> {
                    useJUnitPlatform()
                }
            }
        }
    }
}
