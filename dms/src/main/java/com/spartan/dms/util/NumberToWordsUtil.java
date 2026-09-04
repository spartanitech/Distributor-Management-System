package com.spartan.dms.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spells out a rupee amount for the "Amount Chargeable (in words)" line on
 * an invoice, e.g. 36360.00 -> "Thirty Six Thousand Three Hundred Sixty
 * Only". Indian numbering (lakh/crore), matching how the sample tax
 * invoices already word amounts.
 */
public final class NumberToWordsUtil {

    private NumberToWordsUtil() {
    }

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
            "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    public static String rupeesInWords(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        long rupees = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();

        if (rupees == 0) {
            return "Zero Only";
        }

        StringBuilder sb = new StringBuilder();
        long crore = rupees / 10000000;
        rupees %= 10000000;
        long lakh = rupees / 100000;
        rupees %= 100000;
        long thousand = rupees / 1000;
        rupees %= 1000;
        long hundred = rupees / 100;
        long remainder = rupees % 100;

        if (crore > 0) {
            sb.append(twoDigitGroup(crore)).append(" Crore ");
        }
        if (lakh > 0) {
            sb.append(twoDigitGroup(lakh)).append(" Lakh ");
        }
        if (thousand > 0) {
            sb.append(twoDigitGroup(thousand)).append(" Thousand ");
        }
        if (hundred > 0) {
            sb.append(ONES[(int) hundred]).append(" Hundred ");
        }
        if (remainder > 0) {
            if (sb.length() > 0) {
                sb.append("");
            }
            sb.append(twoDigitGroup(remainder)).append(" ");
        }

        return sb.toString().trim() + " Only";
    }

    private static String twoDigitGroup(long n) {
        if (n < 20) {
            return ONES[(int) n];
        }
        long tens = n / 10;
        long ones = n % 10;
        return TENS[(int) tens] + (ones > 0 ? " " + ONES[(int) ones] : "");
    }
}
