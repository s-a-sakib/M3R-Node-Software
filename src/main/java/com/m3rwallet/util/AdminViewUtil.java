package com.m3rwallet.util;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

@Component("adminView")
public class AdminViewUtil {
    private static final BigDecimal POISHA_PER_BDT = new BigDecimal("100");

    public String amount(String baseUnits) {
        if (baseUnits == null || baseUnits.isBlank()) {
            return "0.00";
        }
        try {
            BigDecimal value = new BigDecimal(baseUnits)
                    .divide(POISHA_PER_BDT, 2, RoundingMode.DOWN);
            return value.toPlainString();
        } catch (NumberFormatException e) {
            return baseUnits;
        }
    }

    public String amount(BigInteger baseUnits) {
        return baseUnits == null ? "0.00" : amount(baseUnits.toString());
    }

    public String address(String storedAddress) {
        if (storedAddress == null || storedAddress.isBlank()) {
            return "";
        }

        String normalized = AddressUtil.normalizeAddr(storedAddress);
        if (normalized != null && normalized.matches("^[0-9a-f]{40}$")) {
            String encoded = AddressUtil.encodeHex20ToBase58(normalized);
            return encoded != null ? encoded : storedAddress;
        }

        return storedAddress;
    }

    public boolean contains(String value, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}
