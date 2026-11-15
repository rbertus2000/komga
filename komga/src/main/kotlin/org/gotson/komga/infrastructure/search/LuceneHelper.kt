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
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator
import org.apache.lucene.search.uhighlight.UnifiedHighlighter
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.text.BreakIterator
import java.util.Locale

private val logger = KotlinLogging.logger {}

private const val MAX_RESULTS = 1000

private const val MAX_EXCERPTS = 3
private const val EXCERPT_LENGTH = 160

// markers that cannot be confused with the text, replaced by tags once the excerpts are HTML-escaped
private const val MARK_START = "\u0001"
private const val MARK_END = "\u0002"
private const val EXCERPT_SEPARATOR = "\u0000"

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
    } else if (entity == LuceneEntity.BookContent && searchTerm.contains(':')) {
      // ponytail: content documents carry no metadata fields, so fielded queries (tag:x, -author:y) cannot be evaluated against them.
      // Those queries only search metadata. Index the content inside the book document if mixed queries are ever needed.
      emptyList()
    } else {
      try {
        val fieldsQuery =
          MultiFieldQueryParser(entity.defaultFields, searchAnalyzer)
            .apply {
              defaultOperator = QueryParser.Operator.AND
              // no match-all for content, else a negative-only query would return every book that has content
            }.parse(if (entity == LuceneEntity.BookContent) searchTerm else "$searchTerm *:*")

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
      } catch (_: ParseException) {
        emptyList()
      } catch (e: Exception) {
        logger.error(e) { "Error fetching entities from index" }
        emptyList()
      }
    }

  /**
   * @return excerpts of the book content matching the [searchTerm], by book ID.
   * Excerpts are HTML-escaped, the matching terms are enclosed in `<mark>` tags.
   */
  fun highlightBookContent(
    searchTerm: String?,
    bookIds: Collection<String>,
  ): Map<String, List<String>> {
    val entity = LuceneEntity.BookContent
    // same restriction as the search: fielded queries do not apply to the content
    if (searchTerm.isNullOrBlank() || searchTerm.contains(':') || bookIds.isEmpty()) return emptyMap()

    val searcher = searcherManager.acquire()
    return try {
      val field = entity.defaultFields.first()
      val contentQuery = QueryParser(field, searchAnalyzer).apply { defaultOperator = QueryParser.Operator.AND }.parse(searchTerm)
      val query =
        BooleanQuery
          .Builder()
          .add(contentQuery, BooleanClause.Occur.MUST)
          .add(TermInSetQuery(entity.id, bookIds.map { BytesRef(it) }), BooleanClause.Occur.FILTER)
          .build()
      val topDocs = searcher.search(query, bookIds.size)

      val highlights =
        UnifiedHighlighter
          .builder(searcher, indexAnalyzer)
          // the default only looks at the first 10000 characters
          .withMaxLength(Int.MAX_VALUE - 1)
          .withBreakIterator { LengthGoalBreakIterator.createClosestToLength(BreakIterator.getWordInstance(Locale.ROOT), EXCERPT_LENGTH, 0.5f) }
          .withFormatter(DefaultPassageFormatter(MARK_START, MARK_END, EXCERPT_SEPARATOR, false))
          .build()
          .highlight(field, contentQuery, topDocs, MAX_EXCERPTS)

      topDocs.scoreDocs
        .mapIndexedNotNull { index, scoreDoc ->
          val excerpts =
            highlights[index]
              .orEmpty()
              .split(EXCERPT_SEPARATOR)
              // without a match the highlighter returns the beginning of the text
              .filter { it.contains(MARK_START) }
              .map { HtmlUtils.htmlEscape(it.trim(), Charsets.UTF_8.name()).replace(MARK_START, "<mark>").replace(MARK_END, "</mark>") }
          searcher.storedId(scoreDoc.doc, entity)?.takeIf { excerpts.isNotEmpty() }?.let { it to excerpts }
        }.toMap()
    } catch (_: ParseException) {
      emptyMap()
    } catch (e: Exception) {
      logger.error(e) { "Error highlighting book content" }
      emptyMap()
    } finally {
      searcherManager.release(searcher)
    }
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

  fun deleteDocuments(term: Term) {
    indexWriter.deleteDocuments(term)
    luceneCommitter.commitAndMaybeRefresh()
  }
}
