package com.univerflow.kaznu.preview

/**
 * Text of PowerPoint 97–2003 (.ppt) and Excel 97–2003 (.xls) files for the search: slide and note text
 * atoms, and cells in sheet order with their shared strings. Encrypted workbooks give no text.
 */
internal object LegacyOffice {
    fun pptText(file: CompoundFile, checkActive: () -> Unit = {}): String {
        val stream = file.stream("PowerPoint Document") ?: throw UnsupportedDocumentException("No PowerPoint stream")
        val out = StringBuilder()
        PowerPoint(LittleEndian(stream), out, checkActive).walk(0, stream.size, 0)
        return out.toString().replace('\r', '\n').replace('\u000B', '\n').replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Visible sheets as the table view shows them ([SheetView.text]); an encrypted workbook gives no text. */
    fun xlsText(file: CompoundFile, checkActive: () -> Unit = {}): String =
        try { SheetView.text(XlsReader.read(file, checkActive)) } catch (_: UnsupportedDocumentException) { "" }

    private class PowerPoint(private val bytes: LittleEndian, private val out: StringBuilder, private val checkActive: () -> Unit) {
        private var records = 0

        fun walk(start: Int, end: Int, depth: Int) {
            var at = start
            while (at + 8 <= end && depth < 16) {
                if (++records % 4096 == 0) checkActive()
                val info = bytes.u16(at)
                val type = bytes.u16(at + 2)
                val length = bytes.i32(at + 4)
                if (length < 0 || at.toLong() + 8 + length > end) return
                val body = at + 8
                when {
                    // Masters hold placeholder prompts ("Click to edit…"), not the presentation's text.
                    type == MAIN_MASTER || type == HANDOUT || (type == SLIDE_LIST && info ushr 4 == 1) || notesMaster(type, body, length) -> Unit
                    info and 0xF == 0xF -> walk(body, body + length, depth + 1)
                    type == TEXT_CHARS -> line(bytes.utf16(body, length / 2))
                    type == TEXT_BYTES -> line(String(bytes.slice(body, length), Charsets.ISO_8859_1))
                }
                if (out.length > TextExtract.LIMIT) return
                at = body + length
            }
        }

        /** "*" is the placeholder of a slide-number or date field, not text. */
        private fun line(text: String) { if (text.isNotBlank() && text.trim() != "*") out.append(text).append('\n') }

        /** The notes master is a notes container whose NotesAtom points at no slide (slide ids start at 0x100). */
        private fun notesMaster(type: Int, body: Int, length: Int) =
            type == NOTES && length >= 12 && bytes.u16(body + 2) == NOTES_ATOM && bytes.i32(body + 8) < 0x100

        companion object {
            const val NOTES = 0x03F0
            const val NOTES_ATOM = 0x03F1
            const val HANDOUT = 0x0FC9
            const val MAIN_MASTER = 0x03F8
            const val SLIDE_LIST = 0x0FF0
            const val TEXT_CHARS = 0x0FA0
            const val TEXT_BYTES = 0x0FA8
        }
    }
}
