import { Tabs, TabsContent, TabsList, TabsTrigger } from './components/ui/tabs';
import { RuleListView } from './components/helios/RuleListView';
import { CompilationView } from './components/helios/CompilationView';
import { EvaluationView } from './components/helios/EvaluationView';
import { MonitoringView } from './components/helios/MonitoringView';
import { VisualRuleBuilder } from './components/helios/VisualRuleBuilder';
import { Code2, Gauge, Play, ListTree, PenTool } from 'lucide-react';

export default function App() {
  return (
    <div className="size-full bg-gray-50">
      <div className="h-full flex flex-col">
        {/* Header */}
        <header className="bg-white border-b px-8 py-4">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-bold text-gray-900">Helios Rule Engine</h1>
              <p className="text-sm text-gray-600 mt-1">
                Rule authoring, debugging, and performance optimization platform
              </p>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <div className="flex items-center gap-2 px-3 py-1.5 bg-green-100 text-green-800 rounded-lg">
                <div className="size-2 bg-green-600 rounded-full animate-pulse"></div>
                <span className="font-medium">Production</span>
              </div>
              <div className="text-gray-600">v2.1.0</div>
            </div>
          </div>
        </header>

        {/* Main Content */}
        <main className="flex-1 overflow-hidden p-8">
          <Tabs defaultValue="rules" className="h-full flex flex-col">
            <TabsList className="mb-6">
              <TabsTrigger value="rules" className="flex items-center gap-2">
                <ListTree className="size-4" />
                Rules
              </TabsTrigger>
              <TabsTrigger value="builder" className="flex items-center gap-2">
                <PenTool className="size-4" />
                Rule Builder
              </TabsTrigger>
              <TabsTrigger value="compilation" className="flex items-center gap-2">
                <Code2 className="size-4" />
                Compilation
              </TabsTrigger>
              <TabsTrigger value="evaluation" className="flex items-center gap-2">
                <Play className="size-4" />
                Evaluation & Debug
              </TabsTrigger>
              <TabsTrigger value="monitoring" className="flex items-center gap-2">
                <Gauge className="size-4" />
                Monitoring
              </TabsTrigger>
            </TabsList>

            <div className="flex-1 overflow-auto">
              <TabsContent value="rules" className="h-full m-0">
                <RuleListView />
              </TabsContent>

              <TabsContent value="builder" className="h-full m-0">
                <VisualRuleBuilder />
              </TabsContent>

              <TabsContent value="compilation" className="h-full m-0">
                <CompilationView />
              </TabsContent>

              <TabsContent value="evaluation" className="h-full m-0">
                <EvaluationView />
              </TabsContent>

              <TabsContent value="monitoring" className="h-full m-0">
                <MonitoringView />
              </TabsContent>
            </div>
          </Tabs>
        </main>
      </div>
    </div>
  );
}