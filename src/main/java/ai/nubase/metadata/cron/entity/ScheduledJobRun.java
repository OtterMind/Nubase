package ai.nubase.metadata.cron.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_job_runs")
public class ScheduledJobRun {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    /** Claimed but not executed (e.g. the claim expired while queued). */
    public static final String STATUS_SKIPPED = "skipped";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "project_ref", nullable = false, length = 128)
    private String projectRef;

    @Column(name = "job_name", nullable = false, length = 128)
    private String jobName;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void onCreate() {
        if (startedAt == null) startedAt = Instant.now();
    }
}
