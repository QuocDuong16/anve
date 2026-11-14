package com.anve.app.ui.common

import androidx.annotation.StringRes

/**
 * A piece of user-facing copy carried through state as a resource ID plus the
 * runtime values its format placeholders need. ViewModels reference
 * `com.anve.app.R` directly — plain ints keep them pure JVM-testable — and the
 * screen resolves the message with `stringResource(id, *args.toTypedArray())`
 * so the copy stays in strings.xml and remains translatable.
 *
 * Static copy (no parameters) travels as a bare `@StringRes Int` instead; use
 * [UiMessage] only when the sentence needs dynamic arguments. Plurals stay out
 * of here: they need `pluralStringResource` with its quantity rules and are
 * resolved where they are rendered.
 */
data class UiMessage(
    @StringRes val id: Int,
    val args: List<Any> = emptyList(),
)
