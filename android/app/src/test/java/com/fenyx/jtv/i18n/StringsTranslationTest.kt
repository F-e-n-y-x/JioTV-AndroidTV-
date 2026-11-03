package com.fenyx.jtv.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/** Every translatable string in res/values has a Hindi twin in res/values-hi, with the same placeholders. */
class StringsTranslationTest {

    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private data class Entry(val texts: List<String>)

    private fun load(dir: String, onlyTranslatable: Boolean): Map<String, Entry> {
        val out = linkedMapOf<String, Entry>()
        val files = File(res, dir).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        for (f in files.sortedBy { it.name }) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val kids = doc.documentElement.childNodes
            for (i in 0 until kids.length) {
                val e = kids.item(i) as? Element ?: continue
                if (onlyTranslatable && e.getAttribute("translatable") == "false") continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> out["string/$name"] = Entry(listOf(e.textContent))
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        out["plurals/$name"] = Entry((0 until items.length).map { items.item(it).textContent })
                    }
                    "string-array" -> {
                        val items = e.getElementsByTagName("item")
                        out["array/$name"] = Entry((0 until items.length).map { items.item(it).textContent })
                    }
                }
            }
        }
        return out
    }

    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc]")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value.replace(Regex("\\.\\d+"), "") }.toSortedSet()

    @Test fun hindiHasEveryKey() {
        val en = load("values", onlyTranslatable = true)
        val hi = load("values-hi", onlyTranslatable = false)
        assertTrue("no strings found under $res", en.size > 10)
        val missing = en.keys - hi.keys
        if (missing.isNotEmpty()) fail("values-hi is missing ${missing.size} key(s):\n" + missing.joinToString("\n"))
    }

    @Test fun hindiHasNoStaleKeys() {
        val en = load("values", onlyTranslatable = false)
        val hi = load("values-hi", onlyTranslatable = false)
        val stale = hi.keys - en.keys
        if (stale.isNotEmpty()) fail("values-hi has key(s) not in values:\n" + stale.joinToString("\n"))
    }

    @Test fun placeholdersMatch() {
        val en = load("values", onlyTranslatable = true)
        val hi = load("values-hi", onlyTranslatable = false)
        val bad = en.mapNotNull { (k, e) ->
            val h = hi[k] ?: return@mapNotNull null
            val want = e.texts.flatMap { placeholders(it) }.toSortedSet()
            val got = h.texts.flatMap { placeholders(it) }.toSortedSet()
            if (want != got) "$k: en $want, hi $got" else null
        }
        if (bad.isNotEmpty()) fail("placeholders differ:\n" + bad.joinToString("\n"))
    }
}
