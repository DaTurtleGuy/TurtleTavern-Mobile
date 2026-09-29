package com.daturtleguy.turtletavern

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

// Structured view over the server's config.yaml for the drawer's Config tab.
//
// The file is parsed with a small indentation-based reader (no new
// dependencies), rendered as section headers, toggle rows for booleans and
// text fields for everything else, and saved with line-targeted edits so
// every untouched line — comments, blank lines, quoting style — survives
// byte-for-byte.

// ---------- Model ----------

internal sealed interface CfgNode {
    val section: String
    val depth: Int
}

internal data class CfgGroup(
    val key: String,
    override val section: String,
    override val depth: Int,
    val desc: String,
    val children: MutableList<CfgNode> = mutableListOf(),
) : CfgNode

internal enum class LeafKind { BOOL, TEXT }

internal data class CfgLeaf(
    val key: String,
    val lineIdx: Int,
    // Original line split around the value token: prefix holds everything up
    // to the value (indent + "key:" + spaces), suffix the trailing comment
    // including its preceding spaces (or ""). Rebuilt as prefix + new + suffix.
    val prefix: String,
    val suffix: String,
    val desc: String,
    override val section: String,
    override val depth: Int,
    val kind: LeafKind,
    val quote: Char?,
    val wasEmpty: Boolean,
    var display: String,
    var dirty: Boolean = false,
) : CfgNode

internal data class CfgListItem(
    val lineIdx: Int,
    val tailCount: Int,
    var text: String,
    var deleted: Boolean = false,
)

internal data class CfgList(
    val key: String,
    val lineIdx: Int,
    val desc: String,
    override val section: String,
    override val depth: Int,
    val itemPrefix: String,
    val items: MutableList<CfgListItem> = mutableListOf(),
    val added: MutableList<String> = mutableListOf(),
) : CfgNode

internal data class CfgRaw(val lineIdx: Int) : CfgNode {
    override val section: String = ""
    override val depth: Int = 0
}

internal data class CfgDoc(val lines: MutableList<String>, val roots: List<CfgNode>) {
    fun render(): String {
        val out = lines.toMutableList()
        val splices = mutableListOf<Triple<Int, Int, List<String>>>()
        fun visit(n: CfgNode) {
            when (n) {
                is CfgLeaf -> if (n.dirty) {
                    out[n.lineIdx] = if (n.wasEmpty && n.display.isEmpty()) {
                        out[n.lineIdx].trimEnd()
                    } else if (n.wasEmpty) {
                        out[n.lineIdx].trimEnd() + " " + n.display + n.suffix
                    } else {
                        n.prefix + serializeLeaf(n) + n.suffix
                    }
                }
                is CfgGroup -> n.children.forEach(::visit)
                is CfgList -> {
                    val kept = n.items.filter { !it.deleted }
                    if (kept.size != n.items.size || n.added.isNotEmpty()) {
                        val rebuilt = mutableListOf<String>()
                        for (it in kept) {
                            rebuilt.add(n.itemPrefix + it.text)
                            for (k in 1..it.tailCount) rebuilt.add(lines[it.lineIdx + k])
                        }
                        for (a in n.added) rebuilt.add(n.itemPrefix + a)
                        val end = n.items.maxOfOrNull { it.lineIdx + it.tailCount }
                            ?: n.lineIdx
                        splices.add(Triple(n.lineIdx + 1, end, rebuilt))
                    }
                }
                is CfgRaw -> Unit
            }
        }
        roots.forEach(::visit)
        for ((start, end, replacement) in splices.sortedByDescending { it.first }) {
            out.subList(start, end + 1).clear()
            out.addAll(start, replacement)
        }
        return out.joinToString("\n")
    }

    private fun serializeLeaf(n: CfgLeaf): String {
        if (n.kind == LeafKind.BOOL) return n.display
        val q = n.quote ?: return n.display
        val escaped = if (q == '\'') n.display.replace("'", "''")
        else n.display.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t")
        return "$q$escaped$q"
    }
}

// ---------- Parser ----------

internal object ConfigParser {
    private val sectionRe = Regex("""^#\s*--\s*(.+?)\s*--\s*$""")
    private val bools = setOf("true", "false", "True", "False", "TRUE", "FALSE")
    private val quotedRe = Regex("""^('(?:[^']|'')*'|"(?:[^"\\]|\\.)*")(\s+#.*)?$""")
    private val bareRe = Regex("""^(\S+)(\s+#.*)?$""")

