package org.gotson.komga.interfaces.api.rest.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.jakewharton.byteunits.BinaryByteUnit
import io.swagger.v3.oas.annotations.media.Schema
import org.apache.commons.io.FilenameUtils
import org.gotson.komga.domain.model.MediaType
import java.time.LocalDate
import java.time.LocalDateTime

data class BookDto(
  val id: String,
  val seriesId: String,
  val seriesTitle: String,
  val libraryId: String,
  val name: String,
  val url: String,
  val number: Int,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val created: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val lastModified: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val fileLastModified: LocalDateTime,
  val sizeBytes: Long,
  val size: String = BinaryByteUnit.format(sizeBytes),
  val media: MediaDto,
  val metadata: BookMetadataDto,
  val readProgress: ReadProgressDto? = null,
  val deleted: Boolean,
  val fileHash: String,
  val oneshot: Boolean,
  @get:Schema(description = "Excerpts of the book content matching the full text search. Only present for paged full text searches matching the book content.")
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val contentSnippets: List<ContentSnippetDto>? = null,
  @get:Schema(description = "Number of matches of the full text search in the book content. Only present for paged full text searches matching the book content.")
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val contentMatchCount: Int? = null,
)

data class ContentSnippetDto(
  @get:Schema(description = "HTML-escaped excerpt, with the matching terms enclosed in <mark> tags.")
  val text: String,
  @get:Schema(description = "Resource of the book containing the excerpt, as in the href of a progression locator.")
  val href: String,
  @get:Schema(description = "Position of the first match within the resource, between 0 and 1. Computed from the text, so it is approximate.")
  val progression: Float,
  @get:Schema(description = "Text of the first match, as found in the resource.")
  val highlight: String,
  @get:Schema(description = "Zero-based index of the first match among the non-overlapping occurrences of the highlight in the text of the resource. Locates the match exactly.")
  val occurrence: Int,
  @get:Schema(description = "Number of matches in the excerpt. When the excerpts of a book have less matches than the book, there are more excerpts.")
  val matchCount: Int,
  @get:Schema(description = "Title of the table of contents entry the first match is under, if any.")
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val chapter: String? = null,
)

fun BookDto.restrictUrl(restrict: Boolean) = if (restrict) copy(url = FilenameUtils.getName(url)) else this

data class MediaDto(
  val status: String,
  val mediaType: String,
  val pagesCount: Int,
  val comment: String,
  val epubDivinaCompatible: Boolean,
  val epubIsKepub: Boolean,
) {
  val mediaProfile: String by lazy { MediaType.fromMediaType(mediaType)?.profile?.name ?: "" }
}

data class BookMetadataDto(
  val title: String,
  val titleLock: Boolean,
  val summary: String,
  val summaryLock: Boolean,
  val number: String,
  val numberLock: Boolean,
  val numberSort: Float,
  val numberSortLock: Boolean,
  @JsonFormat(pattern = "yyyy-MM-dd")
  val releaseDate: LocalDate?,
  val releaseDateLock: Boolean,
  val authors: List<AuthorDto>,
  val authorsLock: Boolean,
  val notableEntities: List<NotableEntityDto>,
  val notableEntitiesLock: Boolean,
  val tags: Set<String>,
  val tagsLock: Boolean,
  val isbn: String,
  val isbnLock: Boolean,
  val links: List<WebLinkDto>,
  val linksLock: Boolean,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val created: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val lastModified: LocalDateTime,
)

data class ReadProgressDto(
  val page: Int,
  val completed: Boolean,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val readDate: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val created: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val lastModified: LocalDateTime,
  val deviceId: String,
  val deviceName: String,
)
