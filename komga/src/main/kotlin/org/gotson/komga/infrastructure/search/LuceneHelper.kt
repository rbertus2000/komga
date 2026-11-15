package org.gotson.komga.infrastructure.search

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.IndexUpgrader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.ReaderUtil
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.QueryVisitor
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.Sort
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator
import org.apache.lucene.search.uhighlight.Passage
import org.apache.lucene.search.uhighlight.PassageFormatter
import org.apache.lucene.search.uhighlight.UnifiedHighlighter
import org.apache.lucene.store.Directory
import org.apache.lucene.util.automaton.ByteRunAutomaton
import org.gotson.komga.infrastructure.mediacontainer.epub.EpubResourceText
import org.gotson.komga.interfaces.api.rest.dto.ContentSnippetDto
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.text.BreakIterator
import java.util.Locale
import java.util.function.Supplier

private val logger = KotlinLogging.logger {}

private const val MAX_RESULTS = 1000

private const val MAX_EXCERPTS = 3
private const val EXCERPT_LENGTH = 160

/**
 * Formats each passage with a match as a [Pair] of the offset of its first match in the content, and a [ContentSnippetDto] without what depends on the resource (href, chapter).
 */
private object ExcerptFormatter : PassageFormatter() {
  override fun format(
    passages: Array<Passage>,
    content: String,
  ): List<Pair<Int, ContentSnippetDto>> =
    // without a match the highlighter returns the beginning of the text
    passages.filter { it.numMatches > 0 }.map { passage ->
      val sb = StringBuilder()
      var highlight: String? = null

      fun append(
        start: Int,
        end: Int,
      ) = sb.append(HtmlUtils.htmlEscape(content.substring(start, end), Charsets.UTF_8.name()))

      var pos = passage.startOffset
      var i = 0
      while (i < passage.numMatches) {
        val start = passage.matchStarts[i]
        var end = passage.matchEnds[i]
        // overlapping matches are merged
        while (i + 1 < passage.numMatches && passage.matchStarts[i + 1] < end) end = maxOf(end, passage.matchEnds[++i])
        end = minOf(end, passage.endOffset)

        append(pos, start)
        sb.append("<mark>")
        append(start, end)
        sb.append("</mark>")
        if (highlight == null) highlight = content.substring(start, end)
        pos = end
        i++
      }
      append(pos, maxOf(pos, passage.endOffset))

      // the same text can occur several times in the content: count the occurrences before the match
      // ponytail: scans the content from its start for each passage. Carry the count from one passage to the next if listing all the excerpts of a frequent word gets slow.
      val firstMatch = passage.matchStarts[0]
      var occurrence = 0
      var index = content.indexOf(highlight!!)
      while (index in 0 until firstMatch) {
        occurrence++
        index = content.indexOf(highlight, index + highlight.length)
      }

      firstMatch to
        ContentSnippetDto(
          text = sb.toString().trim(),
          href = "",
          progression = firstMatch.toFloat() / content.length,
          highlight = highlight,
          occurrence = occurrence,
          matchCount = passage.numMatches,
        )
    }
}

