package com.netverify.controlplane.api;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.api.dto.RunAcceptedResponse;
import com.netverify.controlplane.api.dto.RunSummaryDto;
import com.netverify.controlplane.api.dto.TestLogsResponse;
import com.netverify.controlplane.api.dto.TestMetricsResponse;
import com.netverify.controlplane.api.dto.TestResponse;
import com.netverify.controlplane.api.dto.TestResultResponse;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.config.ConditionalOnApiSurface;
import com.netverify.controlplane.service.TestService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tests")
@ConditionalOnApiSurface
public class TestController {

    private final TestService testService;

    public TestController(TestService testService) {
        this.testService = testService;
    }

    @PostMapping
    public ResponseEntity<TestResponse> create(@Valid @RequestBody CreateTestRequest request) {
        TestRecord record = testService.createTest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TestResponse.from(record));
    }

    @GetMapping
    public List<TestResponse> list() {
        return testService.listTests().stream().map(TestResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TestResponse get(@PathVariable UUID id) {
        return TestResponse.from(testService.getTest(id));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<RunAcceptedResponse> run(@PathVariable UUID id) {
        TestService.QueuedRun queued = testService.runTest(id);
        return ResponseEntity.accepted()
                .body(RunAcceptedResponse.queued(queued.id(), queued.queuedAt()));
    }

    /**
     * Newest-first run history. Entries expose the actual run UUID; {@code POST
     * /run} remains backward-compatible and returns the test id.
     */
    @GetMapping("/{id}/runs")
    public List<RunSummaryDto> runs(@PathVariable UUID id) {
        return testService.listRuns(id);
    }

    @GetMapping("/{id}/results")
    public TestResultResponse results(@PathVariable UUID id) {
        return TestResultResponse.from(testService.getTerminalTest(id));
    }

    @GetMapping("/{id}/logs")
    public TestLogsResponse logs(@PathVariable UUID id) {
        return TestLogsResponse.from(testService.getTerminalTest(id));
    }

    @GetMapping("/{id}/metrics")
    public TestMetricsResponse metrics(@PathVariable UUID id) {
        return TestMetricsResponse.from(testService.getTerminalTest(id));
    }
}