    private data class Tok(
        val idx: Int,
        val indent: Int,
        val key: String?,
        val rest: String,
        val prefix: String,
        val raw: Boolean = false,
    )

    fun parse(rawLines: List<String>): CfgDoc {
        val lines = rawLines.toMutableList()
        val roots = mutableListOf<CfgNode>()
        var section = "Settings"
        val pendingDesc = mutableListOf<String>()
        val toks = mutableListOf<Tok>()

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val m = sectionRe.matchEntire(trimmed)
                if (m != null) {
                    section = m.groupValues[1].trim()
                    pendingDesc.clear()
                } else {
                    pendingDesc.add(trimmed.removePrefix("#").trim())
                }
                continue
            }
            val indent = line.takeWhile { it == ' ' }.length
            if (indent != line.takeWhile { it == ' ' || it == '\t' }.length) {
                toks.add(Tok(idx, indent, null, "", "", raw = true))
                pendingDesc.clear()
                continue
            }
            val body = line.substring(indent)
            if (body.startsWith("- ") || body == "-") {
                toks.add(Tok(idx, indent, null, body.removePrefix("-").trimStart(), ""))
                pendingDesc.clear()
                continue
            }
            val colon = body.indexOf(':')
            if (colon <= 0) {
                toks.add(Tok(idx, indent, null, "", "", raw = true))
                pendingDesc.clear()
                continue
            }
            val key = body.substring(0, colon).trim()
            if (key.isEmpty() || key.any { it.isWhitespace() }) {
                toks.add(Tok(idx, indent, null, "", "", raw = true))
                pendingDesc.clear()
                continue
            }
            val afterColon = body.substring(colon + 1)
            val valueStart = afterColon.indexOfFirst { it != ' ' && it != '\t' }
            val prefix = line.substring(0, indent + colon + 1 +
                if (valueStart < 0) afterColon.length else valueStart)
            val rest = if (valueStart < 0) "" else afterColon.substring(valueStart)
            toks.add(Tok(idx, indent, key, rest, prefix))
            pendingDesc.clear()
        }

        // Re-attach descriptions/sections per token by re-scanning comments.
        val meta = collectMeta(lines, toks.map { it.idx }.toSet())
        val stack = ArrayDeque<Pair<Int, MutableList<CfgNode>>>()
        stack.addLast(-1 to roots)
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.raw) {
                roots.add(CfgRaw(t.idx))
                i++
                continue
            }
            while (stack.last().first >= t.indent) stack.removeLast()
            val parent = stack.last().second
            val m = meta[t.idx]
            val sec = m?.section ?: "Settings"
            val desc = m?.desc ?: ""
            if (t.key == null) { i++; continue } // items attach in phase 2
            val next = toks.getOrNull(i + 1)
            if (next != null && !next.raw && next.indent > t.indent && t.rest.isEmpty()) {
                if (next.key == null) {
                    parent.add(CfgList(t.key, t.idx, desc, sec, depthOf(stack),
                        itemPrefixOf(lines, next)))
                    // No push: in valid YAML nothing but "- " lines (handled
                    // in phase 2) can follow a list header at deeper indent.
                } else {
                    val g = CfgGroup(t.key, sec, depthOf(stack), desc)
                    parent.add(g)
                    stack.addLast(t.indent to g.children)
                }
            } else {
                parent.add(makeLeaf(t, sec, desc, depthOf(stack)))
            }
            i++
        }
        attachItems(lines, roots)
        return CfgDoc(lines, roots)
    }

    private fun depthOf(stack: ArrayDeque<Pair<Int, MutableList<CfgNode>>>): Int =
        (stack.size - 1).coerceAtLeast(0)

    private fun itemPrefixOf(lines: List<String>, tok: Tok): String {
        val line = lines[tok.idx]
        val dash = line.indexOf("- ")
        return if (dash >= 0) line.substring(0, dash + 2) else "  - "
    }

    private fun makeLeaf(t: Tok, section: String, desc: String, depth: Int): CfgNode {
        if (t.rest.isEmpty()) {
            return CfgLeaf(t.key!!, t.idx, t.prefix, "", desc, section, depth,
                LeafKind.TEXT, null, true, "", false)
        }
        quotedRe.matchEntire(t.rest)?.let { m ->
            val raw = m.groupValues[1]
            val q = raw[0]
            val inner = raw.substring(1, raw.length - 1)
            val display = if (q == '\'') inner.replace("''", "'") else unescapeDouble(inner)
            return CfgLeaf(t.key!!, t.idx, t.prefix, m.groupValues[2], desc, section,
                depth, LeafKind.TEXT, q, false, display, false)
        }
        bareRe.matchEntire(t.rest)?.let { m ->
            val token = m.groupValues[1]
            val suffix = m.groupValues[2]
            if (token in bools) {
                return CfgLeaf(t.key!!, t.idx, t.prefix, suffix, desc, section, depth,
                    LeafKind.BOOL, null, false, token.lowercase(), false)
            }
            return CfgLeaf(t.key!!, t.idx, t.prefix, suffix, desc, section, depth,
                LeafKind.TEXT, null, false, token, false)
        }
        // Opaque value (unquoted text with spaces): editable as raw text.
        return CfgLeaf(t.key!!, t.idx, t.prefix, "", desc, section, depth,
            LeafKind.TEXT, null, false, t.rest, false)
    }

    private fun unescapeDouble(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private data class Meta(val section: String, val desc: String)

    private fun collectMeta(lines: List<String>, tokIdx: Set<Int>): Map<Int, Meta> {
        val out = mutableMapOf<Int, Meta>()
        var section = "Settings"
        val pending = mutableListOf<String>()
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val m = sectionRe.matchEntire(trimmed)
                if (m != null) {
                    section = m.groupValues[1].trim()
                    pending.clear()
                } else {
                    pending.add(trimmed.removePrefix("#").trim())
                }
                continue
            }
            if (idx in tokIdx) {
                out[idx] = Meta(section, pending.joinToString("\n"))
                pending.clear()
            } else {
                pending.clear()
            }
        }
        return out
    }

    private fun attachItems(lines: List<String>, roots: List<CfgNode>) {
        fun visit(n: CfgNode) {
            when (n) {
                is CfgList -> {
                    var j = n.lineIdx + 1
                    while (j < lines.size) {
                        val line = lines[j]
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) { j++; continue }
                        val indent = line.takeWhile { it == ' ' }.length
                        if (indent <= indentOf(lines[n.lineIdx])) break
                        if (trimmed.startsWith("- ") || trimmed == "-") {
                            val text = trimmed.removePrefix("-").trimStart()
                            var tail = 0
                            var k = j + 1
                            while (k < lines.size) {
                                val tl = lines[k]
                                val tt = tl.trim()
                                if (tt.isEmpty() || tt.startsWith("#")) break
                                if (tl.takeWhile { it == ' ' }.length <= indent) break
                                tail++
                                k++
                            }
                            n.items.add(CfgListItem(j, tail, text))
                            j = k
                        } else {
                            j++
                        }
                    }
                }
                is CfgGroup -> n.children.forEach(::visit)
                else -> Unit
            }
        }
        roots.forEach(::visit)
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length
}

