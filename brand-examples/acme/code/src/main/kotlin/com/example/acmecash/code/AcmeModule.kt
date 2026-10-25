package com.example.acmecash.code

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import whitewallet.brand.NoteStrategy

/** Registers Acme's code with the app. brand.properties decides whether and in which order users see it. */
@Module
@InstallIn(SingletonComponent::class)
object AcmeModule {
    @Provides
    @IntoSet
    fun tidySmallNotes(): NoteStrategy = TidySmallNotes()
}
