package com.netverify.controlplane.api;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.api.dto.TestResponse;
import com.netverify.controlplane.domain.TestRecord;
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
}
