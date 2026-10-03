package com.per.agent.task;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.per.agent.client.ServerClient;
import com.per.agent.client.TaskReceipt;
import com.per.agent.common.HttpDownloader;
import com.per.agent.common.Jsons;
import com.per.agent.config.AgentProperties;
import com.per.agent.core.AgentLifecycle;
import com.per.agent.core.ServerClock;
import com.per.agent.engine.EngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JMeterTaskExecutorStartTest {

    @TempDir
    Path tempDir;

    private ServerClient serverClient;
    private AgentLifecycle lifecycle;
    private EngineManager engineManager;
    private ServerClock serverClock;
    private JMeterTaskExecutor executor;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.setDataDir(tempDir.toString());
        serverClient = mock(ServerClient.class);
        lifecycle = mock(AgentLifecycle.class);
        engineManager = mock(EngineManager.class);
        serverClock = mock(ServerClock.class);
        when(lifecycle.currentNodeKey()).thenReturn("node-test");
        executor = new JMeterTaskExecutor(properties, serverClient, lifecycle, engineManager,
                mock(HttpDownloader.class), serverClock);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void duplicateStartWhileAlreadyRunningIsIgnored() throws Exception {
        writeState(1L, TaskState.STATUS_RUNNING);

        executor.handleStart(1L, null);

        verify(engineManager, never()).getJmeterBin();
        verify(serverClient, never()).sendReceipt(argThat(receipt -> receipt.taskId() == 1L));
    }

    @Test
    void duplicateStartDuringScheduledWaitRunsOnlyOnce() throws Exception {
        writeState(2L, TaskState.STATUS_PREPARED);
        CountDownLatch waiting = new CountDownLatch(1);
        when(serverClock.now()).thenAnswer(ignored -> {
            waiting.countDown();
            return System.currentTimeMillis();
        });

        Thread first = new Thread(() -> executor.handleStart(2L, System.currentTimeMillis() + 250));
        first.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        executor.handleStart(2L, System.currentTimeMillis() + 250);
        first.join(2_000);

        verify(engineManager, times(1)).getJmeterBin();
        verify(serverClient, times(1)).sendReceipt(argThat(receipt ->
                receipt.taskId() == 2L && TaskReceipt.PHASE_FAILED.equals(receipt.phase())));
    }

    @Test
    void stopDuringScheduledWaitCancelsProcessStart() throws Exception {
        writeState(3L, TaskState.STATUS_PREPARED);
        CountDownLatch waiting = new CountDownLatch(1);
        when(serverClock.now()).thenAnswer(ignored -> {
            waiting.countDown();
            return System.currentTimeMillis();
        });

        Thread start = new Thread(() -> executor.handleStart(3L, System.currentTimeMillis() + 250));
        start.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        executor.handleStop(3L);
        start.join(2_000);

        verify(engineManager, never()).getJmeterBin();
        verify(serverClient, times(1)).sendReceipt(argThat(receipt ->
                receipt.taskId() == 3L && TaskReceipt.PHASE_STOPPED.equals(receipt.phase())));
    }

    private void writeState(long taskId, String status) throws Exception {
        Path taskDir = tempDir.resolve("tasks").resolve(String.valueOf(taskId));
        Files.createDirectories(taskDir);
        TaskState state = new TaskState(taskId, status, null, null, Map.of(), 256);
        Files.writeString(taskDir.resolve("state.json"), Jsons.write(state), StandardCharsets.UTF_8);
    }
}
