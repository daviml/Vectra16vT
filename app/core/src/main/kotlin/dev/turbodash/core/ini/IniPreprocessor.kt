package dev.turbodash.core.ini

/**
 * Resolves the TunerStudio INI preprocessor: `#if/#elif/#else/#endif`, `#set/#unset` and `#define`.
 *
 * Inactive lines are replaced by empty strings so line numbers stay stable for error messages.
 * A line starting with `##` is a commented-out directive and is ignored.
 */
object IniPreprocessor {

    class Result(
        val lines: List<String>,
        val defines: Map<String, String>,
        /** Every symbol referenced by an `#if`/`#elif`, active or not. Used to offer project settings in the UI. */
        val referencedSymbols: Set<String>,
    )

    private class Frame(val parentActive: Boolean, var taken: Boolean, var active: Boolean)

    fun process(text: String, symbols: Set<String>): Result {
        val active = symbols.toMutableSet()
        val defines = LinkedHashMap<String, String>()
        val referenced = LinkedHashSet<String>()
        val out = ArrayList<String>()
        val stack = ArrayDeque<Frame>()
        fun isActive() = stack.lastOrNull()?.active ?: true

        fun eval(arg: String): Boolean {
            val words = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return false
            // Support a single symbol, optionally negated, joined by || / && (rare in practice).
            var result: Boolean? = null
            var pendingOp = "||"
            for (w in words) {
                if (w == "||" || w == "&&") { pendingOp = w; continue }
                val neg = w.startsWith("!")
                val sym = w.removePrefix("!")
                referenced += sym
                val v = (sym in active) != neg
                result = when {
                    result == null -> v
                    pendingOp == "&&" -> result && v
                    else -> result || v
                }
            }
            return result ?: false
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimStart()
            if (line.startsWith("#")) {
                if (line.startsWith("##")) { out += ""; continue }
                val body = line.substring(1).trim()
                val directive = body.takeWhile { !it.isWhitespace() }.lowercase()
                val arg = stripComment(body.substring(directive.length)).trim()
                when (directive) {
                    "if" -> {
                        val parent = isActive()
                        val c = parent && eval(arg)
                        stack.addLast(Frame(parent, c, c))
                    }
                    "elif" -> stack.lastOrNull()?.let { f ->
                        if (f.taken) f.active = false
                        else {
                            val c = f.parentActive && eval(arg)
                            f.active = c
                            f.taken = c
                        }
                    }
                    "else" -> stack.lastOrNull()?.let { f ->
                        f.active = f.parentActive && !f.taken
                        f.taken = true
                    }
                    "endif" -> stack.removeLastOrNull()
                    "set" -> if (isActive()) firstWord(arg)?.let { active += it }
                    "unset" -> if (isActive()) firstWord(arg)?.let { active -= it }
                    "define" -> if (isActive()) {
                        val name = arg.substringBefore('=').trim()
                        // Expand references now: a define may extend an earlier define of the same name.
                        val value = Regex("\\$(\\w+)").replace(arg.substringAfter('=', "").trim()) { m ->
                            defines[m.groupValues[1]] ?: m.value
                        }
                        if (name.isNotEmpty()) defines[name] = value
                    }
                }
                out += ""
                continue
            }
            out += if (isActive()) raw else ""
        }
        return Result(out, defines, referenced)
    }

    private fun firstWord(s: String) = s.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() }

    private fun stripComment(s: String): String {
        val i = s.indexOf(';')
        return if (i >= 0) s.substring(0, i) else s
    }
}
