package com.estatebeacon.agent;

import com.estatebeacon.model.CityNoticeSchema;
import com.estatebeacon.model.DisputeSchema;
import com.estatebeacon.model.MaintenanceSchema;
import com.estatebeacon.model.UniversalRoutingData;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Optional;

/** Java equivalent of the Python {@code PropertyManagementState} TypedDict. */
public final class PropertyManagementState extends AgentState {

    public static final String ORIGINAL_EMAIL_SUBJECT = "originalEmailSubject";
    public static final String ORIGINAL_EMAIL_SENDER = "originalEmailSender";
    public static final String ORIGINAL_EMAIL_TEXT = "originalEmailText";
    public static final String ROUTING_DATA = "routingData";
    public static final String CITY_NOTICE = "cityNotice";
    public static final String MAINTENANCE = "maintenance";
    public static final String DISPUTE = "dispute";
    public static final String ERROR = "error";

    public PropertyManagementState(Map<String, Object> initData) {
        super(initData);
    }

    public String originalEmailSubject() {
        return this.<String>value(ORIGINAL_EMAIL_SUBJECT).orElse("");
    }

    public String originalEmailSender() {
        return this.<String>value(ORIGINAL_EMAIL_SENDER).orElse("");
    }

    public String originalEmailText() {
        return this.<String>value(ORIGINAL_EMAIL_TEXT).orElse("");
    }

    public Optional<UniversalRoutingData> routingData() {
        return this.value(ROUTING_DATA);
    }

    public Optional<CityNoticeSchema> cityNotice() {
        return this.value(CITY_NOTICE);
    }

    public Optional<MaintenanceSchema> maintenance() {
        return this.value(MAINTENANCE);
    }

    public Optional<DisputeSchema> dispute() {
        return this.value(DISPUTE);
    }

    public Optional<String> error() {
        return this.value(ERROR);
    }
}
