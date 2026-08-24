package br.com.freessh

/**
 * Pequeno emulador de tela ANSI/VT para o terminal SSH.
 * Mantem uma grade de caracteres e aplica os controles usados por programas
 * interativos (htop, top, nano, vim etc.) em vez de simplesmente anexar texto.
 */
class AnsiTerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 40,
    private val maxScrollback: Int = 2000
) {
    private data class Cell(var ch: Char = ' ', var fg: Int = 37)

    private var cols = initialCols.coerceAtLeast(20)
    private var rows = initialRows.coerceAtLeast(8)
    private var normal = newScreen()
    private var alternate = newScreen()
    private val scrollback = ArrayDeque<MutableList<Cell>>()

    private var alternateActive = false
    private var row = 0
    private var col = 0
    private var savedRow = 0
    private var savedCol = 0
    private var normalSavedRow = 0
    private var normalSavedCol = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var fg = 37
    private var pending = ""

    private fun blankRow(): MutableList<Cell> = MutableList(cols) { Cell() }
    private fun newScreen(): MutableList<MutableList<Cell>> = MutableList(rows) { blankRow() }
    private fun screen(): MutableList<MutableList<Cell>> = if (alternateActive) alternate else normal

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(20, 240)
        val r = newRows.coerceIn(8, 160)
        if (c == cols && r == rows) return

        fun resizeScreen(old: MutableList<MutableList<Cell>>): MutableList<MutableList<Cell>> {
            val out = MutableList(r) { MutableList(c) { Cell() } }
            val copyRows = minOf(r, old.size)
            val copyCols = minOf(c, cols)
            for (y in 0 until copyRows) {
                for (x in 0 until copyCols) {
                    val cell = old[y][x]
                    out[y][x] = Cell(cell.ch, cell.fg)
                }
            }
            return out
        }

        normal = resizeScreen(normal)
        alternate = resizeScreen(alternate)
        cols = c
        rows = r
        row = row.coerceIn(0, rows - 1)
        col = col.coerceIn(0, cols - 1)
        savedRow = savedRow.coerceIn(0, rows - 1)
        savedCol = savedCol.coerceIn(0, cols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
    }

    @Synchronized
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return renderLocked()
        parse(pending + chunk)
        return renderLocked()
    }

    @Synchronized
    fun render(): String = renderLocked()

    private fun parse(input: String) {
        pending = ""
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch != '\u001B') {
                when (ch) {
                    '\r' -> col = 0
                    '\n' -> lineFeed()
                    '\b' -> col = (col - 1).coerceAtLeast(0)
                    '\t' -> col = (((col / 8) + 1) * 8).coerceAtMost(cols - 1)
                    '\u0007' -> Unit
                    else -> if (ch.code >= 32) putChar(ch)
                }
                i++
                continue
            }

            if (i + 1 >= input.length) {
                pending = input.substring(i)
                break
            }

            when (input[i + 1]) {
                '[' -> {
                    var end = i + 2
                    while (end < input.length && input[end].code !in 0x40..0x7E) end++
                    if (end >= input.length) {
                        pending = input.substring(i)
                        return
                    }
                    handleCsi(input.substring(i + 2, end), input[end])
                    i = end + 1
                }
                ']' -> {
                    var end = i + 2
                    var complete = false
                    while (end < input.length) {
                        if (input[end] == '\u0007') {
                            end++
                            complete = true
                            break
                        }
                        if (input[end] == '\u001B' && end + 1 < input.length && input[end + 1] == '\\') {
                            end += 2
                            complete = true
                            break
                        }
                        end++
                    }
                    if (!complete) {
                        pending = input.substring(i)
                        return
                    }
                    i = end
                }
                '7' -> { savedRow = row; savedCol = col; i += 2 }
                '8' -> { row = savedRow.coerceIn(0, rows - 1); col = savedCol.coerceIn(0, cols - 1); i += 2 }
                'D' -> { lineFeed(); i += 2 }
                'M' -> { reverseIndex(); i += 2 }
                'E' -> { col = 0; lineFeed(); i += 2 }
                'c' -> { resetTerminal(); i += 2 }
                '(', ')' -> {
                    if (i + 2 >= input.length) {
                        pending = input.substring(i)
                        return
                    }
                    i += 3
                }
                else -> i += 2
            }
        }
    }

    private fun handleCsi(raw: String, final: Char) {
        val privateMode = raw.startsWith('?')
        val body = if (privateMode) raw.drop(1) else raw
        val params = if (body.isBlank()) emptyList() else body.split(';').map { it.toIntOrNull() ?: 0 }
        fun p(index: Int, default: Int = 1): Int = params.getOrNull(index)?.takeIf { it > 0 } ?: default

        when (final) {
            'A' -> row = (row - p(0)).coerceAtLeast(0)
            'B', 'e' -> row = (row + p(0)).coerceAtMost(rows - 1)
            'C', 'a' -> col = (col + p(0)).coerceAtMost(cols - 1)
            'D' -> col = (col - p(0)).coerceAtLeast(0)
            'E' -> { row = (row + p(0)).coerceAtMost(rows - 1); col = 0 }
            'F' -> { row = (row - p(0)).coerceAtLeast(0); col = 0 }
            'G', '`' -> col = (p(0) - 1).coerceIn(0, cols - 1)
            'd' -> row = (p(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                row = (p(0) - 1).coerceIn(0, rows - 1)
                col = (p(1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'm' -> applySgr(params)
            's' -> { savedRow = row; savedCol = col }
            'u' -> { row = savedRow.coerceIn(0, rows - 1); col = savedCol.coerceIn(0, cols - 1) }
            'r' -> {
                val top = (p(0) - 1).coerceIn(0, rows - 1)
                val bottom = ((params.getOrNull(1)?.takeIf { it > 0 } ?: rows) - 1).coerceIn(top, rows - 1)
                scrollTop = top
                scrollBottom = bottom
                row = scrollTop
                col = 0
            }
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            'P' -> deleteChars(p(0))
            '@' -> insertChars(p(0))
            'X' -> eraseChars(p(0))
            'S' -> repeat(p(0)) { scrollUp() }
            'T' -> repeat(p(0)) { scrollDown() }
            'h' -> if (privateMode && params.any { it == 47 || it == 1047 || it == 1049 }) switchAlternate(true)
            'l' -> if (privateMode && params.any { it == 47 || it == 1047 || it == 1049 }) switchAlternate(false)
        }
    }

    private fun putChar(ch: Char) {
        if (col >= cols) {
            col = 0
            lineFeed()
        }
        screen()[row][col] = Cell(ch, fg)
        col++
        if (col >= cols) {
            col = 0
            lineFeed()
        }
    }

    private fun lineFeed() {
        if (row == scrollBottom) scrollUp() else row = (row + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        if (row == scrollTop) scrollDown() else row = (row - 1).coerceAtLeast(0)
    }

    private fun scrollUp() {
        val s = screen()
        val removed = s.removeAt(scrollTop)
        s.add(scrollBottom, blankRow())
        if (!alternateActive && scrollTop == 0 && scrollBottom == rows - 1) {
            scrollback.addLast(removed.map { Cell(it.ch, it.fg) }.toMutableList())
            while (scrollback.size > maxScrollback) scrollback.removeFirst()
        }
    }

    private fun scrollDown() {
        val s = screen()
        s.removeAt(scrollBottom)
        s.add(scrollTop, blankRow())
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen()
        when (mode) {
            2, 3 -> for (y in 0 until rows) s[y] = blankRow()
            1 -> {
                for (y in 0 until row) s[y] = blankRow()
                for (x in 0..col.coerceAtMost(cols - 1)) s[row][x] = Cell()
            }
            else -> {
                for (x in col.coerceAtMost(cols - 1) until cols) s[row][x] = Cell()
                for (y in row + 1 until rows) s[y] = blankRow()
            }
        }
        if (mode == 3 && !alternateActive) scrollback.clear()
    }

    private fun eraseLine(mode: Int) {
        val line = screen()[row]
        when (mode) {
            1 -> for (x in 0..col.coerceAtMost(cols - 1)) line[x] = Cell()
            2 -> for (x in 0 until cols) line[x] = Cell()
            else -> for (x in col.coerceAtMost(cols - 1) until cols) line[x] = Cell()
        }
    }

    private fun eraseChars(count: Int) {
        val line = screen()[row]
        for (x in col until minOf(cols, col + count)) line[x] = Cell()
    }

    private fun deleteChars(count: Int) {
        val line = screen()[row]
        val n = count.coerceAtMost(cols - col)
        repeat(n) { if (col < line.size) line.removeAt(col) }
        repeat(n) { line.add(Cell()) }
    }

    private fun insertChars(count: Int) {
        val line = screen()[row]
        val n = count.coerceAtMost(cols - col)
        repeat(n) { line.add(col, Cell()) }
        while (line.size > cols) line.removeAt(line.lastIndex)
    }

    private fun insertLines(count: Int) {
        if (row !in scrollTop..scrollBottom) return
        val s = screen()
        repeat(count.coerceAtMost(scrollBottom - row + 1)) {
            s.add(row, blankRow())
            s.removeAt(scrollBottom + 1)
        }
    }

    private fun deleteLines(count: Int) {
        if (row !in scrollTop..scrollBottom) return
        val s = screen()
        repeat(count.coerceAtMost(scrollBottom - row + 1)) {
            s.removeAt(row)
            s.add(scrollBottom, blankRow())
        }
    }

    private fun applySgr(params: List<Int>) {
        val values = if (params.isEmpty()) listOf(0) else params
        var i = 0
        while (i < values.size) {
            when (val n = values[i]) {
                0 -> fg = 37
                30,31,32,33,34,35,36,37,90,91,92,93,94,95,96,97 -> fg = n
                39 -> fg = 37
                1 -> if (fg in 30..37) fg += 60
                22 -> if (fg in 90..97) fg -= 60
                38 -> {
                    // 256/true-color: aproxima para a paleta ANSI basica.
                    if (i + 2 < values.size && values[i + 1] == 5) {
                        fg = ansi256ToBasic(values[i + 2])
                        i += 2
                    } else if (i + 4 < values.size && values[i + 1] == 2) {
                        fg = rgbToBasic(values[i + 2], values[i + 3], values[i + 4])
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun ansi256ToBasic(value: Int): Int = when {
        value < 8 -> 30 + value
        value < 16 -> 90 + (value - 8)
        else -> 37
    }

    private fun rgbToBasic(r: Int, g: Int, b: Int): Int {
        if (r > 200 && g > 200 && b > 200) return 97
        if (r > g * 1.3 && r > b * 1.3) return 91
        if (g > r * 1.3 && g > b * 1.3) return 92
        if (b > r * 1.3 && b > g * 1.3) return 94
        if (r > 160 && g > 120 && b < 120) return 93
        if (r > 150 && b > 150 && g < 150) return 95
        if (g > 150 && b > 150 && r < 150) return 96
        return 37
    }

    private fun switchAlternate(enable: Boolean) {
        if (enable == alternateActive) return
        if (enable) {
            normalSavedRow = row
            normalSavedCol = col
            alternate = newScreen()
            alternateActive = true
            row = 0
            col = 0
        } else {
            alternateActive = false
            row = normalSavedRow.coerceIn(0, rows - 1)
            col = normalSavedCol.coerceIn(0, cols - 1)
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun resetTerminal() {
        normal = newScreen()
        alternate = newScreen()
        scrollback.clear()
        alternateActive = false
        row = 0
        col = 0
        fg = 37
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun renderLocked(): String {
        val allRows = ArrayList<MutableList<Cell>>()
        if (!alternateActive) allRows.addAll(scrollback)
        val s = screen()

        var lastScreenRow = row.coerceIn(0, rows - 1)
        for (y in s.indices) {
            if (s[y].any { it.ch != ' ' }) lastScreenRow = maxOf(lastScreenRow, y)
        }
        for (y in 0..lastScreenRow) allRows.add(s[y])

        val out = StringBuilder()
        var activeFg = -1
        allRows.forEachIndexed { y, line ->
            val last = line.indexOfLast { it.ch != ' ' }
            if (last >= 0) {
                for (x in 0..last) {
                    val cell = line[x]
                    if (cell.fg != activeFg) {
                        out.append("\u001B[").append(cell.fg).append('m')
                        activeFg = cell.fg
                    }
                    out.append(cell.ch)
                }
            }
            if (y != allRows.lastIndex) out.append('\n')
        }
        if (activeFg != -1) out.append("\u001B[0m")
        return out.toString()
    }
}
