# Helios Rule Engine - Web UI

Production web interface for managing, testing, and monitoring the Helios Rule Engine.

## Quick Start

### Prerequisites
- Node.js >= 18.0.0
- npm >= 9.0.0
- Helios Rule Engine backend running on http://localhost:8080

### Development

```bash
# Install dependencies
npm install

# Start development server
./start-ui.sh
# OR
npm run dev

# Open browser to http://localhost:3000
```

### Production Build

```bash
# Create production build
./build-ui.sh
# OR
npm run build

# Preview production build
npm run preview
```

### Stop Development Server

```bash
./stop-ui.sh
```

## Project Structure

```
helios-ui/
├── public/                    # Static assets
├── src/
│   ├── app/
│   │   ├── App.tsx           # Main application component
│   │   └── components/
│   │       ├── ui/           # Reusable UI components (Radix UI)
│   │       ├── helios/       # Business logic components
│   │       └── figma/        # Figma-sourced components
│   ├── api/                  # API client and services
│   ├── hooks/                # Custom React hooks
│   ├── types/                # TypeScript type definitions
│   ├── utils/                # Utility functions
│   ├── styles/               # Global styles
│   └── main.tsx              # Application entry point
├── package.json              # Dependencies and scripts
├── vite.config.ts            # Vite configuration
├── tailwind.config.js        # Tailwind CSS configuration
├── tsconfig.json             # TypeScript configuration
└── README.md                 # This file
```

## Features (Implementation Roadmap)

### Phase 1: Foundation (Weeks 1-4)
- [ ] Rules Browser - List and search existing rules
- [ ] Basic rule details view
- [ ] Backend API integration
- [ ] Real-time monitoring dashboard

### Phase 2: Rule Management (Weeks 5-8)
- [ ] Visual Rule Builder
- [ ] Rule CRUD operations
- [ ] Version history and rollback
- [ ] Conflict detection

### Phase 3: Debugging & Testing (Weeks 9-12)
- [ ] Evaluation console with tracing
- [ ] Condition-level debugging
- [ ] Test suite management
- [ ] Batch testing

### Phase 4: Advanced Features (Weeks 13-16)
- [ ] Compilation pipeline visualization
- [ ] Performance optimization suggestions
- [ ] A/B testing framework
- [ ] Advanced analytics

## UI Components

The UI is built using:
- **React 18** - Component framework
- **TypeScript** - Type safety
- **Vite** - Build tool and dev server
- **Tailwind CSS** - Utility-first CSS
- **Radix UI** - Accessible component primitives
- **Recharts** - Data visualization
- **React Query** - Server state management
- **Axios** - HTTP client

### Available Views

1. **Overview** - System status and quick actions
2. **Rules** - Browse and manage rules (to be implemented)
3. **Rule Builder** - Visual rule creation (to be implemented)
4. **Evaluation** - Test and debug rules (to be implemented)
5. **Compilation** - View compilation pipeline (to be implemented)
6. **Monitoring** - Real-time performance metrics (to be implemented)

## Configuration

### Environment Variables

Create a `.env` file in the project root:

```bash
# Backend API URL
VITE_API_URL=http://localhost:8080

# Feature flags
VITE_ENABLE_DEBUG_MODE=true
VITE_ENABLE_EXPERIMENTAL_FEATURES=false
```

### API Proxy

The development server proxies `/api/*` requests to the backend:
- UI: `http://localhost:3000`
- API: `http://localhost:8080/api` (proxied from UI)

This avoids CORS issues during development.

## Development Workflow

### 1. Start Backend
```bash
# In rule engine root
mvn clean package -DskipTests
java -jar helios-service/target/helios-service-1.0-SNAPSHOT.jar
```

### 2. Start UI
```bash
# In helios-ui directory
./start-ui.sh
```

### 3. Make Changes
- Edit files in `src/`
- Vite will hot-reload changes automatically
- Check console for errors

