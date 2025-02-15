package space.kscience.gradle

import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.BinaryCompatibilityValidatorPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.signing.Sign
import org.jetbrains.changelog.ChangelogPlugin
import org.jetbrains.changelog.ChangelogPluginExtension
import org.jetbrains.dokka.gradle.AbstractDokkaTask
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import space.kscience.gradle.internal.addPublishing
import space.kscience.gradle.internal.addSonatypePublishing
import space.kscience.gradle.internal.setupPublication

/**
 * Simplifies adding repositories for Maven publishing, responds for releasing tasks for projects.
 */
public class KSciencePublishingExtension(public val project: Project) {
    private var isVcsInitialized = false

    /**
     * Configures Git repository (sources) for the publication.
     *
     * @param vcsUrl URL of the repository's web interface.
     * @param connectionUrl URL of the Git repository.
     * @param developerConnectionUrl URL of the Git repository for developers.
     */
    public fun pom(
        vcsUrl: String,
        connectionUrl: String? = null,
        developerConnectionUrl: String? = connectionUrl,
        connectionPrefix: String = "scm:git:",
        pomConfig: MavenPom.() -> Unit,
    ) {
        if (!isVcsInitialized) {
            project.setupPublication {
                url.set(vcsUrl)

                scm {
                    url.set(vcsUrl)
                    connectionUrl?.let { connection.set("$connectionPrefix$it") }
                    developerConnectionUrl?.let { developerConnection.set("$connectionPrefix$it") }
                }
                pomConfig()
            }

            isVcsInitialized = true
        }
    }

    /**
     * Add a repository with [repositoryName]. Uses "publishing.$repositoryName.user" and "publishing.$repositoryName.token"
     * properties pattern to store user and token
     */
    public fun repository(
        repositoryName: String,
        url: String,
    ) {
        require(isVcsInitialized) { "The project vcs is not set up use 'pom' method to do so" }
        project.addPublishing(repositoryName, url)
    }

    /**
     * Adds Sonatype Maven repository to publishing.

     */
    public fun sonatype(sonatypeRoot: String = "https://s01.oss.sonatype.org") {
        require(isVcsInitialized) { "The project vcs is not set up use 'pom' method to do so" }
        project.addSonatypePublishing(sonatypeRoot)
    }
}

/**
 * Applies third-party plugins (Dokka, Changelog, binary compatibility validator); configures Maven publishing, README
 * generation.
 */
public open class KScienceProjectPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = target.run {
        apply<ChangelogPlugin>()
        apply<DokkaPlugin>()
        apply<BinaryCompatibilityValidatorPlugin>()

        allprojects {
            repositories {
                mavenCentral()
                maven("https://repo.kotlin.link")
                google()
            }

            // Workaround for https://github.com/gradle/gradle/issues/15568
            tasks.withType<AbstractPublishToMaven>().configureEach {
                mustRunAfter(tasks.withType<Sign>())
            }
        }

        afterEvaluate {
            if (isInDevelopment) {
                configure<ApiValidationExtension> {
                    validationDisabled = true
                }
            } else {
                configure<ChangelogPluginExtension> {
                    version.set(project.version.toString())
                }
            }
        }

        val rootReadmeExtension = KScienceReadmeExtension(this)
        val ksciencePublish = KSciencePublishingExtension(this)
        extensions.add("ksciencePublish", ksciencePublish)
        extensions.add("readme", rootReadmeExtension)

        //Add readme generators to individual subprojects
        subprojects {
            val readmeExtension = KScienceReadmeExtension(this)
            extensions.add("readme", readmeExtension)

            tasks.create("generateReadme") {
                group = "documentation"
                description = "Generate a README file if stub is present"

                inputs.property("extension", readmeExtension)

                if (readmeExtension.readmeTemplate.exists()) {
                    inputs.file(readmeExtension.readmeTemplate)
                }
                readmeExtension.inputFiles.forEach {
                    if (it.exists()) {
                        inputs.file(it)
                    }
                }

                val readmeFile = this@subprojects.file("README.md")
                outputs.file(readmeFile)

                doLast {
                    val readmeString = readmeExtension.readmeString()
                    if (readmeString != null) {
                        readmeFile.writeText(readmeString)
                    }
                }
            }

//            tasks.withType<AbstractDokkaTask> {
//                dependsOn(generateReadme)
//            }
        }

        val generateReadme by tasks.creating {
            group = "documentation"
            description = "Generate a README file and a feature matrix if stub is present"

            subprojects {
                tasks.findByName("generateReadme")?.let {
                    dependsOn(it)
                }
            }

            inputs.property("extension", rootReadmeExtension)

            if (rootReadmeExtension.readmeTemplate.exists()) {
                inputs.file(rootReadmeExtension.readmeTemplate)
            }

            rootReadmeExtension.inputFiles.forEach {
                if (it.exists()) {
                    inputs.file(it)
                }
            }

            val readmeFile = project.file("README.md")
            outputs.file(readmeFile)

            doLast {
//                val projects = subprojects.associate {
//                    val normalizedPath = it.path.replaceFirst(":","").replace(":","/")
//                    it.path.replace(":","/") to it.extensions.findByType<KScienceReadmeExtension>()
//                }

                if (rootReadmeExtension.readmeTemplate.exists()) {

                    val modulesString = buildString {
                        subprojects.forEach { subproject ->
//                            val name = subproject.name
                            subproject.extensions.findByType<KScienceReadmeExtension>()?.let { ext ->
                                val path = subproject.path.replaceFirst(":", "").replace(":", "/")
                                appendLine("\n### [$path]($path)")
                                ext.description?.let { appendLine("> ${ext.description}") }
                                appendLine(">\n> **Maturity**: ${ext.maturity}")
                                val featureString = ext.featuresString(itemPrefix = "> - ", pathPrefix = "$path/")
                                if (featureString.isNotBlank()) {
                                    appendLine(">\n> **Features:**")
                                    appendLine(featureString)
                                }
                            }
                        }
                    }

                    rootReadmeExtension.property("modules", modulesString)

                    rootReadmeExtension.readmeString()?.let {
                        readmeFile.writeText(it)
                    }
                }

            }
        }

        tasks.withType<AbstractDokkaTask> {
            dependsOn(generateReadme)
        }

        tasks.create("version") {
            group = "publishing"
            val versionFileProvider = project.layout.buildDirectory.file("project-version.txt")
            outputs.file(versionFileProvider)
            doLast {
                val versionFile = versionFileProvider.get().asFile
                versionFile.createNewFile()
                versionFile.writeText(project.version.toString())
            }
        }

        // Disable API validation for snapshots
        if (isInDevelopment) {
            extensions.findByType<ApiValidationExtension>()?.apply {
                validationDisabled = true
                logger.warn("API validation is disabled for snapshot or dev version")
            }
        }

        plugins.withType<YarnPlugin>() {
            rootProject.configure<YarnRootExtension> {
                lockFileDirectory = rootDir.resolve("gradle")
                yarnLockMismatchReport = YarnLockMismatchReport.WARNING
            }
        }
    }

    public companion object {
        public const val DEPLOY_GROUP: String = "deploy"
    }
}
