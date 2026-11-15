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
    await expect(canvas.getAllByRole('link')[0]).toHaveAttribute('href', '/book/BOOK1')
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
