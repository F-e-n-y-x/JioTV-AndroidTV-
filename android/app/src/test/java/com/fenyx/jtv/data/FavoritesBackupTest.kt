package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesBackupTest {
    @Test fun mergeKeepsBackupOrderThenCurrentExtras() =
        assertEquals(listOf("3", "1", "2", "9"), FavoritesBackup.merge(listOf("3", "1", "2"), listOf("2", "9", "3")))

    @Test fun mergeIntoEmptyIsTheBackup() =
        assertEquals(listOf("5", "4"), FavoritesBackup.merge(listOf("5", "4"), emptyList()))
}
