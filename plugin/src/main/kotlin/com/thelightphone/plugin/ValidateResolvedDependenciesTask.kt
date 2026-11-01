package com.thelightphone.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Compares what each configuration resolved to against the allowlist,
 * flagging first-level modules that are neither allowed nor a transitive of
 * an allowed module. Declared dependencies are checked at configure time, so
 * this only catches what changed during resolution.
 */
abstract class ValidateResolvedDependenciesTask : DefaultTask() {

    @get:Internal
    val resolutionRoots = linkedMapOf<String, Provider<ResolvedComponentResult>>()

    @TaskAction
    fun validate() {
        val violations = DependencyViolations()
        resolutionRoots.forEach { (config, root) ->
            val resolved = try {
                root.get()
            } catch (_: Exception) {
                return@forEach
            }
            check(config, resolved, violations)
        }
        LightSdkPlugin.formatViolations(emptyList(), violations.lines())?.let { throw GradleException(it) }
    }

    private fun check(config: String, root: ResolvedComponentResult, violations: DependencyViolations) {
        val isKsp = config.startsWith("ksp")
        // The resolved graph carries concrete versions, so exact-pinned
        // coordinates are validated against their pin here as at configure time.
        val isAllowed: (String, String, String?) -> Boolean =
            if (isKsp) { group, name, _ -> LightSdkPlugin.isAllowedKspProcessor(group, name) }
            else { group, name, version -> LightSdkPlugin.isAllowedCoordinate(group, name, version) }

        // Only trust transitives of allowed module deps — not project deps,
        // since project dep transitives may themselves be substituted.
        val firstLevel = modules(root).filter { it.id !is ProjectComponentIdentifier }
        val allowedTransitives = mutableSetOf<String>()
        fun collectTransitives(component: ResolvedComponentResult) {
            modules(component).forEach { child ->
                val version = child.moduleVersion ?: return@forEach
                if (allowedTransitives.add("${version.group}:${version.name}")) collectTransitives(child)
            }
        }
        firstLevel.forEach { component ->
            val version = component.moduleVersion ?: return@forEach
            if (isAllowed(version.group, version.name, version.version)) collectTransitives(component)
        }

        firstLevel.forEach { component ->
            val version = component.moduleVersion ?: return@forEach
            val coordinate = "${version.group}:${version.name}"
            if (coordinate in allowedTransitives || isAllowed(version.group, version.name, version.version)) return@forEach
            val tag = if (isKsp) "unexpected resolved KSP dependency" else "unexpected resolved dependency — possible substitution"
            violations.add(config, "$coordinate:${version.version} ($tag)")
        }
    }

    private fun modules(component: ResolvedComponentResult): List<ResolvedComponentResult> =
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }.distinct()
}
