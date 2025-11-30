package com.helios.ruleengine.infra.management;

import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngineModelManagerTest {

    @Mock
    private IRuleCompiler compiler;

    @Mock
    private Tracer tracer;

    @Mock
    private SpanBuilder spanBuilder;

    @Mock
    private Span span;

    @Mock
    private Scope scope;

    @Mock
    private EngineModel engineModel;

    @TempDir
    Path tempDir;

    private Path rulesPath;

    @BeforeEach
    void setUp() throws IOException {
        rulesPath = tempDir.resolve("rules.json");
        Files.writeString(rulesPath, "[]");

        // Setup OpenTelemetry mocks
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
    }

    @Test
    void shouldLoadModelOnInitialization() throws Exception {
        when(compiler.compile(any(Path.class))).thenReturn(engineModel);

        EngineModelManager manager = new EngineModelManager(rulesPath, tracer, compiler);

        assertThat(manager.getEngineModel()).isEqualTo(engineModel);
        verify(compiler).compile(rulesPath);
    }

    @Test
    void shouldThrowExceptionIfInitialLoadFails() throws Exception {
        when(compiler.compile(any(Path.class))).thenThrow(new CompilationException("Compilation failed"));

        assertThatThrownBy(() -> new EngineModelManager(rulesPath, tracer, compiler))
                .isInstanceOf(CompilationException.class);
    }

    @Test
    void shouldReloadModelWhenFileChanges() throws Exception {
        when(compiler.compile(any(Path.class))).thenReturn(engineModel);
        EngineModel newModel = mock(EngineModel.class);

        EngineModelManager manager = new EngineModelManager(rulesPath, tracer, compiler);

        // Prepare for reload
        when(compiler.compile(any(Path.class))).thenReturn(newModel);

        manager.start();

        // Modify file to trigger reload
        // Ensure timestamp changes
        TimeUnit.MILLISECONDS.sleep(100);
        Files.writeString(rulesPath, "[{\"updated\": true}]");

        // Wait for reload (using Awaitility would be better, but simple loop for now)
        // Since we can't easily inject a clock or control the executor, we rely on the
        // file system timestamp check
        // The manager checks every 10 seconds, which is too slow for unit tests.
        // We should probably refactor EngineModelManager to accept a scheduler or check
        // interval.
        // For this test, we can manually invoke the private checkForUpdates method via
        // reflection
        // OR just test the logic if we extract it.
        // However, since we can't easily change the 10s delay without refactoring,
        // let's assume we want to test the *logic* of reload, not the scheduling.
        // We can use reflection to call checkForUpdates? Or just trust the integration
        // test for the scheduling part.

        // Actually, let's just verify the logic by creating a subclass or using
        // reflection if strictly needed.
        // But wait, the user asked for unit tests.
        // Let's use reflection to call 'checkForUpdates' to avoid waiting 10s.

        java.lang.reflect.Method checkMethod = EngineModelManager.class.getDeclaredMethod("checkForUpdates");
        checkMethod.setAccessible(true);
        checkMethod.invoke(manager);

        assertThat(manager.getEngineModel()).isEqualTo(newModel);
        verify(compiler, times(2)).compile(rulesPath);

        manager.shutdown();
    }

    @Test
    void shouldKeepOldModelIfReloadFails() throws Exception {
        when(compiler.compile(any(Path.class))).thenReturn(engineModel);

        EngineModelManager manager = new EngineModelManager(rulesPath, tracer, compiler);

        // Prepare for failed reload
        when(compiler.compile(any(Path.class))).thenThrow(new CompilationException("Reload failed"));

        // Modify file
        TimeUnit.MILLISECONDS.sleep(100);
        Files.writeString(rulesPath, "[{\"updated\": true}]");

        // Trigger update manually
        java.lang.reflect.Method checkMethod = EngineModelManager.class.getDeclaredMethod("checkForUpdates");
        checkMethod.setAccessible(true);
        checkMethod.invoke(manager);

        // Should still have the old model
        assertThat(manager.getEngineModel()).isEqualTo(engineModel);

        // Verify we tried to compile (initial + reload attempt)
        verify(compiler, times(2)).compile(rulesPath);
    }
}
