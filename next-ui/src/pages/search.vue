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
          defaultMessage: 'Search books',
          id: '8mG82T',
        })
      "
      :text="
        $formatMessage({
          description: 'Search page: empty state text when there is no search term',
          defaultMessage: 'Search by title, ISBN, or the text inside EPUB books',
          id: '4jihJ/',
        })
      "
    />

    <EmptyStateSearchNoResults
      v-else-if="books?.content?.length === 0"
      @reset="q = ''"
    />

    <template v-else-if="books">
      <v-list
        lines="three"
        :disabled="isLoading"
      >
        <v-list-item
          v-for="book in books.content"
          :key="book.id"
          :to="{ name: '/book/[id]', params: { id: book.id } }"
        >
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

          <!-- snippets are HTML-escaped by the server, the only tags are the <mark> around the matches -->
          <!-- eslint-disable vue/no-v-html -->
          <div
            v-for="(snippet, index) in book.contentSnippets"
            :key="index"
            class="text-body-medium text-medium-emphasis mt-1"
            v-html="`…${snippet}…`"
          />
          <!-- eslint-enable vue/no-v-html -->
        </v-list-item>
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
import { bookListQuery } from '@/colada/books'
import { bookPosterUrl } from '@/api/images'
import { useImageCacheStore } from '@/stores/image-cache'
import { PageRequest } from '@/types/PageRequest'

const cacheStore = useImageCacheStore()

const q = useRouteQuery('q', '')
const page1 = ref(1)
watch(q, () => (page1.value = 1))

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
</script>

<route lang="yaml">
meta:
  requiresRole: USER
  scrollable: true
</route>
