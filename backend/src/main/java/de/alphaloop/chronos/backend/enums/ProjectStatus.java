package de.alphaloop.chronos.backend.enums;
/**
 * Project lifecycle states.
 *
 * Design: Enum with business rules for valid transitions.
 * This is cleaner than database check constraints for complex logic.
 */
public enum ProjectStatus {
    DRAFT("New project, not yet started"),
    ACTIVE("Active project, accepting orders"),
    ON_HOLD("Temporarily suspended"),
    COMPLETED("All orders finished"),
    CANCELLED("Project cancelled");

    private final String description;

    ProjectStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Business rule: valid state transitions.
     */
    public boolean canTransitionTo(ProjectStatus newStatus) {
        return switch (this) {
            case DRAFT -> newStatus == ACTIVE;
            case ACTIVE -> true;
            case ON_HOLD -> newStatus == ACTIVE || newStatus == CANCELLED;
            case COMPLETED -> false; // Terminal state
            case CANCELLED -> false; // Terminal state
        };
    }
}