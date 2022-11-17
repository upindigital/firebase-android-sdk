package com.google.firebase.gradle

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

data class FirebaseLibrary(val moduleNames: List<String>, val directories: List<String>)

open class ReleaseGenerator : DefaultTask() {
    @TaskAction
    @Throws(Exception::class)
    fun generateReleaseConfig() {
        val currentRelease = project.property("currentRelease").toString()
        val pastRelease = project.property("pastRelease").toString()
        val rootDir = project.rootDir
        val availableModules = parseSubProjects(rootDir)
        val firebaseLibraries = extractLibraries(availableModules)

        val repo = Git.open(rootDir)
        val headRef = repo.repository.resolve(Constants.HEAD)
        val branchRef = getObjectRefForBranchName(repo, pastRelease)

        val changedDirs = getChangedLibraries(repo, branchRef, headRef, firebaseLibraries)
        writeReleaseConfig(rootDir, changedDirs, currentRelease)
    }

    private fun extractLibraries(availableModules: Set<String>): List<FirebaseLibrary> {
        val nonKtxModules = availableModules.filter { !it.endsWith("ktx") }.toSet()
        return nonKtxModules
                .filter { possibleModule -> !nonKtxModules.any { it.startsWith("$possibleModule:") } }
                .map { moduleName ->
                    val ktxModuleName = "$moduleName:ktx"

                    val moduleNames = listOf(moduleName, ktxModuleName).filter { availableModules.contains(it) }
                    val directories = moduleNames.map { it.replace(":", "/") }

                    FirebaseLibrary(moduleNames, directories)
                }
    }

    private fun parseSubProjects(rootDir: File) = File(rootDir, "subprojects.cfg").readLines().filter { !it.startsWith("#") }.filter { it.isNotEmpty() }.toSet()

    @Throws(GitAPIException::class)
    private fun getObjectRefForBranchName(repo: Git, branchName: String) =
            repo.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                    .firstOrNull { it.name == "refs/remotes/origin/$branchName" }?.objectId
                    ?: throw RuntimeException("Could not find branch named $branchName")

    private fun getChangedLibraries(repo: Git, previousReleaseRef: ObjectId, currentReleaseRef: ObjectId, libraries: List<FirebaseLibrary>) =
            libraries.filter { library ->
                library.directories.any { checkDirChanges(repo, previousReleaseRef, currentReleaseRef, it) }
            }.flatMap { it.moduleNames }

    private fun checkDirChanges(repo: Git, previousReleaseRef: ObjectId, currentReleaseRef: ObjectId, directory: String) =
            repo.log()
                    .addPath("$directory/")
                    .addRange(previousReleaseRef, currentReleaseRef)
                    .setMaxCount(1)
                    .call()
                    .iterator().hasNext()

    private fun writeReleaseConfig(configPath: File, libraries: List<String>, releaseName: String) {
        File(configPath, "release.cfg").writeText(
                """
                    [release]
                    name = $releaseName
                    mode = RELEASE
                    
                    [modules]
                    ${libraries.joinToString("\n".padEnd(21, ' '))}
                """.trimIndent()
        )
    }
}
