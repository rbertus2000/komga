package org.gotson.komga.infrastructure.mediacontainer.epub

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.EpubTocEntry
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class EpubResourceTextTest {
  private val body =
    Jsoup
      .parse(
        """
        <html><body>
          <p>Preface text.</p>
          <div><h1 id="c1">Chapter One</h1>
            <p>First <a name="inline"></a>paragraph.</p>
          </div>
          <h1 id="c2">Chapter Two</h1>
          <p>Second paragraph.</p>
        </body></html>
        """.trimIndent(),
      ).body()

  @Test
  fun `given anchor when getting text offset then it is where the element starts in the text`() {
    val text = body.text()
    assertThat(text).isEqualTo("Preface text. Chapter One First paragraph. Chapter Two Second paragraph.")

    // an offset can be on the whitespace before the element
    assertThat(text.substring(textOffset(body, "c1")!!).trim()).startsWith("Chapter One")
    assertThat(text.substring(textOffset(body, "c2")!!).trim()).startsWith("Chapter Two")
    assertThat(text.substring(textOffset(body, "inline")!!).trim()).startsWith("paragraph. Chapter Two")
    assertThat(textOffset(body, "unknown")).isNull()
    // the body is left untouched
    assertThat(body.text()).isEqualTo(text)
  }

  @Test
  fun `given table of contents when getting chapters then the title at an offset is the one of the last entry before it`() {
    val toc =
      listOf(
        EpubTocEntry("Other", "other.xhtml#c1"),
        EpubTocEntry("Two", "text/ch.xhtml#c2"),
        EpubTocEntry("One", "text/ch.xhtml#c1"),
        EpubTocEntry("Missing anchor", "text/ch.xhtml#unknown"),
        EpubTocEntry("No link", null),
      )

    val text = body.text()
    val resource = EpubResourceText("text/ch.xhtml", text, listOf(0 to "Previous") + chaptersOf("text/ch.xhtml", body, toc))

    // an entry without a target in the resource applies from its start
    assertThat(resource.chapterAt(text.indexOf("Preface"))).isEqualTo("Missing anchor")
    assertThat(resource.chapterAt(text.indexOf("Chapter One"))).isEqualTo("One")
    assertThat(resource.chapterAt(text.indexOf("First"))).isEqualTo("One")
    assertThat(resource.chapterAt(text.indexOf("Chapter Two"))).isEqualTo("Two")
    assertThat(resource.chapterAt(text.indexOf("Second"))).isEqualTo("Two")

    // the title of the previous resource applies until the first entry
    assertThat(EpubResourceText("text/ch.xhtml", text, listOf(0 to "Previous") + chaptersOf("text/ch.xhtml", body, toc.take(3))).chapterAt(0)).isEqualTo("Previous")
    assertThat(EpubResourceText("text/ch.xhtml", text).chapterAt(10)).isNull()
  }
}
