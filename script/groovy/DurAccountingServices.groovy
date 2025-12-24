// Groovy service implementations for DurAccountingServices

def applyPayment() {
    def paymentId = context.paymentId
    def invoiceId = context.invoiceId
    def amount = context.amount
    
    def payment = ec.entity.find("durion.accounting.DurPayment")
            .condition("paymentId", paymentId).one()
    
    if (!payment) {
        ec.message.addError("Payment not found: ${paymentId}")
        return [success: false]
    }
    
    def amountToApply = amount ?: payment.paymentAmount
    
    // Find outstanding AR transaction for invoice
    def arTransaction = ec.entity.find("durion.accounting.DurArTransaction")
            .condition("invoiceId", invoiceId)
            .condition("transactionType", "INVOICE")
            .one()
    
    if (!arTransaction) {
        ec.message.addWarning("No invoice found for: ${invoiceId}")
        return [success: false]
    }
    
    def currentBalance = arTransaction.balanceAmount ?: 0.0
    def newBalance = Math.max(0, currentBalance - amountToApply)
    def actualApplied = currentBalance - newBalance
    
    // Update balance
    arTransaction.balanceAmount = newBalance
    arTransaction.update()
    
    // Update payment status
    if (newBalance == 0) {
        payment.statusId = "PAYMENT_APPLIED"
    }
    payment.update()
    
    // Create payment AR transaction
    ec.service.sync().name("create#durion.accounting.DurArTransaction")
            .parameters([
                invoiceId: invoiceId,
                customerId: payment.customerId,
                transactionType: "PAYMENT",
                transactionDate: ec.user.nowTimestamp,
                amount: actualApplied,
                balanceAmount: 0,
                statusId: "AR_TRANS_POSTED",
                postedDate: ec.user.nowTimestamp,
                createdBy: ec.user.userAccount.userId
            ]).call()
    
    return [
        success: true,
        amountApplied: actualApplied,
        remainingBalance: newBalance
    ]
}

def getArAging() {
    def customerId = context.customerId
    def asOfDate = context.asOfDate ?: ec.user.nowTimestamp
    
    def finder = ec.entity.find("durion.accounting.DurArTransaction")
            .condition("transactionType", "INVOICE")
    
    if (customerId) {
        finder.condition("customerId", customerId)
    }
    
    def transactions = finder.list()
    
    def current = 0.0
    def days30 = 0.0
    def days60 = 0.0
    def days90Plus = 0.0
    
    transactions.each { trans ->
        def balance = trans.balanceAmount ?: 0.0
        if (balance > 0) {
            def daysDue = (asOfDate.time - trans.transactionDate.time) / (1000 * 60 * 60 * 24) as Integer
            
            if (daysDue <= 30) {
                current += balance
            } else if (daysDue <= 60) {
                days30 += balance
            } else if (daysDue <= 90) {
                days60 += balance
            } else {
                days90Plus += balance
            }
        }
    }
    
    def totalOutstanding = current + days30 + days60 + days90Plus
    
    return [
        agingData: [
            current: current,
            days30: days30,
            days60: days60,
            days90Plus: days90Plus
        ],
        current: current,
        days30: days30,
        days60: days60,
        days90Plus: days90Plus,
        totalOutstanding: totalOutstanding
    ]
}

def depositPayment() {
    def paymentId = context.paymentId
    def depositDate = context.depositDate ?: new Date()
    
    def payment = ec.entity.find("durion.accounting.DurPayment")
            .condition("paymentId", paymentId).one()
    
    if (!payment) {
        ec.message.addError("Payment not found: ${paymentId}")
        return [success: false]
    }
    
    payment.depositDate = depositDate
    payment.update()
    
    ec.logger.info("Payment ${paymentId} deposited on ${depositDate}")
    
    return [success: true]
}

def clearPayment() {
    def paymentId = context.paymentId
    def clearedDate = context.clearedDate ?: new Date()
    
    def payment = ec.entity.find("durion.accounting.DurPayment")
            .condition("paymentId", paymentId).one()
    
    if (!payment) {
        ec.message.addError("Payment not found: ${paymentId}")
        return [success: false]
    }
    
    payment.clearedDate = clearedDate
    payment.statusId = "PAYMENT_CLEARED"
    payment.update()
    
    ec.logger.info("Payment ${paymentId} cleared on ${clearedDate}")
    
    return [success: true]
}

def getPaymentSummary() {
    def customerId = context.customerId
    def startDate = context.startDate
    def endDate = context.endDate
    
    def finder = ec.entity.find("durion.accounting.DurPayment")
    
    if (customerId) {
        finder.condition("customerId", customerId)
    }
    
    if (startDate) {
        finder.condition("paymentDate", ">=", startDate)
    }
    
    if (endDate) {
        finder.condition("paymentDate", "<=", endDate)
    }
    
    def payments = finder.list()
    
    def paymentCount = payments.size()
    def totalReceived = 0.0
    def methodCount = [:]
    
    payments.each { payment ->
        totalReceived += payment.paymentAmount ?: 0.0
        def method = payment.paymentMethod
        methodCount[method] = (methodCount[method] ?: 0) + 1
    }
    
    def averagePayment = paymentCount > 0 ? totalReceived / paymentCount : 0.0
    
    return [
        paymentCount: paymentCount,
        totalReceived: totalReceived,
        averagePayment: averagePayment,
        methodSummary: methodCount
    ]
}
