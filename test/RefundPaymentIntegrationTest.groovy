package durion.accounting.test

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import spock.lang.Specification
import spock.lang.Shared

/**
 * Integration Tests for Refund Payment functionality
 * Tests for: Refund payment workflow, status transitions, validation, and business rules
 */
class RefundPaymentIntegrationTest extends Specification {

    @Shared ExecutionContext ec
    @Shared def slurper = new JsonSlurper()

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        // Clean test data
        ec.entity.find("durion.accounting.DurRefundPayment").disableAuthz().deleteAll()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    // ============================================
    // REFUND PAYMENT WORKFLOW TESTS
    // ============================================

    def "should execute complete refund payment workflow: INITIATED → APPROVED → PROCESSING → COMPLETED"() {
        when: "initiate refund payment"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-001",
            originalPaymentId: "PAY-001",
            invoiceId: "INV-001",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Product return",
            glAccountId: "GL-1010"
        ])

        then:
        initiateResult.refundPayment.statusId == "REFUND_INITIATED"
        initiateResult.refundPayment.refundAmount == 100.00
        initiateResult.refundPayment.customerId == "CUST-001"

        when: "approve refund"
        def refundPaymentId = initiateResult.refundPaymentId
        def approveResult = ec.service.sync("durion.approveRefundPayment", [
            refundPaymentId: refundPaymentId,
            approvedBy: "manager"
        ])

        then:
        approveResult.success == true
        approveResult.refundPayment.statusId == "REFUND_APPROVED"
        approveResult.refundPayment.approvedBy == "manager"
        approveResult.refundPayment.approvalDate != null

        when: "process refund"
        def processResult = ec.service.sync("durion.processRefundPayment", [
            refundPaymentId: refundPaymentId,
            referenceNumber: "CHK-9999"
        ])

        then:
        processResult.success == true
        processResult.refundPayment.statusId == "REFUND_PROCESSING"
        processResult.refundPayment.referenceNumber == "CHK-9999"
        processResult.refundPayment.processingDate != null

        when: "complete refund"
        def completeResult = ec.service.sync("durion.completeRefundPayment", [
            refundPaymentId: refundPaymentId
        ])

        then:
        completeResult.success == true
        completeResult.refundPayment.statusId == "REFUND_COMPLETED"
        completeResult.refundPayment.completedDate != null
    }

    def "should create AR transaction when refund is completed"() {
        given: "a refund payment in processing state"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-002",
            invoiceId: "INV-002",
            refundAmount: 250.00,
            refundMethod: "ACH",
            reason: "Billing error"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])
        ec.service.sync("durion.processRefundPayment", [refundPaymentId: refundPaymentId])

        when: "complete the refund"
        ec.service.sync("durion.completeRefundPayment", [refundPaymentId: refundPaymentId])

        then: "AR transaction should be created"
        def arTransactions = ec.entity.find("durion.accounting.DurArTransaction")
            .condition("customerId", "CUST-002")
            .condition("transactionType", "REFUND")
            .list()
        arTransactions.size() > 0
        arTransactions[0].amount == -250.00
        arTransactions[0].statusId == "AR_TRANS_POSTED"
    }

    def "should allow cancelling refund from INITIATED state"() {
        given:
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-003",
            refundAmount: 50.00,
            refundMethod: "STORE_CREDIT",
            reason: "Test cancellation"
        ])
        def refundPaymentId = initiateResult.refundPaymentId

        when:
        def cancelResult = ec.service.sync("durion.cancelRefundPayment", [
            refundPaymentId: refundPaymentId,
            cancellationReason: "Customer changed mind"
        ])

        then:
        cancelResult.success == true
        cancelResult.refundPayment.statusId == "REFUND_CANCELLED"
        cancelResult.refundPayment.notes.contains("Customer changed mind")
    }

    def "should allow cancelling refund from APPROVED state"() {
        given:
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-001",
            refundAmount: 75.00,
            refundMethod: "CHECK",
            reason: "Test cancellation from approved"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])

        when:
        def cancelResult = ec.service.sync("durion.cancelRefundPayment", [
            refundPaymentId: refundPaymentId,
            cancellationReason: "Duplicate request"
        ])

        then:
        cancelResult.success == true
        cancelResult.refundPayment.statusId == "REFUND_CANCELLED"
    }

    def "should mark refund as failed from PROCESSING state"() {
        given: "a refund in processing state"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-002",
            refundAmount: 200.00,
            refundMethod: "ACH",
            reason: "Test failure"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])
        ec.service.sync("durion.processRefundPayment", [refundPaymentId: refundPaymentId])

        when: "mark as failed"
        def failResult = ec.service.sync("durion.failRefundPayment", [
            refundPaymentId: refundPaymentId,
            failureReason: "Invalid bank account"
        ])

        then:
        failResult.success == true
        failResult.refundPayment.statusId == "REFUND_FAILED"
        failResult.refundPayment.failureReason == "Invalid bank account"
    }

    // ============================================
    // VALIDATION AND ERROR TESTS
    // ============================================

    def "should not allow approval from non-INITIATED state"() {
        given: "a refund already approved"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-001",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])

        when: "try to approve again"
        def result = ec.service.sync("durion.approveRefundPayment", [
            refundPaymentId: refundPaymentId
        ])

        then:
        result.success == false
        ec.message.getErrors().size() > 0
    }

    def "should not allow processing from non-APPROVED state"() {
        given: "a refund in initiated state"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-002",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        def refundPaymentId = initiateResult.refundPaymentId

        when: "try to process without approval"
        def result = ec.service.sync("durion.processRefundPayment", [
            refundPaymentId: refundPaymentId
        ])

        then:
        result.success == false
        ec.message.getErrors().size() > 0
    }

    def "should not allow completion from non-PROCESSING state"() {
        given: "a refund in approved state"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-003",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])

        when: "try to complete without processing"
        def result = ec.service.sync("durion.completeRefundPayment", [
            refundPaymentId: refundPaymentId
        ])

        then:
        result.success == false
        ec.message.getErrors().size() > 0
    }

    def "should not allow cancelling from PROCESSING state"() {
        given: "a refund in processing state"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-001",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])
        ec.service.sync("durion.processRefundPayment", [refundPaymentId: refundPaymentId])

        when: "try to cancel from processing"
        def result = ec.service.sync("durion.cancelRefundPayment", [
            refundPaymentId: refundPaymentId,
            cancellationReason: "Test"
        ])

        then:
        result.success == false
        ec.message.getErrors().size() > 0
    }

    def "should not allow failing from non-PROCESSING state"() {
        given: "a refund in approved state"
        def initiateResult = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-002",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        def refundPaymentId = initiateResult.refundPaymentId
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refundPaymentId])

        when: "try to fail from approved state"
        def result = ec.service.sync("durion.failRefundPayment", [
            refundPaymentId: refundPaymentId,
            failureReason: "Test"
        ])

        then:
        result.success == false
        ec.message.getErrors().size() > 0
    }

    // ============================================
    // QUERY AND SUMMARY TESTS
    // ============================================

    def "should find refund payments by customer"() {
        given: "multiple refund payments for different customers"
        ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-FIND-001",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test 1"
        ])
        ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-FIND-001",
            refundAmount: 200.00,
            refundMethod: "ACH",
            reason: "Test 2"
        ])
        ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-FIND-002",
            refundAmount: 150.00,
            refundMethod: "CHECK",
            reason: "Test 3"
        ])

        when: "find by customer"
        def result = ec.service.sync("durion.findRefundPayments", [
            customerId: "CUST-FIND-001"
        ])

        then:
        result.refundPaymentList.size() == 2
        result.refundPaymentList.every { it.customerId == "CUST-FIND-001" }
    }

    def "should find refund payments by status"() {
        given: "refunds in different statuses"
        def refund1 = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-STATUS-001",
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        def refund2 = ec.service.sync("durion.initiateRefundPayment", [
            customerId: "CUST-STATUS-002",
            refundAmount: 200.00,
            refundMethod: "CHECK",
            reason: "Test"
        ])
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refund2.refundPaymentId])

        when: "find by status"
        def result = ec.service.sync("durion.findRefundPayments", [
            statusId: "REFUND_INITIATED"
        ])

        then:
        result.refundPaymentList.size() >= 1
        result.refundPaymentList.every { it.statusId == "REFUND_INITIATED" }
    }

    def "should calculate refund payment summary"() {
        given: "several refund payments"
        def customer = "CUST-SUMMARY-001"
        ec.service.sync("durion.initiateRefundPayment", [
            customerId: customer,
            refundAmount: 100.00,
            refundMethod: "CHECK",
            reason: "Test 1"
        ])
        def refund2 = ec.service.sync("durion.initiateRefundPayment", [
            customerId: customer,
            refundAmount: 200.00,
            refundMethod: "ACH",
            reason: "Test 2"
        ])
        ec.service.sync("durion.approveRefundPayment", [refundPaymentId: refund2.refundPaymentId])

        when: "get summary"
        def result = ec.service.sync("durion.getRefundPaymentSummary", [
            customerId: customer
        ])

        then:
        result.refundCount == 2
        result.totalRefunded == 300.00
        result.averageRefund == 150.00
        result.statusSummary["REFUND_INITIATED"] == 1
        result.statusSummary["REFUND_APPROVED"] == 1
    }
}
