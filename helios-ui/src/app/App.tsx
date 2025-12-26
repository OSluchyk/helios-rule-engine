import { useState } from 'react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from './components/ui/tabs'
import { RulesList } from './components/helios/RulesList'
import { EvaluationView } from './components/helios/EvaluationView'
import { BatchEvaluationView } from './components/helios/BatchEvaluationView'
import { CompilationView } from './components/helios/CompilationView'
import { MonitoringView } from './components/helios/MonitoringView'

function App() {
  const [activeTab, setActiveTab] = useState('overview')

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
            <TabsTrigger value="batch">Batch Testing</TabsTrigger>
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
                <li><strong>Evaluation:</strong> Test individual rules and debug execution traces</li>
                <li><strong>Batch Testing:</strong> Evaluate multiple events and view aggregated statistics</li>
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
            <RulesList />
          </TabsContent>

          <TabsContent value="builder">
            <div className="rounded-lg border p-6">
              <p className="text-muted-foreground">Visual rule builder will be implemented here.</p>
              <p className="text-sm text-muted-foreground mt-2">
                Reference: helios-mock-ui/src/app/components/helios/VisualRuleBuilder.tsx
              </p>
            </div>
          </TabsContent>

          <TabsContent value="evaluation">
            <EvaluationView />
          </TabsContent>

          <TabsContent value="batch">
            <BatchEvaluationView />
          </TabsContent>

          <TabsContent value="compilation">
            <CompilationView />
          </TabsContent>

          <TabsContent value="monitoring">
            <MonitoringView />
          </TabsContent>
        </Tabs>
      </main>
    </div>
  )
}

export default App
