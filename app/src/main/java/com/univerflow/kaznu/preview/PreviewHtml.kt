package com.univerflow.kaznu.preview

import java.io.InputStream
import java.util.Locale

/** The viewer's private origin. Every request to it is answered by the app; nothing reaches the network. */
object PreviewHtml {
    const val ORIGIN = "https://univer-files.local"
    const val INDEX = "$ORIGIN/index.html"
    /** The copy of the open document that is printed ([Style.print]). */
    const val PRINT = "$ORIGIN/print.html"
    const val FILE = "$ORIGIN/file"
    fun pageUrl(index: Int) = "$ORIGIN/page/$index.png"
    /** A picture of the document kept by [FileMedia], by its content name. */
    fun mediaUrl(name: String) = "$ORIGIN/media/$name"
    /** Table [index] of the open document on its own screen ([DocumentTables]). */
    fun tableUrl(index: Int) = "$ORIGIN/table/$index"

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        value.forEach { char ->
            when (char) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> if (char.code < 0x20 && char != '\n' && char != '\t') out.append(' ') else out.append(char)
            }
        }
        return out.toString()
    }

    /**
     * How a page is drawn: [zoom] below 100 lays it out wider with smaller text, [a4] caps reading pages at an A4
     * width, [dark] follows the theme, and a [print] copy loads every picture with the page so printing waits for them.
     */
    class Style(val zoom: Int = 100, val a4: Boolean = true, val dark: Boolean = false, val print: Boolean = false)

    const val MIN_ZOOM = 25
    const val A4_WIDTH = 794
    /** Layout zoom steps of the viewer, as in a browser. */
    val ZOOM_STEPS = intArrayOf(25, 33, 50, 67, 75, 80, 90, 100)

    /** A static page: JavaScript stays disabled and the policy allows only the viewer's own images. */
    fun document(title: String, language: String, body: String, css: String = "", style: Style = Style()) =
        start(title, language, css, style) + body + END

    /**
     * The page up to its body. An [overview] page sets no initial or minimum scale, so WebView's overview mode opens it
     * zoomed out to its full width.
     */
    private fun start(title: String, language: String, css: String, style: Style, overview: Boolean = false): String {
        val zoom = style.zoom.coerceIn(MIN_ZOOM, 100)
        // CSS zoom on the root lays the page out in proportionally more CSS pixels: text gets smaller and lines longer.
        val scale = (if (zoom < 100) "html{zoom:${zoom / 100.0}}" else "") + if (style.print) ".pic>span,.page .leaf{content-visibility:visible}" else ""
        val viewport = if (overview) "width=device-width,maximum-scale=6" else "width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=6"
        return "<!doctype html><html lang=\"${escape(language)}\"${if (style.dark) " class=\"dark\"" else ""}><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"$viewport\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src $ORIGIN data:; style-src 'unsafe-inline'\">" +
            "<title>${escape(title)}</title><style>$BASE_CSS$css$scale</style></head><body>"
    }

    private const val END = "</body></html>"

    private fun notice(text: String?) = text?.let { "<p class=\"notice\">${escape(it)}</p>" } ?: ""

    fun pdf(title: String, language: String, sizes: List<Pair<Int, Int>>, style: Style = Style(), notice: String? = null, pageLabel: (Int) -> String) =
        start(title, language, PDF_CSS, style) + notice(notice) +
            sizes.mapIndexed { index, (width, height) -> pdfPage(index, width, height, pageLabel(index + 1)) }.joinToString("") + END

    /**
     * The same page as [pdf], written while WebView reads it: [size] is asked page by page, so the first pages show before
     * the sizes of the last ones are read.
     */
    fun pdfStream(title: String, language: String, count: Int, size: (Int) -> Pair<Int, Int>?, style: Style = Style(), notice: String? = null,
        pageLabel: (Int) -> String): InputStream = PartStream(start(title, language, PDF_CSS, style) + notice(notice), count,
        { index -> size(index)?.let { (width, height) -> pdfPage(index, width, height, pageLabel(index + 1)) } }, END)

    private val SIZED = Regex("#(\\d{1,5})x(\\d{1,5})$")

    /**
     * A document picture. With its pixel size in the address it is a background in a box of its proportions, fetched
     * only near the screen; without one, a plain <img>. [alt] names it for screen readers.
     */
    fun picture(src: String, alt: String = ""): String {
        val size = SIZED.find(src) ?: return "<img alt=\"${escape(alt)}\" loading=\"lazy\" src=\"${escape(src)}\">"
        val width = size.groupValues[1].toInt().coerceAtLeast(1)
        val ratio = String.format(Locale.ROOT, "%.3f", size.groupValues[2].toInt() * 100.0 / width)
        val label = if (alt.isBlank()) "" else " role=\"img\" aria-label=\"${escape(alt)}\""
        return "<span class=\"pic\" style=\"width:${width}px\"><span style=\"padding-top:$ratio%\"><span$label " +
            "style=\"background-image:url(${escape(src.substring(0, size.range.first))})\"></span></span></span>"
    }

    /**
     * A PDF page as a background in a box of its proportions, skipped off screen (content-visibility): lazy images
     * need JavaScript, so this is how WebView fetches the page only near the screen.
     */
    private fun pdfPage(index: Int, width: Int, height: Int, label: String): String {
        val ratio = String.format(Locale.ROOT, "%.3f", height * 100.0 / width.coerceAtLeast(1))
        val text = escape(label)
        return "<figure class=\"page\"><div class=\"leaf\" style=\"padding-top:$ratio%\"><div role=\"img\" aria-label=\"$text\" " +
            "style=\"background-image:url(${pageUrl(index)})\"></div></div><figcaption>$text</figcaption></figure>"
    }

    fun image(title: String, language: String, style: Style = Style()) = document(title, language,
        "<div class=\"image\"><img src=\"$FILE\" alt=\"${escape(title)}\"></div>", IMAGE_CSS, style)

    fun text(title: String, language: String, notice: String?, text: String, style: Style = Style()) = document(title, language,
        notice(notice) + "<pre>${escape(text)}</pre>", TEXT_CSS, style)

    /**
     * Word documents and web pages: one reading column ("paper") that fills the screen and, with [Style.a4], grows no
     * wider than an A4 sheet when the page is zoomed out.
     */
    fun docx(title: String, language: String, notice: String?, body: String, style: Style = Style()) = document(title, language,
        notice(notice) + "<article class=\"paper\">$body</article>",
        DOCX_CSS + if (style.a4) ".paper,.notice{max-width:${A4_WIDTH}px;margin-left:auto;margin-right:auto;box-sizing:border-box}" else "", style)

    /** A workbook drawn by [SheetHtml]. */
    fun sheet(title: String, language: String, body: String, style: Style = Style()) = document(title, language, body, SHEET_CSS, style)

    /** A presentation drawn by [SlideHtml]. */
    fun slides(title: String, language: String, body: String, style: Style = Style()) = document(title, language, body, SLIDES_CSS, style)

    /**
     * One table or sheet on its own screen ([DocumentTables.markup]), at full width and opened zoomed out. Word tables
     * and sheets keep their widths; a web table is at most 1200 px wide. The layout zoom does not apply.
     */
    fun table(title: String, language: String, markup: String, style: Style = Style()): String {
        val grid = markup.startsWith("<div class=\"grid\"")
        val fixed = grid || markup.indexOf("<table").let { it >= 0 && markup.startsWith("<table style=\"min-width:", it) }
        val body = if (grid) "<div class=\"solo\">$markup</div>" else "<article class=\"paper solo${if (fixed) "" else " fluid"}\">$markup</article>"
        return start(title, language, (if (grid) SHEET_CSS else DOCX_CSS) + SOLO_CSS, Style(dark = style.dark), overview = true) + body + END
    }

    /** Material "open in full", drawn in the link's colour through a mask. */
    private const val EXPAND_ICON = "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E" +
        "%3Cpath d='M21 11V3h-8l3.29 3.29-10 10L3 13v8h8l-3.29-3.29 10-10z'/%3E%3C/svg%3E\") center/contain no-repeat"
    // Colours are theme tokens: light by default, dark under <html class="dark">, and ink on white paper when printed.
    private const val BASE_CSS = ":root{--bg:#F1F4F9;--ink:#16283B;--muted:#4B5B6C;--paper:#FFFFFF;--line:#D5DDE6;--grid:#C4CED9;--head:#EEF2F7;" +
        "--notice:#DCE6FA;--notice-ink:#111C2B;--link:#1B5FA6;--chip:#E2E9F3;--frame:#E0E6EE;color-scheme:light}" +
        ":root.dark{--bg:#0F151C;--ink:#E3E9F0;--muted:#A6B4C3;--paper:#18212B;--line:#2E3B49;--grid:#354456;--head:#212C38;" +
        "--notice:#1D3352;--notice-ink:#DCE7F7;--link:#8EBBF0;--chip:#25313F;--frame:#0B1016;color-scheme:dark}" +
        "@media print{:root,:root.dark{--bg:#fff;--ink:#000;--muted:#333;--paper:#fff;--line:#999;--grid:#999;--head:#eee;--notice:#fff;" +
        "--notice-ink:#000;--link:#1B5FA6;--chip:#eee;--frame:#fff}}" +
        "html{-webkit-text-size-adjust:100%}" +
        "body{margin:0;padding:16px;background:var(--bg);color:var(--ink);font:16px/1.55 system-ui,-apple-system,Roboto,sans-serif;overflow-wrap:anywhere}" +
        ".notice{margin:0 0 12px;padding:10px 14px;border-radius:12px;background:var(--notice);color:var(--notice-ink);font-size:14px}" +
        ".empty{color:var(--muted)}a[href]{color:var(--link)}" +
        ".open-table{margin:8px 0 6px;text-align:end}.open-table a{display:inline-flex;align-items:center;gap:8px;box-sizing:border-box;" +
        "min-height:40px;padding:8px 16px;border-radius:20px;background:var(--chip);color:var(--ink);font-size:14px;line-height:1.2;text-decoration:none}" +
        ".open-table a::before{content:\"\";flex:none;width:18px;height:18px;background:currentColor;-webkit-mask:$EXPAND_ICON;mask:$EXPAND_ICON}" +
        // A picture fetched near the screen (picture()): as wide as its pixels, never wider than its column.
        ".pic{display:inline-block;max-width:100%}.pic>span{display:block;position:relative;height:0;content-visibility:auto}" +
        ".pic>span>span{position:absolute;top:0;left:0;width:100%;height:100%;background:center/contain no-repeat;" +
        "-webkit-print-color-adjust:exact;print-color-adjust:exact}:root.dark .pic>span{background-color:#fff}" +
        // On paper a table is printed whole, without its scroll frame or the link to its own screen.
        "@media print{.open-table{display:none}html .paper .table,html .grid{overflow:visible;max-height:none}}"
    private const val PDF_CSS = "body{background:var(--frame);padding:12px 8px}" +
        ".page{margin:0 auto 16px;max-width:960px}" +
        ".page .leaf{position:relative;height:0;content-visibility:auto;background:#fff;border-radius:4px;box-shadow:0 1px 3px #0003}" +
        ".page .leaf>div{position:absolute;top:0;left:0;width:100%;height:100%;border-radius:4px;background:center/100% 100% no-repeat}" +
        ".page figcaption{margin-top:6px;text-align:center;font-size:13px;color:var(--muted)}"
    private const val SOLO_CSS = "body{padding:8px}.solo{box-sizing:border-box;width:max-content;min-width:100%}.solo.fluid{max-width:1200px}" +
        ".paper.solo{padding:12px}.solo .table{overflow:visible;max-height:none;margin:0;border:0;border-radius:0}" +
        ".solo .grid{overflow:visible;max-height:none;max-width:none}"
    private const val IMAGE_CSS = "body{padding:0;background:#16283B}:root.dark body{background:#05080B}" +
        ".image{min-height:100vh;display:flex;align-items:center;justify-content:center}.image img{display:block;max-width:100%;height:auto}"
    private const val TEXT_CSS = "pre{margin:0;padding:16px;background:var(--paper);border:1px solid var(--line);border-radius:12px;" +
        "white-space:pre-wrap;word-break:break-word;font:14px/1.5 ui-monospace,Menlo,Consolas,monospace}"
    private const val DOCX_CSS = ".paper{box-sizing:border-box;margin:0 auto;padding:20px 16px;background:var(--paper);" +
        "border:1px solid var(--line);border-radius:12px}.paper p{margin:0 0 8px}.paper p.empty{min-height:1em}" +
        ".paper h1,.paper h2,.paper h3,.paper h4,.paper h5,.paper h6{margin:16px 0 8px;line-height:1.3}.paper .li{padding-inline-start:20px;text-indent:-14px}" +
        ".paper .tab{display:inline-block;width:2em}.paper img{max-width:100%;height:auto}:root.dark .paper img{background:#fff}" +
        ".paper .table{overflow:auto;overscroll-behavior-x:contain;margin:8px 0 12px;max-height:85vh;border:1px solid var(--line);border-radius:8px}" +
        ".paper table{border-collapse:collapse;table-layout:fixed}" +
        ".paper td,.paper th{border:1px solid var(--grid);padding:6px 8px;vertical-align:top;overflow-wrap:break-word;word-break:normal}" +
        ".paper th{background:var(--head);text-align:start}.paper caption{padding:6px 8px;text-align:start;font-weight:600}" +
        ".paper .table:focus-visible{outline:3px solid var(--link);outline-offset:2px}" +
        ".word-equation{display:inline-block;white-space:nowrap;overflow-wrap:normal}.word-fraction{display:inline-flex;vertical-align:middle;flex-direction:column;text-align:center;margin:0 3px}" +
        ".word-fraction>span:first-child{border-bottom:1px solid currentColor;padding:0 3px}.word-fraction>span:last-child{padding:0 3px}" +
        ".paper td p{margin:0 0 4px}.paper ul,.paper ol{margin:0 0 8px;padding-inline-start:24px}.paper li{margin:0 0 4px}" +
        ".paper blockquote{margin:0 0 8px;padding:4px 0 4px 14px;border-inline-start:3px solid var(--line);color:var(--muted)}" +
        ".paper pre{margin:0 0 8px;padding:10px 12px;overflow:auto;background:var(--head);border-radius:8px;white-space:pre-wrap;" +
        "font:14px/1.5 ui-monospace,Menlo,Consolas,monospace}" +
        ".paper hr{border:0;border-top:1px solid var(--line);margin:12px 0}.paper dt{font-weight:600}.paper dd{margin:0 0 8px 20px}" +
        ".paper .alt{color:var(--muted);font-style:italic}.paper summary{cursor:pointer}"
    private const val SHEET_CSS = "body{padding:12px 8px}" +
        ".sheets{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 12px}.sheets a{display:inline-block;padding:8px 14px;border-radius:18px;" +
        "background:var(--chip);color:var(--ink);text-decoration:none;font-size:14px;line-height:1.25}" +
        ".sheet{margin:0 0 20px}.sheet h2{margin:0 0 8px;font-size:17px;line-height:1.3}" +
        ".grid{box-sizing:border-box;width:fit-content;max-width:100%;overflow:auto;overscroll-behavior-x:contain;max-height:80vh;" +
        "background:var(--paper);border:1px solid var(--line);border-radius:8px}" +
        ".grid:focus-visible{outline:3px solid var(--link);outline-offset:2px}" +
        ".grid table{border-collapse:separate;border-spacing:0;table-layout:fixed;font-size:14px;line-height:1.35}" +
        ".grid td,.grid th{border-right:1px solid var(--grid);border-bottom:1px solid var(--grid);padding:4px 6px;vertical-align:bottom;" +
        "white-space:pre-wrap;overflow-wrap:break-word;word-break:normal;text-align:start}" +
        ".grid th{position:sticky;background:var(--head);color:var(--muted);font-weight:500;font-size:12px;text-align:center;vertical-align:middle;white-space:nowrap}" +
        ".grid thead th{top:0;z-index:2}.grid tbody th{left:0;z-index:1}.grid thead th.corner{left:0;z-index:3}" +
        ".grid .al{text-align:start}.grid .ac{text-align:center}.grid .ar{text-align:end;font-variant-numeric:tabular-nums}.grid .aj{text-align:justify}" +
        ".grid .vt{vertical-align:top}.grid .vm{vertical-align:middle}.grid .b{font-weight:700}.grid .i{font-style:italic}" +
        ".grid .u{text-decoration:underline}.grid .s{text-decoration:line-through}.grid .u.s{text-decoration:underline line-through}"
    private val SLIDES_CSS = "body{padding:12px}" +
        ".slide{box-sizing:border-box;max-width:960px;margin:0 auto 16px;padding:16px 18px 18px;background:var(--paper);border:1px solid var(--line);border-radius:14px}" +
        ".slide .number{margin:0 0 8px;color:var(--muted);font-size:13px}.slide h2{margin:0 0 10px;font-size:21px;line-height:1.3}" +
        ".slide p{margin:0 0 8px}.slide ul{margin:0 0 8px;padding-inline-start:22px}.slide li{margin:0 0 6px}" +
        (1..8).joinToString("") { ".slide .l$it{margin-inline-start:${it * 22}px}" } +
        ".slide figure{margin:12px 0}.slide img{display:block;max-width:100%;height:auto;margin:0 auto;border-radius:6px}:root.dark .slide img{background:#fff}" +
        ".slide .pic{display:block;margin:0 auto;border-radius:6px;overflow:hidden}" +
        ".notes{margin-top:12px;padding:10px 12px;border-radius:10px;background:var(--head)}.notes summary{cursor:pointer;color:var(--muted);font-size:14px}" +
        ".notes p{margin:8px 0 0}"
}
