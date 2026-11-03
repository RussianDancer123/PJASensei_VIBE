package pl.edu.pjasensei.assignment;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The only hosts a SourceRepository may be on, so the server cannot be pointed at internal ones. */
@ConfigurationProperties("pjasensei.assignments")
record AssignmentProperties(List<String> allowedSourceHosts) {}
