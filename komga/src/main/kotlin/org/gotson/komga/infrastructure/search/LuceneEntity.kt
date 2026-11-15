package org.gotson.komga.infrastructure.search

import org.apache.lucene.document.DateTools
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexOptions
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.SeriesCollection
import org.gotson.komga.interfaces.api.rest.dto.BookDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.gotson.komga.language.toDate

enum class LuceneEntity(
  val type: String,
  val id: String,
  val defaultFields: Array<String>,
) {
  Book("book", "book_id", arrayOf("title", "isbn")),
  Series("series", "series_id", arrayOf("title")),
  Collection("collection", "collection_id", arrayOf("name")),
  ReadList("readlist", "readlist_id", arrayOf("name")),

  // text of the book file, kept in its own document so that it does not need to be extracted again when the book metadata changes
  BookContent("book_content", "book_content_id", arrayOf("content")),
  ;

  companion object {
    const val TYPE = "type"
  }
}

fun BookDto.toDocument() =
  Document().apply {
    add(TextField("title", metadata.title, Field.Store.NO))
    add(TextField("isbn", metadata.isbn, Field.Store.NO))
    metadata.tags.forEach {
      add(TextField("tag", it, Field.Store.NO))
    }
    metadata.authors.forEach {
      add(TextField("author", it.name, Field.Store.NO))
      add(TextField(it.role, it.name, Field.Store.NO))
    }
    if (metadata.releaseDate != null) add(TextField("release_date", DateTools.dateToString(metadata.releaseDate.toDate(), DateTools.Resolution.YEAR), Field.Store.NO))
    add(TextField("status", media.status, Field.Store.NO))
    add(TextField("deleted", deleted.toString(), Field.Store.NO))
    add(TextField("oneshot", oneshot.toString(), Field.Store.NO))

    add(StringField(LuceneEntity.TYPE, LuceneEntity.Book.type, Field.Store.NO))
    add(StringField(LuceneEntity.Book.id, id, Field.Store.YES))
  }

// stored and indexed with offsets, so that excerpts can be highlighted without analyzing the whole text again
private val contentFieldType =
  FieldType(TextField.TYPE_STORED).apply {
    setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
    freeze()
  }

fun bookContentDocument(
  bookId: String,
  content: String,
) = Document().apply {
  // the ID must be the first stored field, so it can be read without loading the content
  add(StringField(LuceneEntity.BookContent.id, bookId, Field.Store.YES))
  add(StringField(LuceneEntity.TYPE, LuceneEntity.BookContent.type, Field.Store.NO))
  add(Field(LuceneEntity.BookContent.defaultFields.first(), content, contentFieldType))
}

fun SeriesDto.toDocument() =
  Document().apply {
    add(TextField("title", metadata.title, Field.Store.NO))
    if (metadata.titleSort != metadata.title) add(TextField("title", metadata.titleSort, Field.Store.NO))
    metadata.alternateTitles.forEach { add(TextField("title", it.title, Field.Store.NO)) }
    add(TextField("publisher", metadata.publisher, Field.Store.NO))
    add(TextField("status", metadata.status, Field.Store.NO))
    add(TextField("reading_direction", metadata.readingDirection, Field.Store.NO))
    if (metadata.ageRating != null) add(TextField("age_rating", metadata.ageRating.toString(), Field.Store.NO))
    if (metadata.language.isNotBlank()) add(TextField("language", metadata.language, Field.Store.NO))
    metadata.tags.forEach {
      add(TextField("series_tag", it, Field.Store.NO))
      add(TextField("tag", it, Field.Store.NO))
    }
    booksMetadata.tags.forEach {
      add(TextField("book_tag", it, Field.Store.NO))
      add(TextField("tag", it, Field.Store.NO))
    }
    metadata.genres.forEach {
      add(TextField("genre", it, Field.Store.NO))
    }
    metadata.sharingLabels.forEach {
      add(TextField("sharing_label", it, Field.Store.NO))
    }
    if (metadata.totalBookCount != null) add(TextField("total_book_count", metadata.totalBookCount.toString(), Field.Store.NO))
    add(TextField("book_count", booksCount.toString(), Field.Store.NO))
    booksMetadata.authors.forEach {
      add(TextField("author", it.name, Field.Store.NO))
      add(TextField(it.role, it.name, Field.Store.NO))
    }
    if (booksMetadata.releaseDate != null) add(TextField("release_date", DateTools.dateToString(booksMetadata.releaseDate.toDate(), DateTools.Resolution.YEAR), Field.Store.NO))
    add(TextField("deleted", deleted.toString(), Field.Store.NO))
    add(TextField("oneshot", oneshot.toString(), Field.Store.NO))
    if (metadata.totalBookCount != null) add(TextField("complete", (metadata.totalBookCount == booksCount).toString(), Field.Store.NO))

    add(StringField(LuceneEntity.TYPE, LuceneEntity.Series.type, Field.Store.NO))
    add(StringField(LuceneEntity.Series.id, id, Field.Store.YES))
  }

fun SeriesDto.oneshotDocument(document: Document) =
  document.apply {
    add(TextField("publisher", metadata.publisher, Field.Store.NO))
    add(TextField("status", metadata.status, Field.Store.NO))
    add(TextField("reading_direction", metadata.readingDirection, Field.Store.NO))
    if (metadata.ageRating != null) add(TextField("age_rating", metadata.ageRating.toString(), Field.Store.NO))
    if (metadata.language.isNotBlank()) add(TextField("language", metadata.language, Field.Store.NO))
    metadata.genres.forEach {
      add(TextField("genre", it, Field.Store.NO))
    }
    metadata.sharingLabels.forEach {
      add(TextField("sharing_label", it, Field.Store.NO))
    }
    add(TextField("complete", "true", Field.Store.NO))
  }

fun SeriesCollection.toDocument() =
  Document().apply {
    add(TextField("name", name, Field.Store.NO))
    add(StringField(LuceneEntity.TYPE, LuceneEntity.Collection.type, Field.Store.NO))
    add(StringField(LuceneEntity.Collection.id, id, Field.Store.YES))
  }

fun ReadList.toDocument() =
  Document().apply {
    add(TextField("name", name, Field.Store.NO))
    add(StringField(LuceneEntity.TYPE, LuceneEntity.ReadList.type, Field.Store.NO))
    add(StringField(LuceneEntity.ReadList.id, id, Field.Store.YES))
  }
