import type { Meta, StoryObj } from '@storybook/vue3-vite'
import { useRouter } from 'vue-router'
import { delay, http } from 'msw'
import { expect } from 'storybook/test'

import Search from './search.vue'
import { handleGetBooks } from '@/generated/openapi/msw.gen'
import { response200OK, response401Unauthorized } from '@/mocks/api/utils'
import { mockPage } from '@/mocks/api/pageable'
import { PageRequest } from '@/types/PageRequest'

const meta = {
  component: Search,
  render: (_args, { parameters }) => ({
    components: { Search },
    setup() {
      // the search term comes from the route
      void useRouter().replace({ query: parameters.q ? { q: parameters.q } : {} })
    },
    template: '<Search />',
  }),
  parameters: {
    // More on how to position stories at: https://storybook.js.org/docs/configure/story-layout
    docs: {
      description: {
        component: '',
      },
    },
    q: 'valdorian',
  },
  args: {},
} satisfies Meta<typeof Search>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {},
  play: async ({ canvas }) => {
    // matches are highlighted, and the escaped text of the snippets is rendered as text
    await expect((await canvas.findAllByText('Valdorian'))[0]?.tagName).toBe('MARK')
    await expect(canvas.getAllByText(/they didn't even suspect/)[0]).toBeVisible()
    const links = canvas.getAllByRole('link')
    await expect(links[0]).toHaveAttribute('href', '/book/BOOK1')
    // the number of matches of the book, and the chapter of its snippets, shown once for consecutive snippets
    await expect(canvas.getAllByText('5 matches')[0]).toBeVisible()
    await expect(canvas.getAllByText('Chapter 1')[0]).toBeVisible()
    await expect(canvas.getAllByText('Chapter 2')).toHaveLength(
      canvas.getAllByText('Chapter 1').length,
    )
    // snippets open the classic epub reader at the match, in a new tab, without changing the reading progress
    await expect(links[1]).toHaveAttribute('target', '_blank')
    await expect(links[1]?.getAttribute('href')).toMatch(
      /\/classic\/book\/BOOK1\/read-epub\?href=OEBPS%2Fchapter\+1\.xhtml&progression=0\.25&highlight=Valdorian&occurrence=3&incognito=true$/,
    )
  },
}

export const AllSnippets: Story = {
  play: async ({ canvas, userEvent }) => {
    // the search returns the best snippets of each book, the others are shown on demand.
    // Of the 10 books with snippets on the page, 5 have matches outside of their best snippets
    await expect(await canvas.findAllByText('3 matches')).toHaveLength(5)
    await expect(canvas.getAllByRole('button', { name: 'Show all matches' })).toHaveLength(5)
    const text = /back to the/
    await expect((await canvas.findAllByText(/they didn't even suspect/))[0]).toBeVisible()
    await expect(canvas.queryByText(text)).toBeNull()

    await userEvent.click(
      canvas.getAllByRole('button', { name: 'Show all matches' })[0] as HTMLElement,
    )
    await expect(await canvas.findByText(text)).toBeVisible()
    await expect(canvas.getByText('Chapter 3')).toBeVisible()

    await userEvent.click(canvas.getByRole('button', { name: 'Show fewer matches' }))
    await expect(canvas.queryByText(text)).toBeNull()
  },
}

export const AllTypes: Story = {
  parameters: { q: 'e' },
  play: async ({ canvas }) => {
    // series, collections and read lists are found by their name, and link to their page
    await expect(
      (await canvas.findByText('Space Adventures')).closest('a')?.getAttribute('href'),
    ).toMatch(/^\/series\//)
    await expect(canvas.getByText('Golden Age').closest('a')?.getAttribute('href')).toMatch(
      /^\/collection\//,
    )
    await expect(canvas.getByText('Readlist example').closest('a')?.getAttribute('href')).toMatch(
      /^\/readlist\//,
    )
    for (const header of ['Series', 'Collections', 'Read lists', 'Books'])
      await expect(canvas.getByText(header)).toBeVisible()
  },
}

export const NoSearchTerm: Story = {
  parameters: { q: '' },
}

export const NoResults: Story = {
  beforeEach({ msw }) {
    msw.use(handleGetBooks(() => response200OK(mockPage([], new PageRequest(0, 20)))))
  },
}

export const Loading: Story = {
  beforeEach({ msw }) {
    msw.use(http.all('*/api/v1/books/list', async () => await delay(2_000)))
  },
}

export const Error: Story = {
  beforeEach({ msw }) {
    msw.use(handleGetBooks(() => response401Unauthorized()))
  },
}
