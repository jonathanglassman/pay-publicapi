package uk.gov.pay.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.Optional;

@ApiModel(value = "PaymentRefundRequest", description = "The Payment Refund Request Payload")
public class CreatePaymentRefundRequest {

    public static final String REFUND_AMOUNT_AVAILABLE="refund_amount_available";

    private int amount;
    @JsonProperty("refund_amount_available")
    private Integer refundAmountAvailable;

    public CreatePaymentRefundRequest() {
    }

    public CreatePaymentRefundRequest(int amount, Integer refundAmountAvailable) {
        this.amount = amount;
        this.refundAmountAvailable = refundAmountAvailable;
    }

    @ApiModelProperty(value = "Amount in pence. Can't be more than the available amount for refunds", required = true, allowableValues = "range[1, 10000000]", example = "150000")
    public int getAmount() {
        return amount;
    }

    /**
     * This field should be made compulsory at a later stage
     * @return
     */
    @ApiModelProperty(value = "Amount in pence. Total amount still available before issuing the refund", required = false, allowableValues = "range[1, 10000000]", example = "200000")
    public Optional<Integer> getRefundAmountAvailable() {
        return Optional.ofNullable(refundAmountAvailable);
    }

    @Override
    public String toString() {
        return "CreatePaymentRefundRequest{" +
                "amount=" + amount +
                ", refundAmountAvailable=" + refundAmountAvailable +
                '}';
    }
}
