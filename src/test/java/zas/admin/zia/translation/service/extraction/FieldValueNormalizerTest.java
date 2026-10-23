package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldValueNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "STRING     | '  Dr. Müller '  | Dr. Müller",
            "STRING     | '   '            | NULL",
            "DATE       | 2024-03-12       | 2024-03-12",
            "DATE       | 12.03.2024       | 2024-03-12",
            "DATE       | 1.3.2024         | 2024-03-01",
            "DATE       | 12/03/2024       | 2024-03-12",
            "DATE       | 12-03-2024       | 2024-03-12",
            "DATE       | 12. 03. 2024     | 2024-03-12",
            "DATE       | 2024-03-12T10:00 | 2024-03-12",
            "DATE       | 31.02.2024       | NULL",
            "DATE       | 12.03.24         | NULL",
            "DATE       | March 2024       | NULL",
            "AMOUNT     | CHF 1'234.50     | 1234.50",
            "AMOUNT     | Fr. 120.-        | 120",
            "AMOUNT     | 120.--           | 120",
            "AMOUNT     | 12,50            | 12.50",
            "AMOUNT     | 1.234,50         | 1234.50",
            "AMOUNT     | 1,234.50         | 1234.50",
            "AMOUNT     | -15.00           | -15.00",
            "AMOUNT     | abc              | NULL",
            "NUMBER     | 8.1 %            | 8.1",
            "NUMBER     | 2                | 2",
            "IDENTIFIER | 756.1234.5678.97 | 7561234567897",
            "IDENTIFIER | ch93 0076 2011   | CH9300762011",
            "IDENTIFIER | ' - . '          | NULL",
    })
    void normalize(FieldType type, String value, String expected) {
        assertThat(FieldValueNormalizer.normalize(new FieldDefinition("key", type, null, null), value))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {"Male, male", "FEMALE, female", "other, NULL"})
    void normalize_enum_returnsCanonicalValue(String value, String expected) {
        FieldDefinition sex = new FieldDefinition("sex", FieldType.ENUM, null, List.of("male", "female"));

        assertThat(FieldValueNormalizer.normalize(sex, value)).isEqualTo(expected);
    }
}
