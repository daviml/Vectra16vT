package dev.turbodash.core.expr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Values and lookups an expression can see. Unknown names evaluate to 0, like a missing channel. */
interface EvalContext {
    fun value(name: String): Double?
    /** Labels of a `bits` constant, for `bitStringValue(...)`. */
    fun options(name: String): List<String>? = null
    fun timeNow(): Double = 0.0
}

/**
 * Compiled TunerStudio expression (`{ ... }` blocks in the INI): C-like arithmetic, comparison,
 * logic, bitwise operators, ternary and a few functions. Results are Double, or String for
 * `bitStringValue`.
 */
class Expression private constructor(val source: String, private val root: Node) {

    fun eval(ctx: EvalContext): Any = root.eval(ctx)

    fun evalNumber(ctx: EvalContext): Double = toNumber(root.eval(ctx))

    /** Identifiers this expression references (used for dependency ordering and validation). */
    val identifiers: Set<String> by lazy { HashSet<String>().also { root.collect(it) } }

    companion object {
        private val cache = HashMap<String, Expression>()

        fun compile(source: String): Expression = synchronized(cache) {
            cache.getOrPut(source) { Expression(source, Parser(tokenizeTyped(source) { k, t -> Tok(k, t) }).parseAll()) }
        }

        /** Compiles leniently: a broken expression evaluates to 0 instead of throwing. */
        fun compileOrZero(source: String): Expression = try {
            compile(source)
        } catch (e: Exception) {
            Expression(source, Node.Num(0.0))
        }

        fun toNumber(v: Any): Double = when (v) {
            is Double -> v
            is Boolean -> if (v) 1.0 else 0.0
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    // ------------------------------------------------------------------ AST

    private sealed class Node {
        abstract fun eval(ctx: EvalContext): Any
        open fun collect(out: MutableSet<String>) {}

        class Num(val v: Double) : Node() {
            override fun eval(ctx: EvalContext) = v
        }

        class Str(val v: String) : Node() {
            override fun eval(ctx: EvalContext) = v
        }

        class Ident(val name: String) : Node() {
            override fun eval(ctx: EvalContext): Any = ctx.value(name) ?: 0.0
            override fun collect(out: MutableSet<String>) { out += name }
        }

        class Unary(val op: String, val a: Node) : Node() {
            override fun eval(ctx: EvalContext): Any {
                val x = toNumber(a.eval(ctx))
                return when (op) {
                    "-" -> -x
                    "+" -> x
                    "!" -> if (x == 0.0) 1.0 else 0.0
                    "~" -> x.toLong().inv().toDouble()
                    else -> x
                }
            }
            override fun collect(out: MutableSet<String>) = a.collect(out)
        }

        class Binary(val op: String, val a: Node, val b: Node) : Node() {
            override fun eval(ctx: EvalContext): Any {
                // short-circuit logic
                if (op == "&&") return if (truthy(a.eval(ctx)) && truthy(b.eval(ctx))) 1.0 else 0.0
                if (op == "||") return if (truthy(a.eval(ctx)) || truthy(b.eval(ctx))) 1.0 else 0.0
                val x = toNumber(a.eval(ctx))
                val y = toNumber(b.eval(ctx))
                return when (op) {
                    "+" -> x + y
                    "-" -> x - y
                    "*" -> x * y
                    "/" -> if (y == 0.0) 0.0 else x / y
                    "%" -> if (y == 0.0) 0.0 else x % y
                    "<" -> b2d(x < y)
                    ">" -> b2d(x > y)
                    "<=" -> b2d(x <= y)
                    ">=" -> b2d(x >= y)
                    "==" -> b2d(x == y)
                    "!=" -> b2d(x != y)
                    "&" -> (x.toLong() and y.toLong()).toDouble()
                    "|" -> (x.toLong() or y.toLong()).toDouble()
                    "^" -> (x.toLong() xor y.toLong()).toDouble()
                    "<<" -> (x.toLong() shl y.toInt()).toDouble()
                    ">>" -> (x.toLong() shr y.toInt()).toDouble()
                    else -> 0.0
                }
            }
            override fun collect(out: MutableSet<String>) { a.collect(out); b.collect(out) }
        }

        class Ternary(val c: Node, val a: Node, val b: Node) : Node() {
            override fun eval(ctx: EvalContext): Any = if (truthy(c.eval(ctx))) a.eval(ctx) else b.eval(ctx)
            override fun collect(out: MutableSet<String>) { c.collect(out); a.collect(out); b.collect(out) }
        }

        class Call(val fn: String, val args: List<Node>) : Node() {
            override fun eval(ctx: EvalContext): Any {
                fun n(i: Int) = toNumber(args.getOrNull(i)?.eval(ctx) ?: 0.0)
                return when (fn) {
                    "bitStringValue" -> {
                        val listName = (args.getOrNull(0) as? Ident)?.name
                        val opts = listName?.let { ctx.options(it) } ?: emptyList()
                        opts.getOrElse(n(1).toInt()) { "" }
                    }
                    "timeNow" -> ctx.timeNow()
                    "abs" -> abs(n(0))
                    "sqrt" -> sqrt(n(0))
                    "log" -> ln(n(0))
                    "log10" -> log10(n(0))
                    "exp" -> exp(n(0))
                    "pow" -> n(0).pow(n(1))
                    "round" -> n(0).roundToLong().toDouble()
                    "floor" -> floor(n(0))
                    "ceil" -> ceil(n(0))
                    "min" -> minOf(n(0), n(1))
                    "max" -> maxOf(n(0), n(1))
                    "isNaN" -> b2d(n(0).isNaN())
                    "isOnline" -> 1.0
                    else -> 0.0 // table(), arrayValue() etc. are not supported yet
                }
            }
            override fun collect(out: MutableSet<String>) = args.forEach { it.collect(out) }
        }
    }

    // ------------------------------------------------------------------ tokenizer

    private data class Tok(val kind: Char, val text: String) // n=number, i=ident, s=string, o=operator

    private class Parser(private val toks: List<Tok>) {
        private var p = 0
        private fun peek() = toks.getOrNull(p)
        private fun next() = toks[p++]
        private fun accept(op: String): Boolean {
            val t = peek()
            if (t != null && t.kind == 'o' && t.text == op) { p++; return true }
            return false
        }
        private fun expect(op: String) { if (!accept(op)) error("esperado '$op' em ${peek()?.text}") }

        fun parseAll(): Node {
            val n = ternary()
            if (p != toks.size) error("token inesperado ${peek()?.text}")
            return n
        }

        private fun ternary(): Node {
            val c = binary(0)
            if (accept("?")) {
                val a = ternary()
                expect(":")
                val b = ternary()
                return Node.Ternary(c, a, b)
            }
            return c
        }

        private val levels = listOf(
            setOf("||"), setOf("&&"), setOf("|"), setOf("^"), setOf("&"),
            setOf("==", "!="), setOf("<", ">", "<=", ">="), setOf("<<", ">>"),
            setOf("+", "-"), setOf("*", "/", "%"),
        )

        private fun binary(level: Int): Node {
            if (level == levels.size) return unary()
            var left = binary(level + 1)
            while (true) {
                val t = peek()
                if (t != null && t.kind == 'o' && t.text in levels[level]) {
                    p++
                    left = Node.Binary(t.text, left, binary(level + 1))
                } else return left
            }
        }

        private fun unary(): Node {
            val t = peek()
            if (t != null && t.kind == 'o' && t.text in setOf("-", "+", "!", "~")) {
                p++
                return Node.Unary(t.text, unary())
            }
            return primary()
        }

        private fun primary(): Node {
            val t = next()
            return when (t.kind) {
                'n' -> Node.Num(parseNumber(t.text))
                's' -> Node.Str(t.text)
                'i' -> if (accept("(")) {
                    val args = ArrayList<Node>()
                    if (!accept(")")) {
                        do { args += ternary() } while (accept(","))
                        expect(")")
                    }
                    Node.Call(t.text, args)
                } else Node.Ident(t.text)
                'o' -> if (t.text == "(") { val n = ternary(); expect(")"); n } else error("operador inesperado ${t.text}")
                else -> error("token inválido")
            }
        }

        private fun parseNumber(s: String): Double =
            if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLong(16).toDouble() else s.toDouble()
    }
}

private fun b2d(b: Boolean) = if (b) 1.0 else 0.0

private fun truthy(v: Any) = when (v) {
    is Double -> v != 0.0
    is String -> v.isNotEmpty()
    else -> false
}

private val OPERATORS = listOf("<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "!", "~", "&", "|", "^", "?", ":", "(", ")", ",")


private fun <T> tokenizeTyped(src: String, make: (Char, String) -> T): List<T> {
    val out = ArrayList<T>()
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                val start = i
                if (c == '0' && i + 1 < src.length && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                    i += 2
                    while (i < src.length && src[i].isLetterOrDigit()) i++
                } else {
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E') && i + 1 < src.length &&
                        (src[i + 1].isDigit() || src[i + 1] == '-' || src[i + 1] == '+')
                    ) {
                        i += 2
                        while (i < src.length && src[i].isDigit()) i++
                    }
                }
                out += make('n', src.substring(start, i))
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val start = i
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.' || src[i] == '$')) i++
                out += make('i', src.substring(start, i))
            }
            c == '"' -> {
                val end = src.indexOf('"', i + 1).let { if (it < 0) src.length else it }
                out += make('s', src.substring(i + 1, end))
                i = end + 1
            }
            else -> {
                val op = OPERATORS.firstOrNull { src.startsWith(it, i) } ?: error("caractere inválido '$c'")
                out += make('o', op)
                i += op.length
            }
        }
    }
    return out
}
