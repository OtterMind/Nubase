package ai.nubase.auth.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated response for {@code GET /auth/v1/admin/projects}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectListResponse {

    /** Projects on the current page. */
    private List<ProjectSummaryResponse> projects;

    /** Total number of projects visible to the caller (across all pages). */
    @JsonProperty("total")
    private Long total;

    /** Current page number (1-based). */
    @JsonProperty("page")
    private Integer page;

    /** Page size. */
    @JsonProperty("per_page")
    private Integer perPage;
}
