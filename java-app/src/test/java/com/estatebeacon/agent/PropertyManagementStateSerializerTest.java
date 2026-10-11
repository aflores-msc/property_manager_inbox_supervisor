package com.estatebeacon.agent;

import com.estatebeacon.model.CityNoticeSchema;
import com.estatebeacon.model.Classification;
import com.estatebeacon.model.DisputeSchema;
import com.estatebeacon.model.MaintenanceSchema;
import com.estatebeacon.model.PriorityLevel;
import com.estatebeacon.model.UniversalRoutingData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyManagementStateSerializerTest {

    @Test
    void roundTripsEveryTypedRecordFieldThroughJson() throws Exception {
        var serializer = new PropertyManagementStateSerializer();

        var routingData = new UniversalRoutingData(
                "tenant", "123 Main St", "4B", Classification.CITY_NOTICE, PriorityLevel.HIGH);
        var cityNotice = new CityNoticeSchema(
                "code violation", "City Building Dept", "2025-01-01", "Broken fence", "Repair fence",
                "Jane Inspector", "555-0100", "jane@city.example");
        var maintenance = new MaintenanceSchema(
                "plumbing", "kitchen", "high", "Sink is leaking", "weekday evenings");
        var dispute = new DisputeSchema(
                "noise", "Unit 4B and Unit 4C", "Loud music after 10pm", "Quiet hours enforced", true);

        Map<String, Object> original = Map.of(
                PropertyManagementState.ORIGINAL_EMAIL_SUBJECT, "Leak under the sink",
                PropertyManagementState.ROUTING_DATA, routingData,
                PropertyManagementState.CITY_NOTICE, cityNotice,
                PropertyManagementState.MAINTENANCE, maintenance,
                PropertyManagementState.DISPUTE, dispute,
                PropertyManagementState.ERROR, "Legal agent extraction failed: timeout"
        );

        PropertyManagementState cloned = serializer.cloneObject(original);

        assertThat(cloned.originalEmailSubject()).isEqualTo("Leak under the sink");
        assertThat(cloned.routingData()).contains(routingData);
        assertThat(cloned.cityNotice()).contains(cityNotice);
        assertThat(cloned.maintenance()).contains(maintenance);
        assertThat(cloned.dispute()).contains(dispute);
        assertThat(cloned.error()).contains("Legal agent extraction failed: timeout");
    }

    /**
     * Guards against the exact failure mode {@code TYPED_FIELDS} is fragile to: a new
     * record-valued accessor added to {@link PropertyManagementState} without a matching
     * registration would otherwise come back as a raw {@code Map} after every graph step (state
     * is cloned via JSON on each one) and blow up far from the cause, as a {@code
     * ClassCastException} in whichever node reads it next.
     */
    @Test
    void typedFieldsCoversEveryRecordValuedAccessor() {
        Set<Class<?>> recordAccessorTypes = new HashSet<>();
        for (Method method : PropertyManagementState.class.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != Optional.class) {
                continue;
            }
            var elementType = (Class<?>) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
            if (elementType.isRecord()) {
                recordAccessorTypes.add(elementType);
            }
        }

        assertThat(recordAccessorTypes)
                .as("every record type returned by an Optional<...> accessor on PropertyManagementState "
                        + "must be registered in TYPED_FIELDS, or it silently degrades to a raw Map after "
                        + "the next JSON round trip")
                .isEqualTo(Set.copyOf(PropertyManagementState.TYPED_FIELDS.values()));
    }
}