// ---------- View ----------

internal class ConfigEditorUi(
    private val ctx: Context,
    private val doc: CfgDoc,
    private val container: LinearLayout,
) {
    private data class Row(val view: View, val haystack: String, var section: String)

    private val rows = mutableListOf<Row>()
    private val sectionHeaders = mutableMapOf<String, View>()

    fun build() {
        container.removeAllViews()
        val path = ArrayDeque<String>()
        fun sectionTitle(s: String) {
            if (s !in sectionHeaders) {
                val tv = TextView(ctx).apply {
                    text = s
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(4), dp(16), dp(4), dp(4))
                }
                container.addView(tv)
                sectionHeaders[s] = tv
            }
        }
        fun visit(n: CfgNode) {
            when (n) {
                is CfgGroup -> {
                    sectionTitle(n.section)
                    val tv = TextView(ctx).apply {
                        text = n.key
                        setTypeface(typeface, Typeface.BOLD)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTextColor(0xFFDDDDDD.toInt())
                        setPadding(dp(4 + n.depth * 10), dp(8), dp(4), dp(2))
                    }
                    container.addView(tv)
                    rows.add(Row(tv, "${n.key} ${n.desc}".lowercase(), n.section))
                    if (n.desc.isNotEmpty()) {
                        val d = descView(n.desc, n.depth)
                        container.addView(d)
                        rows.add(Row(d, n.desc.lowercase(), n.section))
                    }
                    path.addLast(n.key)
                    n.children.forEach(::visit)
                    path.removeLast()
                }
                is CfgLeaf -> {
                    sectionTitle(n.section)
                    val fullPath = (path + n.key).joinToString(".")
                    container.addView(leafRow(n))
                    if (n.desc.isNotEmpty()) {
                        val d = descView(n.desc, n.depth)
                        container.addView(d)
                        rows.add(Row(d, n.desc.lowercase(), n.section))
                    }
                    rows.add(Row(container.getChildAt(container.childCount -
                        (if (n.desc.isNotEmpty()) 2 else 1)),
                        "$fullPath ${n.key} ${n.desc}".lowercase(), n.section))
                }
                is CfgList -> {
                    sectionTitle(n.section)
                    val fullPath = (path + n.key).joinToString(".")
                    container.addView(listRows(n))
                    if (n.desc.isNotEmpty()) {
                        val d = descView(n.desc, n.depth)
                        container.addView(d)
                        rows.add(Row(d, n.desc.lowercase(), n.section))
                    }
                    rows.add(Row(container.getChildAt(container.childCount -
                        (if (n.desc.isNotEmpty()) 2 else 1)),
                        "$fullPath ${n.key} ${n.desc}".lowercase(), n.section))
                }
                is CfgRaw -> {
                    val tv = TextView(ctx).apply {
                        text = doc.lines[n.lineIdx]
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(0xFF888888.toInt())
                        typeface = Typeface.MONOSPACE
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                    }
                    container.addView(tv)
                }
            }
        }
        doc.roots.forEach(::visit)
    }

    fun applySearch(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            rows.forEach { it.view.visibility = View.VISIBLE }
            sectionHeaders.values.forEach { it.visibility = View.VISIBLE }
            return
        }
        val visibleSections = mutableSetOf<String>()
        for (r in rows) {
            val show = r.haystack.contains(q)
            r.view.visibility = if (show) View.VISIBLE else View.GONE
            if (show && r.section.isNotEmpty()) visibleSections.add(r.section)
        }
        for ((name, header) in sectionHeaders) {
            val showAll = name.lowercase().contains(q)
            header.visibility = if (showAll || name in visibleSections) View.VISIBLE else View.GONE
            if (showAll) rows.filter { it.section == name }
                .forEach { it.view.visibility = View.VISIBLE }
        }
    }

    private fun descView(desc: String, depth: Int): TextView = TextView(ctx).apply {
        text = desc
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFFAAAAAA.toInt())
        setPadding(dp(4 + depth * 10), 0, dp(4), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun leafRow(n: CfgLeaf): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4 + n.depth * 10), dp(4), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        if (n.kind == LeafKind.BOOL) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val label = TextView(ctx).apply {
                text = n.key
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = SwitchCompat(ctx).apply {
                isChecked = n.display == "true"
                setOnCheckedChangeListener { _, checked ->
                    n.display = if (checked) "true" else "false"
                    n.dirty = true
                }
            }
            line.addView(label)
            line.addView(toggle)
            row.addView(line)
        } else {
            row.addView(TextView(ctx).apply {
                text = n.key
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFFEEEEEE.toInt())
            })
            row.addView(EditText(ctx).apply {
                setText(n.display)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFDDDDDD.toInt())
                typeface = Typeface.MONOSPACE
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        n.display = s?.toString() ?: ""
                        n.dirty = true
                    }
                })
            })
        }
        return row
    }

    private fun listRows(n: CfgList): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4 + n.depth * 10), dp(4), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        box.addView(TextView(ctx).apply {
            text = n.key
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFEEEEEE.toInt())
        })
        for (item in n.items) {
            if (item.deleted) continue
            box.addView(itemRow(n, item, box))
        }
        box.addView(Button(ctx).apply {
            text = "+ Add"
            setOnClickListener {
                val added = ""
                n.added.add(added)
                val row = itemRow(n, null, box, n.added.size - 1)
                box.addView(row, box.childCount - 1)
            }
        })
        return box
    }

    private fun itemRow(
        n: CfgList,
        item: CfgListItem?,
        box: LinearLayout,
        addedIdx: Int = -1,
    ): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val field = EditText(ctx).apply {
            setText(item?.text ?: "")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFDDDDDD.toInt())
            typeface = Typeface.MONOSPACE
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString() ?: ""
                    if (item != null) item.text = v
                    else n.added[addedIdx] = v
                }
            })
        }
        val del = Button(ctx).apply {
            text = "×"
            setOnClickListener {
                if (item != null) item.deleted = true
                else if (addedIdx >= 0) n.added.removeAt(addedIdx)
                box.removeView(line)
            }
        }
        line.addView(field)
        line.addView(del)
        return line
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
