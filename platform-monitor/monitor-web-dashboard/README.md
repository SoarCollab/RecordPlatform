# Monitor Web Dashboard

Modern Vue 3 web dashboard for the platform monitoring system.

## Features

- **Modern Architecture**: Built with Vue 3, Composition API, and TypeScript
- **Real-time Updates**: WebSocket integration for live data streaming
- **Responsive Design**: Mobile-first responsive design
- **Dark Mode**: Built-in dark/light theme support
- **State Management**: Pinia for reactive state management
- **Component Architecture**: Reusable UI components
- **Performance Optimized**: Code splitting and lazy loading

## Tech Stack

- **Frontend Framework**: Vue 3 with Composition API
- **Language**: TypeScript
- **Build Tool**: Vite
- **State Management**: Pinia
- **Routing**: Vue Router 4
- **Styling**: CSS Variables with custom design system
- **Charts**: Chart.js with Vue integration
- **Real-time**: Socket.IO client
- **HTTP Client**: Axios
- **Date Handling**: date-fns
- **Utilities**: @vueuse/core, lodash-es

## Development

### Prerequisites

- Node.js 18+ 
- npm or yarn

### Setup

```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

### Development Scripts

```bash
# Type checking
npm run type-check

# Linting
npm run lint

# Testing
npm run test
npm run test:watch

# End-to-end testing
npm run test:e2e
```

## Project Structure

```
src/
├── assets/          # Static assets and styles
├── components/      # Reusable Vue components
│   ├── common/      # Common UI components
│   └── layout/      # Layout components
├── router/          # Vue Router configuration
├── services/        # API services and utilities
├── stores/          # Pinia stores
├── types/           # TypeScript type definitions
├── views/           # Page components
└── main.ts          # Application entry point
```

## Configuration

### Environment Variables

Create a `.env.local` file for local development:

```env
VITE_API_BASE_URL=http://localhost:8080/api/v2
VITE_WS_BASE_URL=ws://localhost:8080
```

### API Integration

The dashboard connects to the monitor system's REST API and WebSocket endpoints:

- **REST API**: `/api/v2/*` - Client data, metrics, authentication
- **WebSocket**: `/ws` - Real-time data streaming and notifications

## Features Implementation Status

### ✅ Task 9.1 - Modern Frontend Architecture
- Vue 3 application with Composition API and TypeScript
- Pinia state management for client and monitoring data
- Component-based architecture with reusable UI components
- Build system and development environment configured

### 🚧 Task 9.2 - Real-time Dashboard Components (Next)
- Real-time charts with WebSocket data streaming integration
- Interactive dashboards with drill-down capabilities
- Customizable dashboard layouts and widgets
- Responsive design for mobile and desktop access

### 🚧 Task 9.3 - Advanced User Interface Features (Planned)
- Advanced search and filtering for client and metric data
- Data export and sharing functionality
- Dark mode, accessibility features, and internationalization
- User preferences and dashboard customization

### 🚧 Task 9.4 - Frontend Tests (Planned)
- End-to-end tests for user workflows and authentication
- Real-time data updates and WebSocket connection handling
- Dashboard functionality, responsiveness, and performance
- Cross-browser compatibility and accessibility compliance

## Architecture Decisions

### State Management
- **Pinia** for reactive state management with TypeScript support
- Separate stores for different domains (auth, clients, websocket, theme)
- Computed properties for derived state and filtering

### Component Design
- **Composition API** for better TypeScript integration and reusability
- **Single File Components** with scoped styles
- **Props/Events** pattern for component communication
- **Teleport** for modals and notifications

### Styling Approach
- **CSS Variables** for theming and dark mode support
- **Utility Classes** for common patterns
- **Component-scoped Styles** to prevent conflicts
- **Responsive Design** with mobile-first approach

### Real-time Updates
- **WebSocket Store** manages connections and subscriptions
- **Automatic Reconnection** with exponential backoff
- **Selective Subscriptions** to minimize data transfer
- **<5 Second Latency** requirement for data visualization

## Browser Support

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

## Performance Considerations

- **Code Splitting**: Automatic route-based code splitting
- **Lazy Loading**: Components and routes loaded on demand
- **Bundle Optimization**: Vendor chunks and tree shaking
- **Caching**: HTTP caching and service worker (planned)
- **WebSocket Optimization**: Selective subscriptions and connection pooling