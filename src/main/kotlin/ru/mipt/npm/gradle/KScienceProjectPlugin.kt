package ru.mipt.npm.gradle

import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.BinaryCompatibilityValidatorPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.*
import org.jetbrains.changelog.ChangelogPlugin
import org.jetbrains.changelog.ChangelogPluginExtension
import org.jetbrains.dokka.gradle.AbstractDokkaTask
import org.jetbrains.dokka.gradle.DokkaPlugin
import ru.mipt.npm.gradle.internal.*

private fun Project.allTasks(): Set<Task> = allprojects.flatMapTo(HashSet()) { it.tasks }

@Suppress("unused")
public class KSciencePublishingExtension(public val project: Project) {
    private var isVcsInitialized = false

    @Deprecated("Use git function and report an issue if other VCS is used.")
    public fun vcs(vcsUrl: String) {
        if (!isVcsInitialized) {
            project.setupPublication {
                url.set(vcsUrl)
                scm { url.set(vcsUrl) }
            }

            isVcsInitialized = true
        }
    }

    /**
     * Configures Git repository (sources) for the publication.
     *
     * @param vcsUrl URL of the repository's web interface.
     * @param connectionUrl URL of the Git repository.
     * @param developerConnectionUrl URL of the Git repository for developers.
     */
    public fun git(vcsUrl: String, connectionUrl: String? = null, developerConnectionUrl: String? = connectionUrl) {
        if (!isVcsInitialized) {
            project.setupPublication {
                url.set(vcsUrl)

                scm {
                    url.set(vcsUrl)
                    connectionUrl?.let { connection.set("scm:git:$it") }
                    developerConnectionUrl?.let { developerConnection.set("scm:git:$it") }
                }
            }

            isVcsInitialized = true
        }
    }

    private fun linkPublicationsToReleaseTask(name: String) = project.afterEvaluate {
        allTasks()
            .filter { it.name.startsWith("publish") && it.name.endsWith("To${name.capitalize()}Repository") }
            .forEach {
                val theName = "release${it.name.removePrefix("publish").removeSuffix("To${name.capitalize()}Repository")}"
                logger.info("Making $theName task depend on ${it.name}")

                val releaseTask = project.tasks.findByName(theName) ?: project.tasks.create(theName) {
                    group = KScienceProjectPlugin.RELEASE_GROUP
                    description = "Publish development or production release based on version suffix"
                }

                releaseTask.dependsOn(it)
            }
    }

    /**
     * Adds GitHub as VCS and adds GitHub Packages Maven repository to publishing.
     *
     * @param githubProject the GitHub project.
     * @param githubOrg the GitHub user or organization.
     * @param addToRelease publish packages in the `release` task to the GitHub repository.
     */
    public fun github(
        githubProject: String,
        githubOrg: String = "mipt-npm",
        addToRelease: Boolean = project.requestPropertyOrNull("publishing.github") == "true",
    ) {
        // Automatically initialize VCS using GitHub
        if (!isVcsInitialized) {
            git("https://github.com/$githubOrg/${githubProject}", "https://github.com/$githubOrg/${githubProject}.git")
        }

        if (addToRelease) {
            try {
                project.addGithubPublishing(githubOrg, githubProject)
                linkPublicationsToReleaseTask("github")
            } catch (t: Throwable) {
                project.logger.error("Failed to set up github publication", t)
            }
        }
    }

    /**
     * Adds Space Packages Maven repository to publishing.
     *
     * @param spaceRepo the repository URL.
     * @param addToRelease publish packages in the `release` task to the Space repository.
     */
    public fun space(
        spaceRepo: String = "https://maven.pkg.jetbrains.space/mipt-npm/p/sci/maven",
        addToRelease: Boolean = project.requestPropertyOrNull("publishing.space") != "false",
    ) {
        project.addSpacePublishing(spaceRepo)

        if (addToRelease) linkPublicationsToReleaseTask("space")
    }

//    // Bintray publishing
//    var bintrayOrg: String? by project.extra
//    var bintrayUser: String? by project.extra
//    var bintrayApiKey: String? by project.extra
//    var bintrayRepo: String? by project.extra

    /**
     * Adds Sonatype Maven repository to publishing.
     *
     * @param addToRelease  publish packages in the `release` task to the Sonatype repository.
     */
    public fun sonatype(
        addToRelease: Boolean = (project.requestPropertyOrNull("publishing.sonatype") != "false"),
    ) {
        require(isVcsInitialized) { "The project vcs is not set up use 'git' method to do so" }
        project.addSonatypePublishing()

        if (addToRelease) linkPublicationsToReleaseTask("sonatype")
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

        afterEvaluate {
            if (isSnapshot()) {
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
        extensions.add("ksciencePublish", KSciencePublishingExtension(this))
        extensions.add("readme", rootReadmeExtension)

        //Add readme generators to individual subprojects
        subprojects {
            val readmeExtension = KScienceReadmeExtension(this)
            extensions.add("readme", readmeExtension)
            val generateReadme by tasks.creating {
                group = "documentation"
                description = "Generate a README file if stub is present"

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

            tasks.withType<AbstractDokkaTask> {
                dependsOn(generateReadme)
            }
        }

        val generateReadme by tasks.creating {
            group = "documentation"
            description = "Generate a README file and a feature matrix if stub is present"

            subprojects {
                tasks.findByName("generateReadme")?.let {
                    dependsOn(it)
                }
            }

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
                            val name = subproject.name
                            val path = subproject.path.replaceFirst(":", "").replace(":", "/")
                            val ext = subproject.extensions.findByType<KScienceReadmeExtension>()
                            appendLine("<hr/>")
                            appendLine("\n* ### [$name]($path)")
                            if (ext != null) {
                                appendLine("> ${ext.description}")
                                appendLine(">\n> **Maturity**: ${ext.maturity}")
                                val featureString = ext.featuresString(itemPrefix = "> - ", pathPrefix = "$path/")
                                if (featureString.isNotBlank()) {
                                    appendLine(">\n> **Features:**")
                                    appendLine(featureString)
                                }
                            }
                        }
                        appendLine("<hr/>")
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

        // Disable API validation for snapshots
        if (isSnapshot()) {
            extensions.findByType<ApiValidationExtension>()?.apply {
                validationDisabled = true
                logger.warn("API validation is disabled for snapshot or dev version")
            }
        }
    }

    public companion object {
        public const val RELEASE_GROUP: String = "release"
    }
}
