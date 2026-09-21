package com.leaseflow.payment;

import com.leaseflow.common.exception.BusinessRuleViolationException;
import com.leaseflow.common.exception.DuplicateResourceException;
import com.leaseflow.common.exception.ResourceNotFoundException;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.payment.dto.PaymentRegistrationResponse;
import com.leaseflow.payment.dto.RegisterPaymentRequest;
import com.leaseflow.schedule.PaymentScheduleItem;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class RentPaymentService {

    private final LeaseContractRepository contractRepository;
    private final PaymentScheduleItemRepository scheduleItemRepository;
    private final RentPaymentRepository paymentRepository;

    public RentPaymentService(LeaseContractRepository contractRepository,
                              PaymentScheduleItemRepository scheduleItemRepository,
                              RentPaymentRepository paymentRepository) {
        this.contractRepository = contractRepository;
        this.scheduleItemRepository = scheduleItemRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentRegistrationResponse registerPayment(String contractNo, int periodNo,
                                                       RegisterPaymentRequest request) {
        LeaseContract contract = contractRepository.findByContractNo(contractNo)
                .orElseThrow(() -> new ResourceNotFoundException("合同不存在: " + contractNo));
        PaymentScheduleItem scheduleItem = scheduleItemRepository
                .findByContractIdAndPeriodNoForUpdate(contract.getId(), periodNo)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "租金期次不存在: 合同 %s 期次 %d".formatted(contractNo, periodNo)));

        if (paymentRepository.existsByPaymentNo(request.paymentNo())) {
            throw new DuplicateResourceException("回款流水号已存在: " + request.paymentNo());
        }

        BigDecimal amount = request.amount();
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolationException("回款金额必须大于 0");
        }
        BigDecimal outstanding = scheduleItem.getTotalDue().subtract(scheduleItem.getPaidAmount());
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException(
                    "回款金额超过该期未收金额，未收金额: " + outstanding);
        }

        RentPayment payment = new RentPayment(request.paymentNo(), scheduleItem,
                amount, request.paymentDate());
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException("回款流水号已存在: " + request.paymentNo());
        }
        scheduleItem.addPaidAmount(amount);

        BigDecimal paidAmount = scheduleItem.getPaidAmount();
        BigDecimal outstandingAfter = scheduleItem.getTotalDue().subtract(paidAmount);
        PaymentStatus status = PaymentStatus.of(scheduleItem.getTotalDue(), paidAmount);

        return new PaymentRegistrationResponse(
                new PaymentRegistrationResponse.PaymentView(request.paymentNo(), periodNo,
                        amount, request.paymentDate()),
                new PaymentRegistrationResponse.PeriodView(periodNo, scheduleItem.getTotalDue(),
                        paidAmount, outstandingAfter, status.name()));
    }
}
