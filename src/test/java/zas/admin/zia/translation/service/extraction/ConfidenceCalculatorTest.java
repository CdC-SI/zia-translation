package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceCalculatorTest {

    private final ConfidenceCalculator calculator = new ConfidenceCalculator(0.3, 0.5);

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            // model confidence is clamped
            "STRING     | Müller        | Herr Mueller, Müller AG    | 1.5  | 1.0",
            "STRING     | Müller        | Patient: MULLER            | -1   | 0.0",
            "STRING     | Müller        | Patient: MULLER            | NULL | 0.0",
            // no evidence
            "STRING     | Müller        | NULL                       | 0.9  | 0.3",
            "STRING     | Müller        | '  '                       | 0.2  | 0.2",
            // evidence matching (accents, case, spaces folded)
            "STRING     | Hans  Müller  | Name: hans muller          | 0.9  | 0.9",
            "STRING     | Meier         | Name: Müller               | 0.9  | 0.5",
            "IDENTIFIER | 7601000152916 | GLN: 7601 0001 5291 6      | 0.9  | 0.9",
            "IDENTIFIER | 7601000152916 | GLN: 7601 0001 5291 7      | 0.9  | 0.5",
            "AMOUNT     | 1234.50       | Total CHF 1'234.50         | 0.8  | 0.8",
            "AMOUNT     | 1234.50       | Total CHF 1'243.50         | 0.8  | 0.5",
            "AMOUNT     | 1234.50       | Total CHF 1.234,50         | 0.8  | 0.8",
            "AMOUNT     | 1234.5        | Total CHF 1’234.50         | 0.8  | 0.8",
            "AMOUNT     | -15.00        | Total CHF -15.00           | 0.8  | 0.8",
            "NUMBER     | 1234          | Anzahl 1.234               | 0.8  | 0.8",
            "NUMBER     | 1.234         | Anzahl 1.234               | 0.8  | 0.8",
            // complete numbers are compared: magnitude, sign and separate numbers
            "AMOUNT     | 660.00        | Total CHF 6600.00          | 0.8  | 0.5",
            "AMOUNT     | -15.00        | Total CHF 15.00            | 0.8  | 0.5",
            "AMOUNT     | 15.00         | Total CHF -15.00           | 0.8  | 0.5",
            "AMOUNT     | 12.34         | 'Quantity 12, unit price 34' | 0.8 | 0.5",
            "AMOUNT     | 12.34         | Quantity 12 34             | 0.8  | 0.5",
            "DATE       | 2024-03-12    | Datum: 12.03.2024          | 0.9  | 0.9",
            "DATE       | 2024-03-12    | Datum: 12. 3. 2024         | 0.9  | 0.9",
            "DATE       | 2024-03-12    | Datum: 13.03.2024          | 0.9  | 0.5",
            "DATE       | 2024-03-12    | Bern, 12. März 2024        | 0.9  | 0.9",
            "DATE       | 2024-03-12    | Bern, 12. März 2023        | 0.9  | 0.5",
            "DATE       | 2024-03-12    | Bern, 13. März 2024        | 0.9  | 0.5",
            "DATE       | 2024-03-12    | Bern, 12. April 2024       | 0.9  | 0.5",
            "DATE       | 2024-03-12    | Rechnung 2024              | 0.9  | 0.5",
            "DATE       | 2024-03-12    | Rechnung 2024/12 März      | 0.9  | 0.5",
            "DATE       | 2024-04-01    | Lausanne, le 1er avril 2024 | 0.9 | 0.9",
            "DATE       | 2024-08-15    | Lugano, 15 agosto 2024     | 0.9  | 0.9",
            "DATE       | 2024-03-12    | 'March 12, 2024'           | 0.9  | 0.9",
            "DATE       | 2024-12-03    | Genève, 3 déc. 2024        | 0.9  | 0.9",
            // date with a 2-digit year, e.g. the service lines of a Tarmed form
            "DATE       | 2026-10-08    | 08.10.26 007 AA.25.0030 1  | 0.9  | 0.9",
            "DATE       | 2026-10-08    | 09.10.26 007 AA.25.0030 1  | 0.9  | 0.5",
            "DATE       | 2026-10-08    | 08.10.2026 / 72587         | 0.9  | 0.9",
            "DATE       | 2024-03-12    | Datum: 12.03.2025          | 0.9  | 0.5",
            "ENUM       | male          | Herr                       | 0.9  | 0.9",
    })
    void compute(FieldType type, String value, String evidence, Double modelConfidence, double expected) {
        FieldDefinition field = new FieldDefinition("key", type, null, type == FieldType.ENUM ? List.of("male") : null);

        assertThat(calculator.compute(field, value, evidence, modelConfidence)).isEqualTo(expected);
    }
}
