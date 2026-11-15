<template>
  <v-container
    fluid
    max-width="960"
    class="pa-0 pa-sm-4"
  >
    <EmptyStateNetworkError v-if="error" />

    <v-empty-state
      v-else-if="!q"
      icon="i-mdi:magnify"
      :title="
        $formatMessage({
          description: 'Search page: empty state title when there is no search term',
          defaultMessage: 'Search',
          id: 'ExNWqK',
        })
      "
      :text="
        $formatMessage({
          description: 'Search page: empty state text when there is no search term',
          defaultMessage:
            'Search series, books, collections and read lists, or the text inside EPUB books',
          id: 'kl0bOP',
        })
      "
    />

    <EmptyStateSearchNoResults
      v-else-if="books?.content?.length === 0 && groups.length === 0"
      @reset="q = ''"
    />

    <template v-else-if="books">
      <v-list
        lines="three"
        :disabled="isLoading"
      >
        <template
          v-for="group in groups"
          :key="group.title"
        >
          <v-list-subheader>{{ group.title }}</v-list-subheader>
          <v-list-item
            v-for="item in group.items"
            :key="item.id"
            :to="item.to"
            :title="item.title"
          >
            <template #prepend>
              <v-img
                width="52"
                height="75"
                contain
                :src="item.poster"
                lazy-src="@/assets/cover.svg"
                class="me-2"
              />
            </template>
          </v-list-item>
        </template>

        <v-list-subheader v-if="books.content?.length">
          {{
            $formatMessage({
              description: 'Search page: books results header',
              defaultMessage: 'Books',
              id: 'gRXxGy',
            })
          }}
        </v-list-subheader>
        <template
          v-for="book in books.content"
          :key="book.id"
        >
          <v-list-item :to="{ name: '/book/[id]', params: { id: book.id } }">
            <template #prepend>
              <v-img
                width="52"
                height="75"
                contain
                :src="bookPosterUrl(book.id, cacheStore.getVersion(book.id))"
                lazy-src="@/assets/cover.svg"
                class="me-2"
              />
            </template>

            <v-list-item-title>{{ book.metadata.title }}</v-list-item-title>
            <v-list-item-subtitle>{{ book.seriesTitle }}</v-list-item-subtitle>

            <template
              v-if="book.contentMatchCount"
              #append
            >
              <span class="text-label-large text-medium-emphasis">
                {{ matchCountMessage(book.contentMatchCount) }}
              </span>
            </template>
          </v-list-item>

          <template
            v-for="(snippet, index) in shownSnippets(book)"
            :key="index"
          >
            <!-- the chapter is shown once for the snippets that follow each other in it -->
            <div
              v-if="snippet.chapter && snippet.chapter !== shownSnippets(book)[index - 1]?.chapter"
              class="text-label-large mt-2"
              style="padding-inline-start: 92px"
            >
              {{ snippet.chapter }}
            </div>

            <!-- each snippet opens the book at the match, in a new tab so that the results stay at hand -->
            <v-list-item
              :href="readerUrl(book.id, snippet)"
              target="_blank"
              rel="noopener"
              lines="one"
            >
              <template #prepend>
                <!-- aligns the snippets with the book title -->
                <div
                  style="width: 52px"
                  class="me-2"
                />
              </template>

              <!-- snippets are HTML-escaped by the server, the only tags are the <mark> around the matches -->
              <!-- eslint-disable vue/no-v-html -->
              <div
                class="text-body-medium text-medium-emphasis"
                v-html="`…${snippet.text}…`"
              />
              <!-- eslint-enable vue/no-v-html -->
            </v-list-item>
          </template>

          <div
            v-if="allSnippets[book.id] || hasMoreSnippets(book)"
            style="padding-inline-start: 80px"
          >
            <v-btn
              variant="text"
              size="small"
              color="primary"
              :loading="loadingSnippets === book.id"
              @click="toggleAllSnippets(book.id)"
            >
              {{
                allSnippets[book.id]
                  ? $formatMessage({
                      description:
                        'Search page: button to show only the best content matches of a book',
                      defaultMessage: 'Show fewer matches',
                      id: 'KqUlfl',
                    })
                  : $formatMessage({
                      description: 'Search page: button to show all the content matches of a book',
                      defaultMessage: 'Show all matches',
                      id: 'mpiVeE',
                    })
              }}
            </v-btn>
          </div>
        </template>
      </v-list>

      <v-pagination
        v-if="(books.totalPages ?? 0) > 1"
        v-model="page1"
        :length="books.totalPages"
      />
    </template>
  </v-container>
</template>

