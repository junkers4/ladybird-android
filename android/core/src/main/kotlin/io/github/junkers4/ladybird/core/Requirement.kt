package io.github.junkers4.ladybird.core

/**
 * Links a test to the requirement(s) it verifies (see requirements/README.md).
 * tools/requirements/check_traceability.py reads these annotations from the test sources.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Requirement(vararg val ids: String)
