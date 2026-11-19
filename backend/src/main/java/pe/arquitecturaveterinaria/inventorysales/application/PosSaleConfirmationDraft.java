package pe.arquitecturaveterinaria.inventorysales.application;

import java.math.BigDecimal;
import java.util.List;

public record PosSaleConfirmationDraft(String paymentMethod, BigDecimal declaredAmountPen,
		List<PosServiceChargeDraft> serviceCharges) {

	public PosSaleConfirmationDraft(String paymentMethod, BigDecimal declaredAmountPen) {
		this(paymentMethod, declaredAmountPen, List.of());
	}
}
