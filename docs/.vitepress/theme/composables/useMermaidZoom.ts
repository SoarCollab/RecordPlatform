import { onMounted, onUnmounted } from 'vue'

/**
 * Mermaid diagram zoom functionality
 * Adds click-to-zoom capability for all Mermaid diagrams
 * SSR-safe, with automatic cleanup
 */
export function useMermaidZoom() {
  let cleanupFunctions: (() => void)[] = []

  onMounted(() => {
    // Client-side only
    if (typeof window === 'undefined') return

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
        clonedSvg.style.maxWidth = '90vw'
        clonedSvg.style.maxHeight = '90vh'
        clonedSvg.style.height = 'auto'
        clonedSvg.style.width = 'auto'

        // Create overlay
        const overlay = document.createElement('div')
        overlay.className = 'mermaid-zoom-overlay'
        overlay.setAttribute('role', 'dialog')
        overlay.setAttribute('aria-modal', 'true')
        overlay.setAttribute('aria-label', 'Zoomed diagram view')

        // Create content container
        const content = document.createElement('div')
        content.className = 'mermaid-zoom-content'
        content.appendChild(clonedSvg)

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
        const closeModal = () => {
          overlay.classList.add('mermaid-zoom-closing')
          setTimeout(() => {
            document.body.removeChild(overlay)
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

        // Cleanup when modal closes
        const modalCleanup = () => {
          overlay.removeEventListener('click', handleOverlayClick)
          closeBtn.removeEventListener('click', handleCloseClick)
          document.removeEventListener('keydown', handleEscape)
        }

        // Store cleanup for unmount
        cleanupFunctions.push(modalCleanup)
      }

      element.addEventListener('click', handleClick)

      // Store cleanup function
      cleanupFunctions.push(() => {
        element.removeEventListener('click', handleClick)
      })
    })
  })

  onUnmounted(() => {
    // Clean up all event listeners
    cleanupFunctions.forEach(cleanup => cleanup())
    cleanupFunctions = []
  })
}
