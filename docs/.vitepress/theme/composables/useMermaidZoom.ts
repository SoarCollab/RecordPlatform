import { onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vitepress'

type SvgSize = {
  width: number
  height: number
}

function parseSvgLength(value: string | null): number | null {
  if (!value) return null

  const trimmed = value.trim()
  // Percentage lengths depend on container sizing, not usable here
  if (!trimmed || trimmed.endsWith('%')) return null

  const parsed = Number.parseFloat(trimmed)
  if (!Number.isFinite(parsed) || parsed <= 0) return null

  return parsed
}

function parseViewBox(viewBox: string | null): SvgSize | null {
  if (!viewBox) return null

  const parts = viewBox.trim().split(/[\s,]+/).map(v => Number.parseFloat(v))
  if (parts.length !== 4) return null

  const width = parts[2]
  const height = parts[3]
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null

  return { width, height }
}

function getDiagramSize(svg: SVGElement): SvgSize | null {
  const viewBoxSize = parseViewBox(svg.getAttribute('viewBox'))
  if (viewBoxSize) return viewBoxSize

  // Some Mermaid SVGs use width="100%" with a numeric height which breaks aspect calculations.
  const widthAttr = parseSvgLength(svg.getAttribute('width'))
  const heightAttr = parseSvgLength(svg.getAttribute('height'))
  if (widthAttr && heightAttr) return { width: widthAttr, height: heightAttr }

  // Fallback to rendered size when attributes/viewBox are unavailable.
  const rect = svg.getBoundingClientRect()
  if (rect.width > 0 && rect.height > 0) {
    return { width: rect.width, height: rect.height }
  }

  return null
}

function getMaxSvgSizeInModal(): SvgSize {
  // Keep this in sync with docs/.vitepress/theme/styles/mermaid-zoom.css
  const remPx = Number.parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
  const viewportW = window.innerWidth
  const viewportH = window.innerHeight

  const isMobile = viewportW <= 768
  const isTablet = viewportW > 768 && viewportW <= 1024

  const overlayPadding = (isMobile ? 1 : 2) * remPx
  const contentPadding = (isMobile ? 1.5 : 2) * remPx

  const contentMaxVW = (isMobile || isTablet) ? 0.95 : 0.9
  const contentMaxVH = (isMobile || isTablet) ? 0.95 : 0.9

  const maxContentW = Math.min(viewportW * contentMaxVW, viewportW - overlayPadding * 2)
  const maxContentH = Math.min(viewportH * contentMaxVH, viewportH - overlayPadding * 2)

  return {
    width: Math.max(1, maxContentW - contentPadding * 2),
    height: Math.max(1, maxContentH - contentPadding * 2),
  }
}

/**
 * Initialize Mermaid zoom functionality on current DOM elements
 * Pure function that returns a cleanup function
 * @returns Cleanup function to remove all event listeners
 */
function initMermaidZoom(): () => void {
  // Client-side only
  if (typeof window === 'undefined') return () => {}

  const cleanupFunctions: (() => void)[] = []
  const mermaidElements = document.querySelectorAll('.mermaid')

  mermaidElements.forEach((element) => {
    const handleClick = (e: Event) => {
      e.preventDefault()
      e.stopPropagation()

      // Find SVG element
      const svg = element.querySelector('svg')
      if (!svg) return

      // Clone SVG for modal
      const clonedSvg = svg.cloneNode(true) as SVGElement

      // Get intrinsic diagram size (prefer viewBox to avoid Mermaid width="100%" pitfalls)
      const diagramSize = getDiagramSize(svg)
      if (!diagramSize) return
      const { width: originalWidth, height: originalHeight } = diagramSize

      // Ensure viewBox is set for proper scaling
      const existingViewBox = clonedSvg.getAttribute('viewBox')
      if (!existingViewBox && originalWidth && originalHeight) {
        clonedSvg.setAttribute('viewBox', `0 0 ${originalWidth} ${originalHeight}`)
      }

      // Set explicit dimensions that scale well
      // Keep the aspect ratio but constrain to modal viewport (accounts for paddings)
      const { width: maxW, height: maxH } = getMaxSvgSizeInModal()
      const scale = Math.min(maxW / originalWidth, maxH / originalHeight)
      const displayWidth = originalWidth * scale
      const displayHeight = originalHeight * scale

      clonedSvg.setAttribute('width', String(displayWidth))
      clonedSvg.setAttribute('height', String(displayHeight))
      clonedSvg.style.display = 'block'
      // Override Mermaid's inline max-width/max-height constraints
      clonedSvg.style.width = `${displayWidth}px`
      clonedSvg.style.height = `${displayHeight}px`
      clonedSvg.style.maxWidth = 'none'
      clonedSvg.style.maxHeight = 'none'

      // Create SVG wrapper for proper sizing
      const svgWrapper = document.createElement('div')
      svgWrapper.className = 'mermaid-zoom-svg-wrapper'
      svgWrapper.appendChild(clonedSvg)

      // Create overlay
      const overlay = document.createElement('div')
      overlay.className = 'mermaid-zoom-overlay'
      overlay.setAttribute('role', 'dialog')
      overlay.setAttribute('aria-modal', 'true')
      overlay.setAttribute('aria-label', 'Zoomed diagram view')

      // Create content container
      const content = document.createElement('div')
      content.className = 'mermaid-zoom-content'
      content.appendChild(svgWrapper)

      // Create close button
      const closeBtn = document.createElement('button')
      closeBtn.className = 'mermaid-zoom-close'
      closeBtn.setAttribute('aria-label', 'Close diagram zoom')
      closeBtn.innerHTML = `
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      `

      content.appendChild(closeBtn)
      overlay.appendChild(content)

      // Close handlers
      let isClosing = false
      const cleanupModalListeners = () => {
        overlay.removeEventListener('click', handleOverlayClick)
        closeBtn.removeEventListener('click', handleCloseClick)
        document.removeEventListener('keydown', handleEscape)
      }

      const closeModal = () => {
        if (isClosing) return
        isClosing = true

        cleanupModalListeners()
        overlay.classList.add('mermaid-zoom-closing')
        setTimeout(() => {
          if (overlay.parentNode) {
            document.body.removeChild(overlay)
          }
          document.body.style.overflow = ''
        }, 300)
      }

      const handleOverlayClick = (e: MouseEvent) => {
        if (e.target === overlay) {
          closeModal()
        }
      }

      const handleCloseClick = () => {
        closeModal()
      }

      const handleEscape = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
          closeModal()
        }
      }

      // Bind events
      overlay.addEventListener('click', handleOverlayClick)
      closeBtn.addEventListener('click', handleCloseClick)
      document.addEventListener('keydown', handleEscape)

      // Prevent body scroll
      document.body.style.overflow = 'hidden'

      // Add to DOM with animation
      document.body.appendChild(overlay)
      requestAnimationFrame(() => {
        overlay.classList.add('mermaid-zoom-visible')
      })

      // Cleanup if component unmounts while modal is open
      cleanupFunctions.push(cleanupModalListeners)
    }

    element.addEventListener('click', handleClick)

    // Store cleanup function for element listener
    cleanupFunctions.push(() => {
      element.removeEventListener('click', handleClick)
    })
  })

  // Return combined cleanup function
  return () => {
    cleanupFunctions.forEach(cleanup => cleanup())
  }
}

/**
 * Mermaid diagram zoom composable
 * Adds click-to-zoom capability for all Mermaid diagrams
 * Handles lifecycle and route changes automatically
 * SSR-safe with automatic cleanup
 */
export function useMermaidZoom() {
  const route = useRoute()
  let cleanup: (() => void) | null = null

  const initialize = () => {
    // Clean up previous listeners before re-initializing
    if (cleanup) {
      cleanup()
      cleanup = null
    }
    cleanup = initMermaidZoom()
  }

  onMounted(() => {
    initialize()
  })

  // Re-initialize when route changes (for SPA navigation)
  watch(() => route.path, () => {
    nextTick(initialize)
  })

  onUnmounted(() => {
    if (cleanup) {
      cleanup()
      cleanup = null
    }
  })
}