@Component
class LuceneHelper(
  private val directory: Directory,
  private val searchAnalyzer: Analyzer,
  private val indexAnalyzer: Analyzer,
  private val indexWriter: IndexWriter,
  private val searcherManager: SearcherManager,
  private val luceneCommitter: LuceneCommitter,
) {
  fun indexExists(): Boolean = DirectoryReader.indexExists(directory)

  fun setIndexVersion(version: Int) {
    val doc =
      Document().apply {
        add(StringField("index_version", version.toString(), Field.Store.YES))
        add(StringField("type", "index_version", Field.Store.NO))
      }
    updateDocument(Term("type", "index_version"), doc)
    logger.info { "Lucene index version: ${getIndexVersion()}" }
  }

  fun getIndexVersion(): Int {
    val searcher = searcherManager.acquire()
    val topDocs = searcher.search(TermQuery(Term("type", "index_version")), 1)
    return topDocs.scoreDocs
      .map { searcher.storedFields().document(it.doc)["index_version"] }
      .firstOrNull()
      ?.toIntOrNull() ?: 1
  }

  fun searchEntitiesIds(
    searchTerm: String?,
    entity: LuceneEntity,
  ): List<String>? =
    if (searchTerm.isNullOrBlank()) {
      null
    } else {
      try {
        val fieldsQuery =
          if (entity == LuceneEntity.BookContent)
            parseContentQuery(searchTerm)
          else
            MultiFieldQueryParser(entity.defaultFields, searchAnalyzer).apply { defaultOperator = QueryParser.Operator.AND }.parse("$searchTerm *:*")

        if (fieldsQuery == null) {
          emptyList()
        } else {
          val typeQuery = TermQuery(Term(LuceneEntity.TYPE, entity.type))

          val booleanQuery =
            BooleanQuery
              .Builder()
              .add(fieldsQuery, BooleanClause.Occur.MUST)
              .add(typeQuery, BooleanClause.Occur.MUST)
              .build()

          val searcher = searcherManager.acquire()
          val topDocs = searcher.search(booleanQuery, MAX_RESULTS)
          topDocs.scoreDocs.mapNotNull { searcher.storedId(it.doc, entity) }
        }
      } catch (_: ParseException) {
        emptyList()
      } catch (e: Exception) {
        logger.error(e) { "Error fetching entities from index" }
        emptyList()
      }
    }

  /**
   * Terms without a field apply to the content, terms with a field (author:name) apply to the metadata of the book, which content documents carry.
   *
   * @return null if the [searchTerm] does not look for anything in the content. Such a search is answered by the book documents alone:
   * here it would match every resource of the books (author:name), or the resources where a term is absent rather than the books (-term).
   */
  private fun parseContentQuery(searchTerm: String): Query? {
    val field = LuceneEntity.BookContent.defaultFields.first()
    val query = QueryParser(field, searchAnalyzer).apply { defaultOperator = QueryParser.Operator.AND }.parse(searchTerm)

    var searchesContent = false
    query.visit(
      object : QueryVisitor() {
        override fun acceptField(name: String?) = name == field

        override fun getSubVisitor(
          occur: BooleanClause.Occur,
          parent: Query,
        ): QueryVisitor = if (occur == BooleanClause.Occur.MUST_NOT) EMPTY_VISITOR else this

        override fun consumeTerms(
          query: Query,
          vararg terms: Term,
        ) {
          searchesContent = true
        }

        override fun consumeTermsMatching(
          query: Query,
          field: String,
          automaton: Supplier<ByteRunAutomaton>,
        ) {
          searchesContent = true
        }

        override fun visitLeaf(query: Query) {
          // other queries on the field, like ranges
          if (query !is MatchAllDocsQuery) searchesContent = true
        }
      },
    )
    return query.takeIf { searchesContent }
  }

  /**
   * @return the resources of a book as they were indexed, in reading order
   */
  fun getBookContent(bookId: String): List<EpubResourceText> {
    val searcher = searcherManager.acquire()
    return try {
      val query = TermQuery(Term(LuceneEntity.BookContent.id, bookId))
      searcher
        .search(query, searcher.count(query).coerceAtLeast(1), Sort.INDEXORDER)
        .scoreDocs
        .mapNotNull { searcher.contentResource(it.doc, withText = true) }
    } finally {
      searcherManager.release(searcher)
    }
  }

  /**
   * @param withText the text is only loaded on demand, as it can be large
   * @return the resource a content document was built from, null if the document was indexed by an older version
   */
  private fun IndexSearcher.contentResource(
    docId: Int,
    withText: Boolean,
  ): EpubResourceText? {
    val textField = LuceneEntity.BookContent.defaultFields.first()
    val doc = storedFields().document(docId, setOfNotNull(LuceneEntity.CONTENT_HREF, LuceneEntity.CONTENT_CHAPTER, textField.takeIf { withText }))
    return doc[LuceneEntity.CONTENT_HREF]?.let { href ->
      EpubResourceText(
        href,
        doc[textField].orEmpty(),
        doc.getValues(LuceneEntity.CONTENT_CHAPTER).map { it.substringBefore('\t').toInt() to it.substringAfter('\t') },
      )
    }
  }

  /**
   * Runs the [block] with the query of the [searchTerm] on the content.
   *
   * @return the [default] if the [searchTerm] does not look for anything in the content, or on error
   */
  private fun <T> searchContent(
    searchTerm: String?,
    default: T,
    block: (searcher: IndexSearcher, contentQuery: Query) -> T,
  ): T {
    if (searchTerm.isNullOrBlank()) return default
    val searcher = searcherManager.acquire()
    return try {
      parseContentQuery(searchTerm)?.let { block(searcher, it) } ?: default
    } catch (_: ParseException) {
      default
    } catch (e: Exception) {
      logger.error(e) { "Error searching book content" }
      default
    } finally {
      searcherManager.release(searcher)
    }
  }

  /**
   * Restricts the [contentQuery] to the resources of a book.
   */
  private fun bookContentQuery(
    contentQuery: Query,
    bookId: String,
  ): Query =
    BooleanQuery
      .Builder()
      .add(contentQuery, BooleanClause.Occur.MUST)
      .add(TermQuery(Term(LuceneEntity.BookContent.id, bookId)), BooleanClause.Occur.FILTER)
      .build()

  /**
   * @return the number of matches of the [searchTerm] in the content of each book that has some, by book ID.
   */
  fun countBookContentMatches(
    searchTerm: String?,
    bookIds: Collection<String>,
  ): Map<String, Int> =
    searchContent(searchTerm, emptyMap()) { searcher, contentQuery ->
      val field = LuceneEntity.BookContent.defaultFields.first()
      // counted from the positions in the index, without loading the text
      val weight = searcher.createWeight(searcher.rewrite(contentQuery), ScoreMode.COMPLETE_NO_SCORES, 1f)
      val leaves = searcher.indexReader.leaves()

      bookIds
        .associateWith { bookId ->
          searcher.search(bookContentQuery(contentQuery, bookId), MAX_RESULTS).scoreDocs.sumOf { scoreDoc ->
            val leaf = leaves[ReaderUtil.subIndex(scoreDoc.doc, leaves)]
            val matches = weight.matches(leaf, scoreDoc.doc - leaf.docBase)?.getMatches(field)
            var count = 0
            while (matches?.next() == true) count++
            count
          }
        }.filterValues { it > 0 }
    }

  /**
   * @param all if false, only the best excerpts of each book are returned. If true, all the excerpts are returned, in reading order.
   * @return excerpts of the book content matching the [searchTerm], by book ID.
   */
  fun highlightBookContent(
    searchTerm: String?,
    bookIds: Collection<String>,
    all: Boolean = false,
  ): Map<String, List<ContentSnippetDto>> =
    searchContent(searchTerm, emptyMap()) { searcher, contentQuery ->
      // ponytail: "all" stops at 1000 excerpts per book. Paginate the excerpts if a search needs more.
      val maxExcerpts = if (all) MAX_RESULTS else MAX_EXCERPTS
      val field = LuceneEntity.BookContent.defaultFields.first()
      val highlighter =
        object : UnifiedHighlighter(
          builder(searcher, indexAnalyzer)
            // the default only looks at the first 10000 characters
            .withMaxLength(Int.MAX_VALUE - 1)
            .withBreakIterator { LengthGoalBreakIterator.createClosestToLength(BreakIterator.getWordInstance(Locale.ROOT), EXCERPT_LENGTH, 0.5f) }
            .withFormatter(ExcerptFormatter),
        ) {
          // the objects built by the formatter are only available to subclasses
          fun excerpts(docIds: IntArray) = highlightFieldsAsObjects(arrayOf(field), contentQuery, docIds, intArrayOf(maxExcerpts))[field].orEmpty()
        }

      bookIds
        .mapNotNull { bookId ->
          val query = bookContentQuery(contentQuery, bookId)
          // all: the resources of a book are indexed together, in reading order.
          // Else the best resources of the book, the excerpts of the best one come first.
          val docIds =
            (if (all) searcher.search(query, MAX_RESULTS, Sort.INDEXORDER) else searcher.search(query, MAX_EXCERPTS))
              .scoreDocs
              .map { it.doc }
              .toIntArray()
          if (docIds.isEmpty()) return@mapNotNull null
          val excerpts = highlighter.excerpts(docIds)

          docIds
            .flatMapIndexed { index, docId ->
              val resource = searcher.contentResource(docId, withText = false)
              @Suppress("UNCHECKED_CAST")
              (excerpts.getOrNull(index) as? List<Pair<Int, ContentSnippetDto>>)
                .orEmpty()
                // documents indexed by an older version cannot be located
                .mapNotNull { (offset, excerpt) -> resource?.let { excerpt.copy(href = it.href, chapter = it.chapterAt(offset)) } }
            }.take(maxExcerpts)
            .ifEmpty { null }
            ?.let { bookId to it }
        }.toMap()
    }

  /**
   * Reads the ID only, and stops there: other stored fields can be large.
   */
  private fun IndexSearcher.storedId(
    docId: Int,
    entity: LuceneEntity,
  ): String? {
    var id: String? = null
    storedFields().document(
      docId,
      object : StoredFieldVisitor() {
        override fun needsField(fieldInfo: FieldInfo): Status =
          when {
            id != null -> Status.STOP
            fieldInfo.name == entity.id -> Status.YES
            else -> Status.NO
          }

        override fun stringField(
          fieldInfo: FieldInfo,
          value: String,
        ) {
          id = value
        }
      },
    )
    return id
  }

  /**
   * Books matching on metadata come first, followed by the books matching on content.
   *
   * ponytail: content matches are the 1000 best resources, so less than 1000 books when books match in several resources.
   * Group the content documents by book (lucene-grouping) if the tail of the results matters.
   */
  fun searchBookIds(searchTerm: String?): List<String>? =
    searchEntitiesIds(searchTerm, LuceneEntity.Book)?.let { ids ->
      (ids + searchEntitiesIds(searchTerm, LuceneEntity.BookContent).orEmpty()).distinct().take(MAX_RESULTS)
    }

  fun upgradeIndex() {
    IndexUpgrader(directory, IndexWriterConfig(indexAnalyzer), true).upgrade()
    logger.info { "Lucene index upgraded" }
  }

  fun addDocument(doc: Document) {
    indexWriter.addDocument(doc)
    luceneCommitter.commitAndMaybeRefresh()
  }

  fun addDocuments(docs: Iterable<Document>) {
    indexWriter.addDocuments(docs)
    luceneCommitter.commitAndMaybeRefresh()
  }

  fun updateDocument(
    term: Term,
    doc: Document,
  ) {
    indexWriter.updateDocument(term, doc)
    luceneCommitter.commitAndMaybeRefresh()
  }

  /**
   * Replaces all the documents matching the [term] by [docs].
   */
  fun updateDocuments(
    term: Term,
    docs: Collection<Document>,
  ) {
    if (docs.isEmpty()) indexWriter.deleteDocuments(term) else indexWriter.updateDocuments(term, docs)
    luceneCommitter.commitAndMaybeRefresh()
  }

  fun deleteDocuments(term: Term) {
    indexWriter.deleteDocuments(term)
    luceneCommitter.commitAndMaybeRefresh()
  }
}
