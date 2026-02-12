package com.durion.accounting.enums;

/**
 * Enum representing the lifecycle status of a refund payment.
 * 
 * A refund payment goes through several stages from initiation to completion:
 * - INITIATED: Refund request has been created and is pending approval
 * - APPROVED: Refund has been approved and is ready for processing
 * - PROCESSING: Refund payment is being processed (e.g., sent to payment gateway)
 * - COMPLETED: Refund payment has been successfully completed
 * - FAILED: Refund payment failed during processing
 * - CANCELLED: Refund was cancelled before completion
 * 
 * This status enum is used to track refund payments through the DurRefundPayment entity
 * and corresponds to the statusId field in the database.
 */
public enum RefundPaymentStatus {
    
    /**
     * Refund has been initiated and is pending approval.
     * This is the initial state when a refund request is created.
     */
    INITIATED("REFUND_INITIATED", "Initiated", "Refund request has been created and is pending approval"),
    
    /**
     * Refund has been approved by authorized personnel.
     * The refund is ready to be processed.
     */
    APPROVED("REFUND_APPROVED", "Approved", "Refund has been approved and is ready for processing"),
    
    /**
     * Refund payment is currently being processed.
     * This state indicates the payment is in transit.
     */
    PROCESSING("REFUND_PROCESSING", "Processing", "Refund payment is being processed"),
    
    /**
     * Refund payment has been successfully completed.
     * This is the terminal success state.
     */
    COMPLETED("REFUND_COMPLETED", "Completed", "Refund payment has been successfully completed"),
    
    /**
     * Refund payment failed during processing.
     * This state indicates an error occurred that prevented the refund from completing.
     */
    FAILED("REFUND_FAILED", "Failed", "Refund payment failed during processing"),
    
    /**
     * Refund was cancelled before completion.
     * This state indicates the refund was intentionally stopped.
     */
    CANCELLED("REFUND_CANCELLED", "Cancelled", "Refund was cancelled before completion");
    
    private final String statusId;
    private final String displayName;
    private final String description;
    
    /**
     * Constructor for RefundPaymentStatus enum.
     * 
     * @param statusId The database ID for this status (matches Moqui statusId field)
     * @param displayName User-friendly display name for this status
     * @param description Detailed description of what this status means
     */
    RefundPaymentStatus(String statusId, String displayName, String description) {
        this.statusId = statusId;
        this.displayName = displayName;
        this.description = description;
    }
    
    /**
     * Gets the database status ID.
     * This value is used in the statusId field of the DurRefundPayment entity.
     * 
     * @return The status ID string
     */
    public String getStatusId() {
        return statusId;
    }
    
    /**
     * Gets the user-friendly display name.
     * 
     * @return The display name string
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the detailed description of this status.
     * 
     * @return The description string
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Finds a RefundPaymentStatus by its status ID.
     * 
     * @param statusId The status ID to search for
     * @return The matching RefundPaymentStatus, or null if not found
     */
    public static RefundPaymentStatus fromStatusId(String statusId) {
        if (statusId == null) {
            return null;
        }
        
        for (RefundPaymentStatus status : values()) {
            if (status.statusId.equals(statusId)) {
                return status;
            }
        }
        
        return null;
    }
    
    /**
     * Checks if this status represents a terminal state (completed, failed, or cancelled).
     * Terminal states cannot transition to other states.
     * 
     * @return true if this is a terminal status, false otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
    
    /**
     * Checks if a transition from this status to the target status is valid.
     * 
     * Valid transitions:
     * - INITIATED -> APPROVED, CANCELLED
     * - APPROVED -> PROCESSING, CANCELLED
     * - PROCESSING -> COMPLETED, FAILED
     * - Terminal states cannot transition
     * 
     * @param targetStatus The status to transition to
     * @return true if the transition is valid, false otherwise
     */
    public boolean canTransitionTo(RefundPaymentStatus targetStatus) {
        if (targetStatus == null || this.isTerminal()) {
            return false;
        }
        
        switch (this) {
            case INITIATED:
                return targetStatus == APPROVED || targetStatus == CANCELLED;
            case APPROVED:
                return targetStatus == PROCESSING || targetStatus == CANCELLED;
            case PROCESSING:
                return targetStatus == COMPLETED || targetStatus == FAILED;
            default:
                return false;
        }
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