<script lang="ts" setup>
import { useQuery } from '@pinia/colada'
import { useRouteQuery } from '@vueuse/router'
import { useIntl } from 'vue-intl'
import { bookListQuery } from '@/colada/books'
import { seriesListQuery } from '@/colada/series'
import { collectionsListQuery } from '@/colada/collections'
import { readListsListQuery } from '@/colada/readlists'
import {
  bookPosterUrl,
  collectionPosterUrl,
  readListPosterUrl,
  seriesPosterUrl,
} from '@/api/images'
import { useImageCacheStore } from '@/stores/image-cache'
import { PageRequest } from '@/types/PageRequest'
import { ApiBaseUrl } from '@/api/base'
import {
  type BookDto,
  type ContentSnippetDto,
  komgaGetBookContentSnippets,
} from '@/generated/openapi'

const intl = useIntl()
const cacheStore = useImageCacheStore()

// the epub reader only exists in the classic UI.
// Incognito, so that looking at a match does not overwrite the reading progress.
function readerUrl(bookId: string, snippet: ContentSnippetDto) {
  const query = new URLSearchParams({
    href: snippet.href,
    progression: String(snippet.progression),
    highlight: snippet.highlight,
    occurrence: String(snippet.occurrence),
    incognito: 'true',
  })
  return `${ApiBaseUrl.noSlash}/classic/book/${bookId}/read-epub?${query}`
}

const q = useRouteQuery('q', '')
const page1 = ref(1)

// all the snippets of the books they were asked for, in reading order
const allSnippets = ref<Record<string, ContentSnippetDto[]>>({})
const loadingSnippets = ref<string>()

watch(q, () => {
  page1.value = 1
  allSnippets.value = {}
})

// not in the template, where the braces of the plural would end the interpolation
function matchCountMessage(count: number) {
  return intl.formatMessage(
    {
      description: 'Search page: number of matches in the content of a book',
      defaultMessage: '{count, plural, one {# match} other {# matches}}',
      id: 'bSewDz',
    },
    { count: count },
  )
}

// the search only returns the best snippets of a book: there are more when those do not hold all the matches of the book
function hasMoreSnippets(book: BookDto) {
  const matches = (book.contentSnippets ?? []).reduce((sum, it) => sum + it.matchCount, 0)
  return (book.contentMatchCount ?? 0) > matches
}

function shownSnippets(book: BookDto) {
  return allSnippets.value[book.id] ?? book.contentSnippets ?? []
}

async function toggleAllSnippets(bookId: string) {
  if (allSnippets.value[bookId]) {
    delete allSnippets.value[bookId]
    return
  }
  loadingSnippets.value = bookId
  try {
    allSnippets.value[bookId] = await komgaGetBookContentSnippets({
      path: { bookId },
      query: { search: q.value },
    })
  } finally {
    loadingSnippets.value = undefined
  }
}

const {
  data: books,
  isLoading,
  error,
} = useQuery(() => ({
  ...bookListQuery({
    search: { fullTextSearch: q.value },
    // snippets are only returned for paged requests
    pageRequest: new PageRequest(page1.value - 1, 20, ['relevance']),
  }),
  enabled: !!q.value,
}))

// ponytail: only the 20 best series, collections and read lists, without pagination. Paginate each group if 20 falls short.
const others = new PageRequest(0, 20)
const { data: series } = useQuery(() => ({
  ...seriesListQuery({
    // oneshots are listed as books
    search: { fullTextSearch: q.value, condition: { oneShot: { operator: 'isFalse' } } },
    pageRequest: others,
  }),
  enabled: !!q.value,
}))
const { data: collections } = useQuery(() => ({
  ...collectionsListQuery({ search: q.value, pageRequest: others }),
  enabled: !!q.value,
}))
const { data: readLists } = useQuery(() => ({
  ...readListsListQuery({ search: q.value, pageRequest: others }),
  enabled: !!q.value,
}))

const groups = computed(() =>
  [
    {
      title: intl.formatMessage({
        description: 'Search page: series results header',
        defaultMessage: 'Series',
        id: '7F5R1f',
      }),
      items: series.value?.content?.map((it) => ({
        id: it.id,
        title: it.metadata.title,
        poster: seriesPosterUrl(it.id, cacheStore.getVersion(it.id)),
        to: { name: '/series/[id]' as const, params: { id: it.id } },
      })),
    },
    {
      title: intl.formatMessage({
        description: 'Search page: collections results header',
        defaultMessage: 'Collections',
        id: 'krotVT',
      }),
      items: collections.value?.content?.map((it) => ({
        id: it.id,
        title: it.name,
        poster: collectionPosterUrl(it.id, cacheStore.getVersion(it.id)),
        to: { name: '/collection/[id]' as const, params: { id: it.id } },
      })),
    },
    {
      title: intl.formatMessage({
        description: 'Search page: read lists results header',
        defaultMessage: 'Read lists',
        id: 'miF9xr',
      }),
      items: readLists.value?.content?.map((it) => ({
        id: it.id,
        title: it.name,
        poster: readListPosterUrl(it.id, cacheStore.getVersion(it.id)),
        to: { name: '/readlist/[id]' as const, params: { id: it.id } },
      })),
    },
  ].filter((it) => it.items?.length),
)
</script>

<route lang="yaml">
meta:
  requiresRole: USER
  scrollable: true
</route>
