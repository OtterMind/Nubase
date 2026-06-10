package ai.nubase.functions.executor.cloudflare;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record SourceBundle(List<SourceBundleFile> files) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record SourceBundleFile(String path, String content) {
}