### 4. Build for Production
```bash
./build-ui.sh
```

## Scripts

| Script | Description |
|--------|-------------|
| `npm run dev` | Start development server |
| `npm run build` | Create production build |
| `npm run preview` | Preview production build locally |
| `npm run lint` | Run ESLint |
| `npm run type-check` | Run TypeScript type checking |
| `./start-ui.sh` | Start dev server with health checks |
| `./stop-ui.sh` | Stop all running dev servers |
| `./build-ui.sh` | Production build with stats |

## Integration with helios-mock-ui

The `helios-mock-ui` directory contains complete UI component implementations.
Use it as a reference when implementing features:

```bash
# Copy component from mock-ui
cp ../helios-mock-ui/src/app/components/helios/RuleListView.tsx src/app/components/helios/

# Update imports to use real API instead of mock data
# Replace: import { mockRules } from './mock-data';
# With: import { useRules } from '@/api/rules';
```

## API Integration

### Example: Fetching Rules

```typescript
// src/api/rules.ts
import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
});

export const rulesApi = {
  listRules: async () => {
    const response = await api.get('/api/v1/rules');
    return response.data;
  },

  getRule: async (id: string) => {
    const response = await api.get(`/api/v1/rules/${id}`);
    return response.data;
  },
};

// src/app/components/helios/RuleListView.tsx
import { useQuery } from '@tanstack/react-query';
import { rulesApi } from '@/api/rules';

function RuleListView() {
  const { data: rules, isLoading, error } = useQuery({
    queryKey: ['rules'],
    queryFn: rulesApi.listRules,
  });

  if (isLoading) return <div>Loading...</div>;
  if (error) return <div>Error: {error.message}</div>;

  return <div>{/* Render rules */}</div>;
}
```

## Testing

```bash
# Run unit tests (when added)
npm test

# Run E2E tests (when added)
npm run test:e2e
```

## Troubleshooting

### Port 3000 Already in Use
```bash
# Kill process on port 3000
lsof -ti:3000 | xargs kill -9

# Or use a different port
PORT=3001 npm run dev
```

### API Connection Issues
1. Verify backend is running: `curl http://localhost:8080/health`
2. Check browser console for CORS errors
3. Verify proxy configuration in `vite.config.ts`

### Build Errors
```bash
# Clear cache and reinstall
rm -rf node_modules package-lock.json
npm install

# Check Node version
node -v  # Should be >= 18.0.0
```

## Performance

### Bundle Size Optimization
- Code splitting by route
- Lazy loading components
- Tree shaking unused code
- Minification in production

### Target Metrics
- Initial load: <2s (on 3G)
- Time to Interactive: <3s
- Bundle size: <500KB (gzipped)

## Deployment

### Static Hosting (Recommended)
```bash
# Build
npm run build

# Deploy dist/ folder to:
# - AWS S3 + CloudFront
# - Netlify
# - Vercel
# - GitHub Pages
```

### Docker
```dockerfile
FROM node:18-alpine as build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

## Contributing

See [UI_INTEGRATION_IMPLEMENTATION_PLAN.md](../UI_INTEGRATION_IMPLEMENTATION_PLAN.md) for the complete implementation roadmap.

### Code Style
- Use TypeScript for all new files
- Follow existing component patterns
- Use Tailwind CSS for styling
- Add JSDoc comments for complex functions

### Pull Request Process
1. Create feature branch from `main`
2. Implement feature
3. Add/update tests
4. Run `npm run lint` and `npm run type-check`
5. Submit PR with description

## License

Internal use only.

## Support

For questions or issues:
- Check [UI_INTEGRATION_IMPLEMENTATION_PLAN.md](../UI_INTEGRATION_IMPLEMENTATION_PLAN.md)
- Review [helios-mock-ui documentation](../helios-mock-ui/README.md)
- Contact: #helios-ui-integration Slack channel

---

**Status:** Initial setup complete, implementation in progress
**Last Updated:** 2025-12-25
