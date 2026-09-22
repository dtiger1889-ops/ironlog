package dev.ironlog.app.data.importer

/**
 * Templates seeded verbatim on import, taking precedence over [TemplateInferrer] for these names.
 * The public build ships none: history inference reconstructs routines from the imported CSV, and
 * the template editor covers the rest. Add entries here only for a private/personal build.
 */
object CanonicalTemplates {
    val LIST: List<InferredTemplate> = emptyList()

    val NAMES: Set<String> = LIST.map { it.name }.toSet()
}
