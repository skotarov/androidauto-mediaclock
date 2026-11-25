package com.kotarov.autoclock.media

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import org.jsoup.nodes.Node
import java.io.InputStream

/** Parse local HTML, never fetch links, images or other resources. The caller
 * bounds input to 4 MiB because this tolerant parser builds a DOM. */
internal object HtmlBookExtractor {
    fun extract(input: InputStream, check: () -> Unit, emit: (String) -> Unit) {
        val doc = Jsoup.parse(input, null, "")
        doc.select("script,style,noscript,template,svg,iframe,object,head,[hidden],[aria-hidden=true]").remove()
        val body = doc.body()
        val blockNames = setOf("p", "div", "article", "section", "main", "li", "h1", "h2", "h3",
            "h4", "h5", "h6", "br", "tr", "td", "th", "blockquote", "pre", "hr")
        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                check()
                when (node) {
                    is TextNode -> emit(node.text())
                    is Element -> if (node.normalName() in blockNames) emit("\n")
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blockNames) emit("\n")
            }
        })
    }
}
