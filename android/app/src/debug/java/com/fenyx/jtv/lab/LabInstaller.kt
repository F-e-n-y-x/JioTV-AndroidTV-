package com.fenyx.jtv.lab

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.fenyx.jtv.DesignLabHook

/** Runs before Application.onCreate in debug builds and plugs the Design Lab into the app. */
class LabInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        DesignLabHook.content = { vm, onPlay, onExit -> DesignLab(vm, onPlay, onExit) }
        return true
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
