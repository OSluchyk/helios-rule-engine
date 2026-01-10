import { useState, lazy, Suspense } from 'react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from './components/ui/tabs'
import { RuleListView } from './components/helios/RuleListView'
import { RuleBuilder } from './components/helios/RuleBuilder'
import type { RuleMetadata } from '../types/api'
import { Loader2 } from 'lucide-react'

// Lazy load heavy components to improve initial bundle size and load time
// These components contain large dependencies (Recharts, json-diff-kit, etc.)
const UnifiedEvaluationView = lazy(() => import('./components/helios/UnifiedEvaluationView'))
const CompilationView = lazy(() => import('./components/helios/CompilationView'))
const MonitoringView = lazy(() => import('./components/helios/MonitoringView'))

// Loading fallback component for lazy-loaded views
function LoadingFallback() {
  return (
    <div className="flex items-center justify-center h-64">
      <div className="text-center space-y-4">
        <Loader2 className="size-8 animate-spin mx-auto text-blue-600" />
        <p className="text-muted-foreground">Loading...</p>
      </div>
    </div>
  )
}

function App() {
  const [activeTab, setActiveTab] = useState('overview')
  const [editingRule, setEditingRule] = useState<RuleMetadata | null>(null)

  const handleEditRule = (rule: RuleMetadata) => {
    setEditingRule(rule)
    setActiveTab('builder')
  }

  const handleRuleCreated = () => {
    setEditingRule(null)
    setActiveTab('rules')
  }

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-4">
          <h1 className="text-2xl font-bold">Helios Rule Engine</h1>
          <p className="text-sm text-muted-foreground">Management Console</p>
        </div>
      </header>

      <main className="container mx-auto px-4 py-6">
        <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-4">
          <TabsList>
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="rules">Rules</TabsTrigger>
            <TabsTrigger value="builder">Rule Builder</TabsTrigger>
            <TabsTrigger value="evaluation">Evaluation</TabsTrigger>
            <TabsTrigger value="compilation">Compilation</TabsTrigger>
            <TabsTrigger value="monitoring">Monitoring</TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="space-y-4">
            <div className="rounded-lg border p-6">
              <h2 className="text-xl font-semibold mb-4">Welcome to Helios Rule Engine</h2>
              <p className="text-muted-foreground">
                This is the management console for the Helios Rule Engine. Use the tabs above to:
              </p>
              <ul className="list-disc list-inside mt-4 space-y-2 text-muted-foreground">
                <li><strong>Rules:</strong> Browse and manage existing rules</li>
                <li><strong>Rule Builder:</strong> Create new rules with a visual interface</li>
                <li><strong>Evaluation:</strong> Test single events with traces or run batch evaluations</li>
                <li><strong>Compilation:</strong> View compilation pipeline and optimization metrics</li>
                <li><strong>Monitoring:</strong> Monitor real-time performance and system health</li>
              </ul>
              <div className="mt-6 p-4 bg-blue-50 dark:bg-blue-950 rounded-md">
                <p className="text-sm text-blue-900 dark:text-blue-100">
                  <strong>Note:</strong> UI components will be implemented progressively.
                  This is the initial setup. See helios-mock-ui for complete component examples.
                </p>
              </div>
            </div>
          </TabsContent>

          <TabsContent value="rules">
            <RuleListView
              onNewRule={() => {
                setEditingRule(null)
                setActiveTab('builder')
              }}
              onEditRule={handleEditRule}
            />
          </TabsContent>

          <TabsContent value="builder">
            <RuleBuilder
              onRuleCreated={handleRuleCreated}
              editingRule={editingRule}
            />
          </TabsContent>

          <TabsContent value="evaluation">
            <Suspense fallback={<LoadingFallback />}>
              <UnifiedEvaluationView />
            </Suspense>
          </TabsContent>

          <TabsContent value="compilation">
            <Suspense fallback={<LoadingFallback />}>
              <CompilationView />
            </Suspense>
          </TabsContent>

          <TabsContent value="monitoring">
            <Suspense fallback={<LoadingFallback />}>
              <MonitoringView />
            </Suspense>
          </TabsContent>
        </Tabs>
      </main>
    </div>
  )
}

export default App
