/*
 * Copyright © 2015 Typesafe, Inc. <http://www.typesafe.com>
 */

package com.typesafe.paradox

import com.typesafe.paradox.markdown.{ Breadcrumbs, Page, Path, Reader, TableOfContents, Writer }
import com.typesafe.paradox.template.PageTemplate
import com.typesafe.paradox.tree.Tree.{ Forest, Location }
import java.io.File
import org.parboiled.common.FileUtils
import org.pegdown.ast.{ ActiveLinkNode, ExpLinkNode, Node, RootNode }
import org.stringtemplate.v4.STErrorListener
import scala.annotation.tailrec

/**
 * Markdown site processor.
 */
class ParadoxProcessor(reader: Reader = new Reader, writer: Writer = new Writer) {

  /**
   * Process all mappings to build the site.
   */
  def process(mappings: Seq[(File, String)],
              outputDirectory: File,
              sourceSuffix: String,
              targetSuffix: String,
              properties: Map[String, String],
              navigationDepth: Int,
              template: PageTemplate,
              templatePath: String,
              errorListener: STErrorListener): Seq[(File, String)] = {
    @tailrec
    def render(location: Option[Location[Page]], rendered: Seq[(File, String)] = Seq.empty): Seq[(File, String)] = location match {
      case Some(loc) =>
        val page = loc.tree.label
        val writerContext = Writer.Context(loc, sourceSuffix, targetSuffix, properties)
        val toc = new TableOfContents(pages = true, headers = false, ordered = false, maxDepth = navigationDepth)
        val pageContext = PageContents(loc, templatePath, writer, writerContext, toc)
        val outputFile = new File(outputDirectory, page.path)
        outputFile.getParentFile.mkdirs
        template.write(pageContext, outputFile, errorListener)
        render(loc.next, rendered :+ (outputFile, page.path))
      case None => rendered
    }
    val pages = parsePages(mappings, Path.replaceSuffix(sourceSuffix, targetSuffix))
    pages flatMap { root => render(Some(root.location)) }
  }

  /**
   * Default template contents for a markdown page at a particular location.
   */
  case class PageContents(loc: Location[Page], templatePath: String, writer: Writer, context: Writer.Context, toc: TableOfContents) extends PageTemplate.Contents {
    import scala.collection.JavaConverters._

    private lazy val page = loc.tree.label

    lazy val getTitle = page.title
    lazy val getContent = writer.write(page.markdown, context)

    lazy val getTemplate = page.base + templatePath
    lazy val getBase = page.base
    lazy val getHome = link(Some(loc.root))
    lazy val getPrev = link(loc.prev)
    lazy val getNext = link(loc.next)
    lazy val getBreadcrumbs = writer.writeFragment(Breadcrumbs.markdown(loc.path), context)
    lazy val getNavigation = writer.writeFragment(toc.root(loc), context)

    lazy val getProperties = context.properties.asJava

    private def link(location: Option[Location[Page]]): PageTemplate.Link = PageLink(location, page, writer, context)
  }

  /**
   * Default template links, rendered to just a relative uri and HTML for the link.
   */
  case class PageLink(location: Option[Location[Page]], current: Page, writer: Writer, context: Writer.Context) extends PageTemplate.Link {
    lazy val getHref: String = location.map(href).orNull
    lazy val getHtml: String = location.map(link).orNull

    private def link(location: Location[Page]): String = {
      val node = if (location.tree.label.path == current.path)
        new ActiveLinkNode(href(location), location.tree.label.label)
      else
        new ExpLinkNode("", href(location), location.tree.label.label)
      writer.writeFragment(node, context)
    }

    private def href(location: Location[Page]): String = current.base + location.tree.label.path
  }

  /**
   * Parse markdown files (with paths) into a forest of linked pages.
   */
  def parsePages(mappings: Seq[(File, String)], convertPath: String => String): Forest[Page] = {
    Page.forest(parseMarkdown(mappings), convertPath)
  }

  /**
   * Parse markdown files into pegdown AST.
   */
  def parseMarkdown(mappings: Seq[(File, String)]): Seq[(String, RootNode)] = {
    mappings map { case (file, path) => (normalizePath(path), reader.read(FileUtils.readAllChars(file))) }
  }

  /**
   * Normalize path to '/' separator.
   */
  def normalizePath(path: String, separator: Char = java.io.File.separatorChar): String = {
    if (separator == '/') path else path.replace(separator, '/')
  }

}