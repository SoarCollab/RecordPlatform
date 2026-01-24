import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'

// Styles
import './styles/vars.css'
import './styles/home.css'
import './styles/sidebar.css'
import './styles/components.css'
import './styles/mermaid-zoom.css'
import './custom.css'  // Keep existing Mermaid styles

// Layout
import Layout from './Layout.vue'

// Components
import StepCard from './components/StepCard.vue'
import CustomCallout from './components/CustomCallout.vue'
import FeatureHighlight from './components/FeatureHighlight.vue'
import ComparisonTable from './components/ComparisonTable.vue'

export default {
  extends: DefaultTheme,
  Layout,
  enhanceApp({ app }) {
    // Register global components for use in Markdown
    app.component('StepCard', StepCard)
    app.component('Callout', CustomCallout)
    app.component('FeatureHighlight', FeatureHighlight)
    app.component('ComparisonTable', ComparisonTable)
  }
} satisfies Theme
