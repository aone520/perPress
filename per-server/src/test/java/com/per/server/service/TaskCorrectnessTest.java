package com.per.server.service;

import com.per.server.entity.TaskNode;
import com.per.server.entity.TestTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskCorrectnessTest {

    @Test
    void scheduledTimeUsesDocumentedDateTimeFormat() {
        assertEquals(LocalDateTime.of(2026, 10, 4, 12, 30, 45),
                TaskService.parseScheduledStartTime("2026-10-04 12:30:45"));
    }

    @Test
    void multiNodeFinalStatusDistinguishesPartialFailure() {
        assertEquals(TestTask.STATUS_FINISHED, TaskOrchestrator.resolveFinalStatus(List.of(
                node(TaskNode.STATUS_FINISHED), node(TaskNode.STATUS_FINISHED))));
        assertEquals(TestTask.STATUS_FAILED, TaskOrchestrator.resolveFinalStatus(List.of(
                node(TaskNode.STATUS_FAILED), node(TaskNode.STATUS_FAILED))));
        assertEquals(TestTask.STATUS_PARTIAL_FAILED, TaskOrchestrator.resolveFinalStatus(List.of(
                node(TaskNode.STATUS_FINISHED), node(TaskNode.STATUS_FAILED))));
    }

    private TaskNode node(String status) {
        TaskNode node = new TaskNode();
        node.setStatus(status);
        return node;
    }
}
