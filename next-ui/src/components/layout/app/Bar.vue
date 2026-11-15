<template>
  <v-app-bar elevation="1">
    <template #prepend>
      <v-hover v-slot="{ isHovering, props }">
        <v-app-bar-nav-icon
          v-bind="props"
          @click="appStore.drawer = !appStore.drawer"
        >
          <template #default>
            <KTooltip
              activator="parent"
              location="end"
              :text="tooltipMessage"
            />

            <v-badge
              :model-value="unreadCount > 0 && !appStore.drawer"
              dot
              floating
              color="info"
            >
              <v-icon
                :icon="
                  display.mobile.value
                    ? 'i-mdi:menu'
                    : isHovering
                      ? appStore.drawer
                        ? 'i-tabler:layout-sidebar-left-collapse'
                        : 'i-tabler:layout-sidebar-left-expand'
                      : 'i-tabler:layout-sidebar'
                "
              />
            </v-badge>
          </template>
        </v-app-bar-nav-icon>
      </v-hover>
    </template>

    <v-app-bar-title v-if="display.smAndUp.value">
      <RouterLink to="/">
        <v-avatar start>
          <v-img src="@/assets/logo.svg" />
        </v-avatar>
      </RouterLink>
      Komga
    </v-app-bar-title>

    <v-text-field
      v-model="search"
      :placeholder="searchMessage"
      :aria-label="searchMessage"
      prepend-inner-icon="i-mdi:magnify"
      variant="outlined"
      density="compact"
      max-width="480"
      class="mx-2"
      hide-details
      single-line
      clearable
      @keydown.enter="router.push({ name: '/search', query: { q: search ?? '' } })"
    />

    <template #append>
      <div class="d-flex align-center ga-2 pe-2">
        <TaskIndicator />
        <UserMenu />
      </div>
    </template>
  </v-app-bar>
</template>

<script setup lang="ts">
import { useAppStore } from '@/stores/app'
import { useAnnouncements } from '@/colada/announcements'
import { useIntl } from 'vue-intl'
import { useDisplay } from 'vuetify'
import { useRouteQuery } from '@vueuse/router'

const intl = useIntl()
const display = useDisplay()

const appStore = useAppStore()
const { unreadCount } = useAnnouncements()

const router = useRouter()
// keeps the field in sync with the search page
const routeSearch = useRouteQuery('q', '')
const search = ref<string | null>(routeSearch.value)
watch(routeSearch, (it) => (search.value = it))

const searchMessage = intl.formatMessage({
  description: 'App bar: search field placeholder',
  defaultMessage: 'Search books',
  id: 'Nnjb9F',
})

const expandMessage = intl.formatMessage({
  description: 'Sidebar button: tooltip expand',
  defaultMessage: 'Expand sidebar',
  id: 'RTNaxB',
})
const collapseMessage = intl.formatMessage({
  description: 'Sidebar button: tooltip collapse',
  defaultMessage: 'Collapse sidebar',
  id: '0JF9f5',
})
const tooltipMessage = computed(() =>
  display.mobile.value ? expandMessage : appStore.drawer ? collapseMessage : expandMessage,
)
</script>
